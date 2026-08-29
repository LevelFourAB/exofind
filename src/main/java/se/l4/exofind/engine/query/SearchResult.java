package se.l4.exofind.engine.query;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.map.ImmutableMap;

import se.l4.exofind.engine.index.Document;

/**
 * What a search found.
 *
 * @param hits
 *   the results asked for, in the order they were asked for
 * @param total
 *   how many hits there are in total, which is more than the number of hits
 *   returned whenever a limit was reached. Counted in whatever the search
 *   answers with, so a document that answered with several of its values
 *   counts once per value
 * @param documents
 *   how many documents matched, which is the number the facets are counted
 *   in, or {@code null} when that is the same number as {@code total}. Only a
 *   search that expands some of its documents and not others answers both, and
 *   only there does the difference between "how many results" and "how many
 *   products" need saying. A document told to expand that holds no matching
 *   value counts here while answering with no hit at all
 * @param facets
 *   the counts per value the search asked for, keyed by the name of each
 *   facet. Empty when the search asked for none
 * @param relaxed
 *   what the search let go of to find anything, or {@code null} when it found
 *   what was asked for. See {@link Relaxed}
 */
public record SearchResult(
	ImmutableList<Hit> hits,
	Total total,
	Total documents,
	ImmutableMap<String, Facet> facets,
	Relaxed relaxed
) {
	public SearchResult {
		if(facets == null) {
			facets = Maps.immutable.empty();
		}
	}

	public SearchResult(
		ImmutableList<Hit> hits,
		Total total,
		ImmutableMap<String, Facet> facets,
		Relaxed relaxed
	) {
		this(hits, total, null, facets, relaxed);
	}

	public SearchResult(
		ImmutableList<Hit> hits,
		Total total,
		ImmutableMap<String, Facet> facets
	) {
		this(hits, total, null, facets, null);
	}

	public SearchResult(ImmutableList<Hit> hits, Total total) {
		this(hits, total, null, null, null);
	}

	/**
	 * A single result. What one stands for is the search's to say: a document
	 * that matched, or - for a search whose {@link SearchRequest#hits()} names
	 * an object field - one matched value of that field.
	 *
	 * @param id
	 *   the primary key of the document, or {@code null} for an index that has
	 *   no primary key. A hit standing for a value carries the key of the
	 *   document holding it, so several hits share an {@code id} whenever
	 *   several values of one document matched - the identity of such a hit is
	 *   {@code id} together with {@code index}
	 * @param index
	 *   for a hit standing for a value: the position of the value in the
	 *   field's value array as the document gave it, counted from zero.
	 *   {@code null} for a hit standing for a document
	 * @param score
	 *   how well the hit matched. Only means something when the search held a
	 *   clause that scores, see {@link Query#scores()}. A hit standing for a
	 *   value scores what its document scored plus what the value itself
	 *   scored under the {@code nested} clauses of its path - and what its
	 *   document scored alone where only the documents the search's
	 *   {@link SearchRequest.Hits#when()} names answer with their values, so
	 *   that both kinds of hit on the page are ranked by the same number
	 * @param document
	 *   the fields that were asked for, as they were given - for a hit
	 *   standing for a value, the fields of the document holding it
	 * @param value
	 *   for a hit standing for a value: the value itself, as it was given.
	 *   {@code null} for a hit standing for a document, and for an index that
	 *   keeps no copy of its documents, which has no value to hand back
	 * @param key
	 *   where the hit sits in the order it came back in, for continuing from
	 *   it with {@link SearchRequest.Builder#withAfter(SortKey)} or
	 *   {@link SearchRequest.Builder#withBefore(SortKey)}
	 * @param highlights
	 *   the highlighted fragments of the fields the search asked to
	 *   highlight, keyed by field name. A field the document holds no match
	 *   in has no entry, and a search that asked for no highlighting leaves
	 *   the map empty
	 * @param matched
	 *   which values of each object field the search asked about matched,
	 *   keyed by field name. One entry per field asked about, and a search
	 *   that asked about none leaves the map empty
	 */
	public record Hit(
		Object id,
		Integer index,
		float score,
		Document document,
		Document value,
		SortKey key,
		ImmutableMap<String, ImmutableList<String>> highlights,
		ImmutableMap<String, Matched> matched
	) {
		public Hit {
			if(highlights == null) {
				highlights = Maps.immutable.empty();
			}

			if(matched == null) {
				matched = Maps.immutable.empty();
			}
		}

		/**
		 * A hit standing for a document.
		 */
		public Hit(
			Object id,
			float score,
			Document document,
			SortKey key,
			ImmutableMap<String, ImmutableList<String>> highlights,
			ImmutableMap<String, Matched> matched
		) {
			this(id, null, score, document, null, key, highlights, matched);
		}
	}

	/**
	 * Which values of one object field matched for one hit.
	 *
	 * The values are the ones the {@code nested} clauses every result had to
	 * satisfy asked for - the same values a sort or a facet on the field
	 * reads. A search that asked nothing of the values matched all of them.
	 *
	 * @param values
	 *   the values that matched, as they were given - at most as many as the
	 *   search asked for, ordered by how well each matched when the clauses on
	 *   the field rank and in the order the document gave them otherwise.
	 *   {@code null} for an index that keeps no copy of its documents, which
	 *   has no values to hand back
	 * @param totalValues
	 *   how many values matched in all, which is more than the number of
	 *   values whenever the limit was reached
	 */
	public record Matched(
		ImmutableList<Document> values,
		int totalValues
	) {
	}

	/**
	 * What a search let go of to find anything.
	 *
	 * Present only on a search that would otherwise have come back empty and
	 * found something once a word was dropped, so it always means the results
	 * answer less than what was typed. Saying so is the whole point of it: a
	 * page that quietly ignored half of a search is worse than an empty one,
	 * because the person reading it believes it.
	 *
	 * @param dropped
	 *   the words that were let go, in the order they were typed
	 * @param text
	 *   the text the search ran with in the end, for showing what the results
	 *   actually answer
	 */
	public record Relaxed(
		ImmutableList<Dropped> dropped,
		String text
	) {
		/**
		 * One word a search let go of, and why it went.
		 *
		 * @param word
		 *   the word as it was typed
		 * @param reason
		 *   what made it the one to go
		 */
		public record Dropped(
			String word,
			Reason reason
		) {
		}

		/**
		 * Why a word was let go.
		 */
		public enum Reason {
			/**
			 * Nothing in the index holds the word, so keeping it could only ever
			 * have found nothing. Dropping it lost no result - and is worth
			 * saying out loud, as it means the index has nothing of the kind
			 * rather than nothing matching the rest.
			 */
			UNMATCHED,

			/**
			 * The word is one the most documents hold, so it said the least
			 * about what was wanted. Dropping it widens the search to text that
			 * was never typed, which the results have to be read in the light
			 * of.
			 */
			COMMON
		}
	}

	/**
	 * How many matched, in whichever unit the count is of.
	 *
	 * Counting every match costs more the more there are, so a search that
	 * only needs a page of results is allowed to stop counting once it knows
	 * there are more than it could show. The count is then a lower bound, and
	 * saying so is what lets a caller show `1000+` rather than a number that
	 * is quietly wrong.
	 *
	 * @param count
	 *   the number that matched
	 * @param exact
	 *   if the count is the whole number rather than at least that many
	 */
	public record Total(
		long count,
		boolean exact
	) {
	}

	/**
	 * How many matches held each value of one faceted field.
	 *
	 * The counts are sideways of the filters on the facet's own field: what
	 * ticking a value would leave, with the other values still visible. Every
	 * other filter and the whole query narrow the counts the way they narrow
	 * the hits.
	 *
	 * The shape follows how the facet asked: counting per value fills
	 * {@code values}, counting into {@link se.l4.exofind.engine.query.Facet#ranges()
	 * ranges} fills {@code buckets} and leaves the rest empty.
	 *
	 * @param values
	 *   the values with their counts, in the order the facet asked for and at
	 *   most as many as its limit
	 * @param totalValues
	 *   how many distinct values the matches held in all, which is more than
	 *   the number of values whenever the limit was reached
	 * @param buckets
	 *   the buckets with their counts, one per range the facet asked for and
	 *   in the same order
	 */
	public record Facet(
		ImmutableList<Value> values,
		int totalValues,
		ImmutableList<Bucket> buckets
	) {
		public Facet {
			if(values == null) {
				values = Lists.immutable.empty();
			}

			if(buckets == null) {
				buckets = Lists.immutable.empty();
			}
		}

		public Facet(ImmutableList<Value> values, int totalValues) {
			this(values, totalValues, null);
		}

		/**
		 * Counts per bucket, for a facet that asked for ranges.
		 *
		 * @param buckets
		 * @return
		 */
		public static Facet ofBuckets(ImmutableList<Bucket> buckets) {
			return new Facet(null, 0, buckets);
		}

		/**
		 * One value of a faceted field with how many matches held it.
		 *
		 * A field whose values are paths through a tree answers one of these
		 * per level: {@code value} is the level itself, so it reads as a
		 * label, and {@code path} is the whole way down to it, which is what
		 * filtering on it takes. Every other field leaves {@code path} and
		 * {@code children} empty, as its values stand on their own.
		 *
		 * @param value
		 *   the value, in the shape the type of the field returns it in - one
		 *   level of the path for a field whose values are paths
		 * @param count
		 *   how many matches held the value
		 * @param path
		 *   the whole path down to this level, or {@code null} for a value
		 *   that is not part of a tree
		 * @param children
		 *   the levels below this one with their own counts, as far down as
		 *   the facet asked to count. Empty at the deepest level counted, and
		 *   for a value that is not part of a tree
		 * @param totalChildren
		 *   how many levels below this one the matches held in all, which is
		 *   more than the number of children whenever the limit was reached
		 */
		public record Value(
			Object value,
			long count,
			String path,
			ImmutableList<Value> children,
			int totalChildren
		) {
			public Value {
				if(children == null) {
					children = Lists.immutable.empty();
				}
			}

			/**
			 * A value standing on its own, which is what every field but one
			 * whose values are paths counts.
			 *
			 * @param value
			 * @param count
			 */
			public Value(Object value, long count) {
				this(value, count, null, null, 0);
			}
		}

		/**
		 * One bucket of a faceted field with how many matches fell in it.
		 *
		 * @param from
		 *   the lower bound of the bucket as the facet gave it, {@code null}
		 *   for an open one
		 * @param to
		 *   the upper bound of the bucket as the facet gave it, {@code null}
		 *   for an open one
		 * @param count
		 *   how many matches held a value in the bucket
		 */
		public record Bucket(
			Object from,
			Object to,
			long count
		) {
		}
	}
}
