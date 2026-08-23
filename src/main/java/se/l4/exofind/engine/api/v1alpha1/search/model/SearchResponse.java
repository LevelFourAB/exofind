package se.l4.exofind.engine.api.v1alpha1.search.model;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What a search found, as it is answered over the API.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SearchResponse(
	/**
	 * The results asked for, in the order they were asked for.
	 */
	List<Hit> hits,

	/**
	 * How many documents matched in total.
	 */
	Total total,

	/**
	 * The counts per value the request asked for, keyed by the name of each
	 * facet. Present whenever facets were asked for, and left out entirely
	 * when they were not.
	 */
	Map<String, Facet> facets,

	/**
	 * Where in the results this window sits and how to move from it.
	 */
	Page page,

	/**
	 * What the search let go of to find anything, left out entirely when it
	 * found what was asked for.
	 */
	Relaxed relaxed,

	/**
	 * How long answering took, measured around the whole call, in
	 * milliseconds and fractions of one - a search that answers faster than a
	 * millisecond still reports what it spent.
	 */
	double tookMs
) {
	/**
	 * A single document that matched.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Hit(
		/**
		 * The primary key of the document, left out for an index that has no
		 * primary key.
		 */
		Object id,

		/**
		 * How well the document matched, left out when the search computed no
		 * scores rather than defaulted to something that looks like a value.
		 */
		Float score,

		/**
		 * The fields that were asked for, as they were given. A field with
		 * several values is an array, and a locale specific field is an
		 * object keyed by locale tag.
		 */
		Map<String, Object> document,

		/**
		 * The highlighted fragments of the fields the search asked to
		 * highlight, keyed by field name. Present whenever highlighting was
		 * asked for, with fields the document holds no match in left out -
		 * and left out entirely when it was not.
		 */
		Map<String, List<String>> highlights
	) {
	}

	/**
	 * How many matches held each value of one faceted field, for building the
	 * list of filters a user picks from.
	 *
	 * The counts are sideways of the filters on the facet's own field: what
	 * ticking a value would leave, with the other values still visible. Every
	 * other filter and the whole query narrow the counts the way they narrow
	 * the hits.
	 *
	 * The shape follows how the facet asked: counting per value answers
	 * {@code values} with {@code totalValues}, counting into ranges answers
	 * {@code buckets}, with the other shape left out.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Facet(
		/**
		 * The values with their counts, in the order the facet asked for and
		 * at most as many as its limit.
		 */
		List<FacetValue> values,

		/**
		 * How many distinct values the matches held in all, which is more
		 * than the number of values whenever the limit was reached.
		 */
		Integer totalValues,

		/**
		 * The buckets with their counts, one per range the facet asked for
		 * and in the same order.
		 */
		List<FacetBucket> buckets
	) {
	}

	/**
	 * One value of a faceted field with how many matches held it.
	 *
	 * A field whose values are paths through a tree answers one of these per
	 * level, and the levels below it under `values`. Every other field
	 * leaves the shape of a tree out entirely.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record FacetValue(
		/**
		 * The value, in the shape the field returns it in results - a string,
		 * boolean or number, and an ISO 8601 instant for a timestamp field.
		 * One level of the path for a field whose values are paths, so it
		 * reads as a label.
		 */
		Object value,

		/**
		 * How many matches held the value.
		 */
		long count,

		/**
		 * The whole path down to this level, which is what a filter on the
		 * field takes. Left out for a value that is not part of a tree.
		 */
		String path,

		/**
		 * The levels below this one with their own counts, as far down as the
		 * facet asked to count. Left out at the deepest level counted, and
		 * for a value that is not part of a tree.
		 */
		List<FacetValue> values,

		/**
		 * How many levels below this one the matches held in all, which is
		 * more than the number under `values` whenever the limit was reached.
		 * Left out for a value that is not part of a tree.
		 */
		Integer totalValues
	) {
		/**
		 * A value standing on its own, which is what every field but one
		 * holding paths counts.
		 */
		public FacetValue(Object value, long count) {
			this(value, count, null, null, null);
		}
	}

	/**
	 * One bucket of a faceted field with how many matches fell in it.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record FacetBucket(
		/**
		 * The lower bound of the bucket as the request gave it, itself
		 * included. Left out for an open one.
		 */
		Object from,

		/**
		 * The upper bound of the bucket as the request gave it, itself not
		 * included. Left out for an open one.
		 */
		Object to,

		/**
		 * How many matches held a value in the bucket.
		 */
		long count
	) {
	}

	/**
	 * What a search let go of to find anything.
	 *
	 * Present only when the search would otherwise have come back empty, so it
	 * always means the results answer less than what was typed - which is the
	 * whole reason it is here. A page that quietly ignored half of a search is
	 * worse than an empty one, because the person reading it believes it.
	 */
	public record Relaxed(
		/**
		 * The words that were let go, in the order they were typed.
		 */
		List<Dropped> dropped,

		/**
		 * The text the search ran with in the end, for showing what the
		 * results actually answer.
		 */
		String text
	) {
		/**
		 * One word a search let go of, and why it went.
		 */
		public record Dropped(
			/**
			 * The word as it was typed.
			 */
			String word,

			/**
			 * What made it the one to go.
			 */
			Reason reason
		) {
			/**
			 * Why a word was let go.
			 */
			public enum Reason {
				/**
				 * Nothing in the index holds the word, so keeping it could
				 * only ever have found nothing - the index holds nothing of
				 * the kind rather than nothing matching the rest.
				 */
				@JsonProperty("unmatched")
				UNMATCHED,

				/**
				 * The word is one the most documents hold, so it said the
				 * least about what was wanted.
				 */
				@JsonProperty("common")
				COMMON
			}
		}
	}

	/**
	 * How many documents matched.
	 */
	public record Total(
		/**
		 * The number of documents that matched.
		 */
		long count,

		/**
		 * If the count is the whole number rather than at least that many.
		 */
		boolean exact
	) {
	}

	/**
	 * Where in the results this window sits.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Page(
		/**
		 * How many results the window holds at most.
		 */
		int limit,

		/**
		 * How many results come before the window, left out when the window
		 * was reached through {@code next} or {@code previous} - following
		 * a hit does not count what it skips, which is what lets those go
		 * deeper than an offset may.
		 */
		Integer offset,

		/**
		 * Cursor for the preceding window, left out when this is the first.
		 */
		String previous,

		/**
		 * Cursor for the next window, left out when there is nothing after
		 * this one.
		 */
		String next,

		/**
		 * The numbered pages, present when the request asked for them.
		 */
		Pages pages
	) {
	}

	/**
	 * Numbered pages, shaped so a client renders {@code 1 2 3 … 7} with the
	 * ellipses exactly where a window boundary falls - between {@code start}
	 * and {@code middle}, and between {@code middle} and {@code end},
	 * whenever the numbers are not adjacent.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Pages(
		/**
		 * How many pages there are in total.
		 */
		long count,

		/**
		 * The page before the current one, left out on the first.
		 */
		PageRef previous,

		/**
		 * The page after the current one, left out on the last - or when it
		 * would be deeper than paging goes.
		 */
		PageRef next,

		/**
		 * The pages at the start of the list.
		 */
		List<PageRef> start,

		/**
		 * The pages around the current one, when they do not touch either
		 * end.
		 */
		List<PageRef> middle,

		/**
		 * The pages at the end of the list, left out when the last page is
		 * deeper than paging goes - so a pager never offers a jump that
		 * would be refused.
		 */
		List<PageRef> end
	) {
	}

	/**
	 * One numbered page.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record PageRef(
		/**
		 * The number of the page, counted from one.
		 */
		long number,

		/**
		 * Cursor that fetches the page.
		 */
		String cursor,

		/**
		 * Set on the page the response is showing.
		 */
		Boolean current
	) {
	}
}
