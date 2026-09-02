package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.Comparator;
import java.util.function.UnaryOperator;

import org.apache.lucene.index.LeafReaderContext;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.factory.primitive.IntLists;
import org.eclipse.collections.api.factory.primitive.LongSets;
import org.eclipse.collections.api.factory.primitive.ObjectLongMaps;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.list.primitive.MutableIntList;
import org.eclipse.collections.api.map.primitive.MutableObjectLongMap;
import org.eclipse.collections.api.set.primitive.MutableLongSet;

import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.SearchResult;

/**
 * Counting a facet over a field whose values are paths through a tree, one
 * level at a time.
 *
 * A value was written once per level it passes through, so the levels of the
 * tree stand in the doc values as values of their own and counting a level is
 * counting the matches that hold it - a product filed under
 * {@code Men/Shoes/Running} is one of the products under {@code Men}. What the
 * counting adds is the shape: the levels asked for are gathered, hung off the
 * level above them and answered nested, so a caller never has to take a
 * separator apart to know what is below what.
 *
 * Which levels are in scope is decided once per segment rather than per
 * document, and so is what a level was counted as: the walk itself only ever
 * raises a number in an array the ordinal indexes. Both hold because ordinals
 * are per segment and the tree of a catalogue is far smaller than the documents
 * filed in it.
 *
 * {@link StringFacetCount} describes what one count means per mode.
 * The levels of one Lucene document are a set already, so only rolling up has
 * anything to deduplicate: a level counts the first time one of a document's
 * values passes through it and never again.
 */
final class HierarchyFacetCount implements FacetCount {
	private final String field;
	private final FacetMatches.Mode mode;
	private final String separator;
	private final UnaryOperator<String> normalize;
	private final Scope scope;
	private final int limit;
	private final Facet.Order order;

	private final MutableObjectLongMap<String> counts = ObjectLongMaps.mutable.empty();

	HierarchyFacetCount(
		String field,
		FacetMatches.Mode mode,
		String separator,
		UnaryOperator<String> normalize,
		String path,
		int depth,
		int limit,
		Facet.Order order
	) {
		this.field = field;
		this.mode = mode;
		this.separator = separator;
		this.normalize = normalize;
		this.scope = new Scope(separator, normalize, path, depth);
		this.limit = limit;
		this.order = order;
	}

	@Override
	public Leaf leaf(LeafReaderContext context, int matches) throws IOException {
		var values = context.reader().getSortedSetDocValues(field);
		if(values == null) {
			return null;
		}

		/*
		 * The slot each ordinal counts into when it is one the facet asked
		 * about, and -1 when it is not. Decided once here over the values
		 * of the segment - decoded and parsed once per segment, not per
		 * search - so the walk neither looks a term up nor measures a path
		 * per document, and counts into an array no larger than what was
		 * asked about rather than one per value the field holds.
		 */
		var hierarchy = FacetStates.hierarchyOf(context, field, values, separator, normalize);
		var slotOfOrd = new int[hierarchy.paths().length];
		var slots = 0;
		for(var ord = 0; ord < slotOfOrd.length; ord++) {
			slotOfOrd[ord] = scope.holds(hierarchy, ord) ? slots++ : -1;
		}

		// A segment holding none of the levels asked about is nothing to walk
		if(slots == 0) {
			return null;
		}

		var column = FacetStates.ordsOf(context, field);
		return switch(mode) {
			case DOCUMENTS, VALUES -> new EachMatch(column, hierarchy, slotOfOrd, slots);
			case ROLLED_UP -> new RolledUp(column, hierarchy, slotOfOrd, slots);
			case PARENTS_BY_VALUE -> new ByDocument(column, hierarchy, slotOfOrd, slots);
		};
	}

	@Override
	public SearchResult.Facet result() {
		var nodes = Maps.mutable.<String, Node>empty();
		counts.forEachKeyValue(
			(value, count) -> nodes.put(value, new Node(value, scope.levelOf(value), count))
		);

		/*
		 * A level is hung off the one above it, which is counted whenever the
		 * level itself is - a value passes through every level on its way down.
		 * The ones whose parent is the path being counted from have none here,
		 * and are what the facet answers.
		 */
		var roots = Lists.mutable.<Node>empty();
		for(var node : nodes) {
			var parent = scope.parentOf(node.path);
			var above = parent == null ? null : nodes.get(parent);

			if(above == null) {
				roots.add(node);
			} else {
				above.children.add(node);
			}
		}

		Comparator<Node> byValue = Comparator.comparing((Node node) -> node.value)
			.thenComparing(node -> node.path);
		Comparator<Node> byCount = Comparator.<Node>comparingLong(node -> node.count)
			.reversed()
			.thenComparing(byValue);

		return new SearchResult.Facet(
			shape(roots, limit, order == Facet.Order.VALUE ? byValue : byCount),
			roots.size()
		);
	}

	/**
	 * Counts per slot of one segment, folded into the whole by path when the
	 * segment is done - ordinals are per segment, so what carries across is
	 * the path.
	 */
	private abstract class PerSlot implements Leaf {
		final FacetColumns.OrdSpans spans;
		final FacetStates.Hierarchy hierarchy;
		final int[] slotOfOrd;
		final long[] perSlot;

		PerSlot(
			FacetColumns.Ords column,
			FacetStates.Hierarchy hierarchy,
			int[] slotOfOrd,
			int slots
		) {
			this.spans = new FacetColumns.OrdSpans(column);
			this.hierarchy = hierarchy;
			this.slotOfOrd = slotOfOrd;
			this.perSlot = new long[slots];
		}

