package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.Comparator;
import java.util.function.UnaryOperator;

import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.BitSet;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.factory.primitive.IntLists;
import org.eclipse.collections.api.factory.primitive.LongSets;
import org.eclipse.collections.api.factory.primitive.ObjectLongMaps;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.MutableList;

import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.SearchResult;

/**
 * Counting a facet over a field whose values are paths through a tree, one
 * level at a time.
 *
 * A value was written once per level it passes through, so the levels of the
 * tree stand in the doc values as values of their own and counting a level is
 * counting the documents that hold it - a product filed under
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
 * Rolling up works the way {@link NestedFacets} describes: a level is counted
 * the first time one of a document's values passes through it and never again,
 * which for a field inside an object means the values of one document are
 * visited as one block.
 */
final class HierarchyFacets {
	private HierarchyFacets() {
	}

	/**
	 * Count the matches per level below a path.
	 *
	 * @param matches
	 *   what to count, and what rolls values of an object field up
	 * @param field
	 *   the Lucene field the levels were written under
	 * @param separator
	 *   what separates one level of a path from the next
	 * @param normalize
	 *   how a path is read before two of them are called the same
	 * @param path
	 *   the level to count the children of, or {@code null} for the top
	 * @param depth
	 *   how many levels below {@code path} to count
	 * @param limit
	 *   how many levels to bring back at most, per level counted
	 * @param order
	 *   the order the levels of one parent come back in
	 * @return
	 * @throws IOException
	 */
	static SearchResult.Facet count(
		FacetMatches matches,
		String field,
		String separator,
		UnaryOperator<String> normalize,
		String path,
		int depth,
		int limit,
		Facet.Order order
	) throws IOException {
		var scope = new Scope(separator, normalize, path, depth);
		var counts = ObjectLongMaps.mutable.<String>empty();

		for(var docs : matches.hits().getMatchingDocs()) {
			var context = docs.context();
			var values = context.reader().getSortedSetDocValues(field);
			var iterator = docs.bits() == null ? null : docs.bits().iterator();
			if(values == null || iterator == null) {
				continue;
			}

			BitSet parents = null;
			if(matches.parents() != null) {
				parents = matches.parents().getBitSet(context);
				if(parents == null) {
					continue;
				}
			}

			/*
			 * The slot each ordinal counts into when it is one the facet asked
			 * about, and -1 when it is not. Decided once here over the values
			 * of the segment - decoded and parsed once per segment, not per
			 * search - so the walk below neither looks a term up nor measures
			 * a path per document, and counts into an array no larger than
			 * what was asked about rather than one per value the field holds.
			 */
			var hierarchy = FacetStates.hierarchyOf(context, field, values, separator, normalize);
			var slotOfOrd = new int[hierarchy.paths().length];
			var slots = 0;
			for(var ord = 0; ord < slotOfOrd.length; ord++) {
				slotOfOrd[ord] = scope.holds(hierarchy, ord) ? slots++ : -1;
			}

			// A segment holding none of the levels asked about is nothing to walk
			if(slots == 0) {
				continue;
			}

			/*
			 * Counted per slot rather than per path, as a slot is an index
			 * into an array where a path is a key to be hashed - the walk
			 * below meets one per value of every document, the roll-up below
			 * it one per level of the tree.
			 */
			var perSlot = new long[slots];

			if(matches.mode() == FacetMatches.Mode.PARENTS_BY_VALUE) {
				/*
				 * The matches are values of an object field but the levels
				 * live on the documents holding them, so each match counts
				 * what its document says. A document's matches arrive
				 * together, so its levels are read once and reused for the
				 * rest of them - which also keeps the forward-only doc values
				 * moving forward.
				 */
				var document = -1;
				var documentSlots = IntLists.mutable.empty();

				for(
					var doc = iterator.nextDoc();
					doc != DocIdSetIterator.NO_MORE_DOCS;
					doc = iterator.nextDoc()
				) {
					if(doc > document) {
						document = documentOf(parents, doc);
						if(document == DocIdSetIterator.NO_MORE_DOCS) {
							break;
						}

						documentSlots.clear();
						if(values.advanceExact(document)) {
							for(var i = 0; i < values.docValueCount(); i++) {
								var slot = slotOfOrd[(int) values.nextOrd()];
								if(slot >= 0) {
									documentSlots.add(slot);
								}
							}
						}
					}

					for(var i = 0; i < documentSlots.size(); i++) {
						perSlot[documentSlots.get(i)]++;
					}
				}
			} else {
				var document = -1;
				var seen = LongSets.mutable.empty();

				for(
					var doc = iterator.nextDoc();
					doc != DocIdSetIterator.NO_MORE_DOCS;
					doc = iterator.nextDoc()
				) {
					if(parents != null && doc > document) {
						document = documentOf(parents, doc);
						seen.clear();
					}

					if(!values.advanceExact(doc)) {
						continue;
					}

					for(var i = 0; i < values.docValueCount(); i++) {
						var ord = values.nextOrd();
						var slot = slotOfOrd[(int) ord];
						if(slot < 0) {
							continue;
						}

						/*
						 * The levels of one Lucene document are a set already,
						 * so only values of an object field can meet the same
						 * level twice - once per value of the document holding
						 * it. Rolling up is only wanted when the counts are of
						 * documents; counted as values, every match counts.
						 */
						if(parents == null || seen.add(ord)) {
							perSlot[slot]++;
						}
					}
				}
			}

			/*
			 * Ordinals are per segment, so what carries across is the path -
			 * added here rather than in the walk, once per level the segment
			 * held.
			 */
			for(var ord = 0; ord < slotOfOrd.length; ord++) {
				var slot = slotOfOrd[ord];
				if(slot >= 0 && perSlot[slot] > 0) {
					counts.addToValue(hierarchy.paths()[ord], perSlot[slot]);
				}
			}
		}

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
	 * Get the document a value belongs to, which is the first document at or
	 * after it.
	 */
	private static int documentOf(BitSet parents, int doc) {
		return doc < parents.length()
			? parents.nextSetBit(doc)
			: DocIdSetIterator.NO_MORE_DOCS;
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
