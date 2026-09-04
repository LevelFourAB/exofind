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
 * @param interpreted
 *   what the search read out of the text as filters, or {@code null} when it
 *   read nothing. See {@link Interpreted}
 * @param windowEnd
 *   where the window a {@link Rescore} reordered ended, in the order the
 *   ranking put the results in - or {@code null} when the search rescored
 *   nothing, or ran out of results inside the window. The hits of a rescored
 *   window sit in an order no key names, so this is the position a caller
 *   continues from to reach the results below it
 */
public record SearchResult(
	ImmutableList<Hit> hits,
	Total total,
	Total documents,
	ImmutableMap<String, Facet> facets,
	Relaxed relaxed,
	Interpreted interpreted,
	SortKey windowEnd
) {
	public SearchResult {
		if(facets == null) {
			facets = Maps.immutable.empty();
		}
	}

	public SearchResult(
		ImmutableList<Hit> hits,
		Total total,
		Total documents,
		ImmutableMap<String, Facet> facets,
		Relaxed relaxed,
		Interpreted interpreted
	) {
		this(hits, total, documents, facets, relaxed, interpreted, null);
	}

	public SearchResult(
		ImmutableList<Hit> hits,
		Total total,
		Total documents,
		ImmutableMap<String, Facet> facets,
		Relaxed relaxed
	) {
		this(hits, total, documents, facets, relaxed, null, null);
	}

	public SearchResult(
		ImmutableList<Hit> hits,
		Total total,
		ImmutableMap<String, Facet> facets,
		Relaxed relaxed
	) {
		this(hits, total, null, facets, relaxed, null, null);
	}

	public SearchResult(
		ImmutableList<Hit> hits,
		Total total,
		ImmutableMap<String, Facet> facets
	) {
		this(hits, total, null, facets, null, null, null);
	}

	public SearchResult(ImmutableList<Hit> hits, Total total) {
		this(hits, total, null, null, null, null, null);
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
	 *   {@code id} together with {@code valueKey} where the field declares a
	 *   key, and with {@code index} where it does not
	 * @param index
	 *   for a hit standing for a value: the position of the value in the
	 *   field's value array as the document gave it, counted from zero.
	 *   {@code null} for a hit standing for a document
	 * @param valueKey
	 *   for a hit standing for a value of a field that declares a key: what
	 *   that value reads for it. Unlike {@code index} this names the same
	 *   value after a reindex, positions being free to move. {@code null} for
	 *   a hit standing for a document, for a field declaring no key, and on
	 *   an index that keeps no copy of its documents when the key's field is
	 *   not stored - stored, it answers from the value's own document
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
	 *   On an index that keeps no copy of its documents it holds what the
	 *   value's stored fields answer. {@code null} for a hit standing for a
	 *   document, and where neither a copy nor a stored field could answer
	 * @param key
	 *   where the hit sits in the order it came back in, for continuing from
	 *   it with {@link SearchRequest.Builder#withAfter(SortKey)} or
	 *   {@link SearchRequest.Builder#withBefore(SortKey)}
	 * @param highlights
	 *   the highlighted fragments of the fields the search asked to
	 *   highlight, keyed by field name. For a hit standing for a value the
	 *   fields sit inside the value, and the fragments are cut from that
	 *   value alone. A field the hit holds no match in has no entry, and a
	 *   search that asked for no highlighting leaves the map empty
	 * @param matched
	 *   which values of each object field the search asked about matched,
	 *   keyed by field name. One entry per field asked about, and a search
	 *   that asked about none leaves the map empty
	 */
	public record Hit(
		Object id,
		Integer index,
		String valueKey,
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
			this(id, null, null, score, document, null, key, highlights, matched);
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
	 *   the field rank and in the order the document gave them otherwise. On
	 *   an index that keeps no copy of its documents each value holds what its
	 *   stored fields answer; {@code null} where neither a copy nor a stored
	 *   field could answer
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
	 * What a search read out of the typed text as filters.
	 *
	 * Present only on a search whose text held something the index declared
	 * could be read - a number next to the unit of a number field, or next to
	 * a comparative word - so the results answer a filter as well as the
	 * words. Saying so is what lets a search box show the reading as a chip a
	 * person can take away, so that a wrong reading is never silent.
	 *
	 * The words a reading came from are still searched as text besides, so
	 * the results hold what the filter finds and what the words find as text,
	 * ranked with the filter first.
	 *
	 * @param filters
	 *   the filters that were read, in the order their words were typed. Two
	 *   fields declaring the same unit read the same words as two filters,
	 *   either of which a document may satisfy
	 * @param text
	 *   the text that was left once the words of the readings were taken out
	 *   of it, as it reads back to the same search. Empty when everything
	 *   typed was read
	 */
	public record Interpreted(
		ImmutableList<Filter> filters,
		String text
	) {
		/**
		 * What kind of thing a filter was read from.
		 */
		public enum Kind {
			/**
			 * A number next to a unit or a comparative word, read as a bound
			 * on a number field.
			 */
			NUMBER,

			/**
			 * Words that are a value some field holds, read as that value of
			 * the field.
			 */
			VALUE
		}

		/**
		 * One filter a search read out of the text.
		 *
		 * @param kind
		 *   what the filter was read from
		 * @param field
		 *   the field the filter is on, named as the definition names it
		 * @param matcher
		 *   what the values of the field have to satisfy - a
		 *   {@link se.l4.exofind.engine.query.matchers.RangeMatcher range} for
		 *   a bound, an
		 *   {@link se.l4.exofind.engine.query.matchers.EqualsMatcher equals}
		 *   for a number written with its unit and nothing else, or for a
		 *   value of the field
		 * @param words
		 *   the words the filter was read from, as they were typed and in the
		 *   order they were typed
		 * @param when
		 *   the clauses that have to hold where the filter is read, as the
		 *   {@link TextQuery.Target target} the search named said. Empty when
		 *   the filter is read wherever the field holds a value
		 * @param fallback
		 *   the targets read instead where a document holds no value on the
		 *   field, in the order they are tried. Empty when there are none
		 */
		public record Filter(
			Kind kind,
			String field,
			se.l4.exofind.engine.query.matchers.Matcher matcher,
			ImmutableList<String> words,
			ImmutableList<Query> when,
			ImmutableList<TextQuery.Target> fallback
		) {
			public Filter {
				if(kind == null) {
					throw new IllegalArgumentException("A filter needs a kind");
				}

				if(when == null) {
					when = Lists.immutable.empty();
				}

				if(fallback == null) {
					fallback = Lists.immutable.empty();
				}
			}

			public Filter(
				Kind kind,
				String field,
				se.l4.exofind.engine.query.matchers.Matcher matcher,
				ImmutableList<String> words
			) {
				this(kind, field, matcher, words, null, null);
			}
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
		 * @param label
		 *   what a person reads instead of the value, in the locale of the
		 *   search - or {@code null} when the search settings of the index
		 *   declare none for it. See {@code DeclaredValues}
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
			String label,
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
				this(value, count, null, null, null, 0);
			}

			/**
			 * A value standing on its own, with the label the search settings
			 * declare for it.
			 *
			 * @param value
			 * @param count
			 * @param label
			 */
			public Value(Object value, long count, String label) {
				this(value, count, label, null, null, 0);
			}

			/**
			 * One level of a tree, with the levels below it.
			 *
			 * @param value
			 * @param count
			 * @param path
			 * @param children
			 * @param totalChildren
			 */
			public Value(
				Object value,
				long count,
				String path,
				ImmutableList<Value> children,
				int totalChildren
			) {
				this(value, count, null, path, children, totalChildren);
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