		@Override
		public void finish() {
			for(var ord = 0; ord < slotOfOrd.length; ord++) {
				var slot = slotOfOrd[ord];
				if(slot >= 0 && perSlot[slot] > 0) {
					counts.addToValue(hierarchy.paths()[ord], perSlot[slot]);
				}
			}
		}
	}

	/**
	 * Each match counts its own levels, one count per level it passes
	 * through.
	 */
	private final class EachMatch extends PerSlot {
		EachMatch(
			FacetColumns.Ords column,
			FacetStates.Hierarchy hierarchy,
			int[] slotOfOrd,
			int slots
		) {
			super(column, hierarchy, slotOfOrd, slots);
		}

		@Override
		public void count(int doc) {
			for(int i = spans.from(doc), end = spans.to(doc); i < end; i++) {
				var slot = slotOfOrd[spans.values[i]];
				if(slot >= 0) {
					perSlot[slot]++;
				}
			}
		}
	}

	/**
	 * The matches are values of an object field and the counts are of the
	 * documents holding them: a level counts once per document however many of
	 * its values pass through it.
	 */
	private final class RolledUp extends PerSlot {
		private final MutableLongSet seen = LongSets.mutable.empty();

		RolledUp(
			FacetColumns.Ords column,
			FacetStates.Hierarchy hierarchy,
			int[] slotOfOrd,
			int slots
		) {
			super(column, hierarchy, slotOfOrd, slots);
		}

		@Override
		public void beginDocument(int document) {
			seen.clear();
		}

		@Override
		public void count(int doc) {
			for(int i = spans.from(doc), end = spans.to(doc); i < end; i++) {
				var ord = spans.values[i];
				var slot = slotOfOrd[ord];
				if(slot >= 0 && seen.add(ord)) {
					perSlot[slot]++;
				}
			}
		}
	}

	/**
	 * The matches are values of an object field but the levels live on the
	 * documents holding them: each match counts the levels its document is
	 * filed under.
	 */
	private final class ByDocument extends PerSlot {
		private final MutableIntList documentSlots = IntLists.mutable.empty();

		ByDocument(
			FacetColumns.Ords column,
			FacetStates.Hierarchy hierarchy,
			int[] slotOfOrd,
			int slots
		) {
			super(column, hierarchy, slotOfOrd, slots);
		}

		@Override
		public void beginDocument(int document) {
			documentSlots.clear();

			for(int i = spans.from(document), end = spans.to(document); i < end; i++) {
				var slot = slotOfOrd[spans.values[i]];
				if(slot >= 0) {
					documentSlots.add(slot);
				}
			}
		}

		@Override
		public void count(int doc) {
			for(var i = 0; i < documentSlots.size(); i++) {
				perSlot[documentSlots.get(i)]++;
			}
		}
	}

	/**
	 * Order the levels of one parent, cut them to the limit and shape them
	 * along with everything below them. The limit holds per level, as each one
	 * is a list somebody picks from.
	 */
	private static ImmutableList<SearchResult.Facet.Value> shape(
		MutableList<Node> nodes,
		int limit,
		Comparator<Node> comparator
	) {
		nodes.sortThis(comparator);

		var values = Lists.mutable.<SearchResult.Facet.Value>empty();
		for(var node : nodes) {
			if(values.size() == limit) {
				break;
			}

			values.add(new SearchResult.Facet.Value(
				node.value,
				node.count,
				node.path,
				shape(node.children, limit, comparator),
				node.children.size()
			));
		}

		return values.toImmutable();
	}

	/**
	 * One level of the tree with what has been counted for it so far.
	 */
	private static final class Node {
		private final String path;
		private final String value;
		private final long count;
		private final MutableList<Node> children = Lists.mutable.empty();

		Node(String path, String value, long count) {
			this.path = path;
			this.value = value;
			this.count = count;
		}
	}

	/**
	 * Which levels of the tree one facet asked about, and how a path is taken
	 * apart to tell.
	 *
	 * Whether a path sits under the level being counted from is judged on the
	 * normalized path, the same reading that decides which documents narrowing
	 * to a subtree finds, so a path answered by one is a path the other takes.
	 * How deep it reaches is read off its separators, which normalizing never
	 * touches. What is counted and answered is the level as it was given,
	 * which is what a reader recognises.
	 */
	private static final class Scope {
		private final String separator;
		private final String prefix;
		private final int base;
		private final int depth;

		Scope(String separator, UnaryOperator<String> normalize, String path, int depth) {
			this.separator = separator;
			this.depth = depth;

			if(path == null) {
				this.prefix = null;
				this.base = 0;
			} else {
				var normalized = normalize.apply(path);
				this.prefix = normalized + separator;
				this.base = FacetStates.Hierarchy.levelsOf(normalized, separator);
			}
		}

		/**
		 * Get whether the facet asked about the path behind the given ordinal.
		 */
		boolean holds(FacetStates.Hierarchy hierarchy, int ord) {
			if(prefix != null && !hierarchy.normalized()[ord].startsWith(prefix)) {
				return false;
			}

			var below = hierarchy.levels()[ord] - base;
			return below >= 1 && below <= depth;
		}

		/**
		 * Get the level itself, which is the part of the path below the one
		 * above it.
		 */
		String levelOf(String value) {
			var last = value.lastIndexOf(separator);
			return last < 0 ? value : value.substring(last + separator.length());
		}

		/**
		 * Get the path one level above the given one, or {@code null} where
		 * there is none left within the scope.
		 */
		String parentOf(String value) {
			var last = value.lastIndexOf(separator);
			return last < 0 ? null : value.substring(0, last);
		}
	}
}
