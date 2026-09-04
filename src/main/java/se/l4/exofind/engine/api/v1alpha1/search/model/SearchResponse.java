package se.l4.exofind.engine.api.v1alpha1.search.model;

import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import se.l4.exofind.engine.index.Document;

/**
 * Response returned by a search query.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
	description = "The results of a search. See [Response](https://exofind.dev/reference/search-api/#response).",
	examples = SearchResponse.EXAMPLE
)
public record SearchResponse(
	/**
	 * List of matching hits in the requested sort order.
	 */
	@Schema(description = "The matching hits, in the order that `sort` asked for.")
	List<Hit> hits,

	/**
	 * Total number of matching hits, counted in the returned hit units: a
	 * document expanded into nested values counts once per value.
	 */
	@Schema(description = """
		How many hits matched in total, counted in whatever the search answers \
		with: a document that expanded into values counts once per value.""")
	Total total,

	/**
	 * Total count of matching documents, which facet counts aggregate. Present
	 * only for a search whose `hits` specifies `when`; omitted when identical
	 * to {@code total}.
	 */
	@Schema(description = """
		How many documents matched, which is what the facets are counted in. \
		Present only for a search whose `hits` names a `when`, where some \
		documents expand into values and the rest do not; omitted otherwise, \
		where it would be the same number as `total`. A document that `when` \
		expands with no matching value under `path` counts here while \
		answering with no hit.""")
	Total documents,

	/**
	 * Map of facet names to facet results. Present when facets are requested;
	 * omitted otherwise.
	 */
	@Schema(description = """
		Facet results keyed by facet name. Omitted entirely when the request \
		asked for no facets.""")
	Map<String, Facet> facets,

	/**
	 * Pagination state and navigation cursors for the current result window.
	 */
	@Schema(description = "Where in the results this window sits, and how to move from it.")
	Page page,

	/**
	 * Details of dropped terms when query relaxation was applied. Omitted if
	 * the query was not relaxed.
	 */
	@Schema(description = """
		What the search let go of to find anything. Omitted entirely when the \
		query was not relaxed, so its presence always means the results answer \
		less than what was asked for.""")
	Relaxed relaxed,

	/**
	 * The filters the search read out of the query text, and the text that
	 * was left. Omitted when nothing was read.
	 */
	@Schema(description = """
		What the search read out of the query text as filters, and the text \
		that was left. Omitted entirely when nothing was read. See [Reading \
		numbers and \
		units](https://exofind.dev/reference/search-api/#reading-numbers-and-units).""")
	Interpreted interpreted,

	/**
	 * Total execution time for the search request in milliseconds, including
	 * fractional milliseconds.
	 */
	@Schema(
		description = """
			Execution time for the search request in milliseconds, including \
			fractions of one.""",
		examples = "7.412"
	)
	double tookMs
) {
	/**
	 * The example response, as the JSON the engine answers with. It answers
	 * the request under {@link SearchRequest#EXAMPLE}, so the two read
	 * together.
	 */
	public static final String EXAMPLE = """
		{
		  "hits": [
		    {
		      "id": "9781234567890",
		      "score": 8.42,
		      "document": { "name": "Silent Spring", "price": 12.50 }
		    },
		    {
		      "id": "9780007458424",
		      "score": 3.17,
		      "document": { "name": "Spring Snow", "price": 9.95 }
		    }
		  ],
		  "total": { "count": 128, "exact": true },
		  "facets": {
		    "category": {
		      "values": [
		        { "value": "fiction", "count": 87 },
		        { "value": "poetry", "count": 41 }
		      ],
		      "totalValues": 2
		    }
		  },
		  "page": { "limit": 20, "offset": 0, "next": "c2NvcmU6My4xN3w5NzgwMDA3NDU4NDI0" },
		  "tookMs": 7.412
		}""";

	/**
	 * A single search result. Represents either a matching document, or - when
	 * `hits` specifies an object field - an individual matching value of that
	 * field, with `index` and `value` present and the parent document under
	 * `document`.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		One result: usually a document that matched, or - for a search whose \
		`hits` names an object field - one matched value of that field, with \
		`index` and `value` present and the document holding it under \
		`document`.""")
	public record Hit(
		/**
		 * Primary key of the document, omitted on an index without a primary
		 * key. A value hit carries the primary key of its parent document, so
		 * multiple hits share an `id` when several values of one document
		 * match. The identity of such a hit is `id` together with `index`, and
		 * deduping by `id` alone is a mistake.
		 */
		@Schema(
			description = """
				Primary key of the document, omitted on an index that has \
				none. A value hit carries the key of the document holding it, \
				so several hits share an `id` when several values of one \
				document matched - the identity of such a hit is `id` together \
				with `key`, or with `index` on a field that declares no \
				key.""",
			examples = "9781234567890"
		)
		Object id,

		/**
		 * Zero-based position of the value in the parent document array.
		 * Present only on value hits.
		 */
		@Schema(description = """
			Zero-based position of the value this hit stands for in the \
			parent document's value array. Present only when the search asked \
			for value hits. A reindex is free to reorder values, so this names \
			the same value only for as long as the document is not written \
			again - `key` is what does not move.""")
		Integer index,

		/**
		 * Declared key value for the nested object value. Present only on value
		 * hits for fields with a declared key. On an index that does not store
		 * document source, present when the key's field is stored.
		 */
		@Schema(description = """
			What the value this hit stands for reads for the `key` its object \
			field declares. Two values of one document never read the same, so \
			`id` and `key` name one value and go on naming it after a reindex. \
			Omitted for a document hit, for a field that declares no key, and \
			on an index whose `source` is `none` when the key's field is not \
			`stored`.""")
		String key,

		/**
		 * Relevance score of the hit. Omitted when the search computes no
		 * scores. A hit standing for a value scores what its document scored
		 * plus what the value itself scored under the `nested` clauses of its
		 * path.
		 */
		@Schema(
			description = """
				How well the hit matched. Omitted when the search computed no \
				scores, rather than defaulted to something that looks like a \
				value. A value hit scores what its document scored plus what \
				the value itself scored under the `nested` clauses of its \
				path.""",
			examples = "8.42"
		)
		Float score,

		/**
		 * The matched nested value object. Present only when the search
		 * requests value hits. On an index that does not retain document
		 * source copies, holds the stored fields of the value.
		 */
		/*
		 * Typed as a free-form object rather than by the engine's Document,
		 * which is a list of named values on the inside and is written out by
		 * DocumentSerializer as an object keyed by field name. Left to the
		 * scanner the document would describe the inside rather than the wire.
		 */
		@Schema(
			type = SchemaType.OBJECT,
			implementation = Object.class,
			description = """
				The matched nested value object, keyed by field name. Present \
				only when the search requests value hits. On an index whose \
				`source` is `none` it holds the value's `stored` fields, and \
				is omitted when nothing of the value is stored."""
		)
		@JsonSerialize(using = DocumentSerializer.class)
		Document value,

		/**
		 * The fields requested by the search, shaped as indexed. A field with
		 * multiple values is an array, and a locale-specific field is an object
		 * keyed by locale tag. For a value hit, returns the fields of the
		 * parent document.
		 */
		@Schema(
			type = SchemaType.OBJECT,
			implementation = Object.class,
			description = """
				Selected fields of the document per the search request \
				`fields` property, keyed by field name and shaped as indexed. \
				A field declared `multiple` is an array, and a locale-specific \
				field is an object holding the single variant read for the \
				query locale. For a value hit, returns the fields of the \
				parent document."""
		)
		@JsonSerialize(using = DocumentSerializer.class)
		Document document,

		/**
		 * Highlighted fragments of the requested fields, keyed by field name.
		 * Present when highlighting is requested, omitting fields with no
		 * matches. For a value hit, the fragments are cut from the hit's own
		 * value. Omitted when highlighting is not requested.
		 */
		@Schema(description = """
			Highlighted fragments keyed by field name. Present when \
			highlighting is requested, omitting fields with no matching text \
			in the hit - for a value hit the fragments are cut from the hit's \
			own value. Omitted when highlighting is not requested.""")
		Map<String, List<String>> highlights,

		/**
		 * Matched values of each requested nested object field, keyed by field
		 * name. Present when matched values are requested, with an entry per
		 * requested field. Omitted when matched values are not requested.
		 */
		@Schema(description = """
			Matched nested values keyed by field name, with one entry per \
			requested field. Omitted when matched values are not requested.""")
		Map<String, MatchedValues> matched
	) {
	}

	/**
	 * Matched values of an object field for a hit.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = "Matched values of a nested object field for a hit.")
	public record MatchedValues(
		/**
		 * The matched values, up to the requested limit. Values are ordered by
		 * score when scoring clauses exist within the nested clause, and in
		 * document order otherwise. On an index that does not retain document
		 * source copies, each value holds its stored fields.
		 */
		@Schema(
			type = SchemaType.ARRAY,
			implementation = Object.class,
			description = """
				Array of matched nested values, each keyed by field name, up \
				to `limit`. If scoring clauses exist within the `nested` \
				clause, values are ordered by score; otherwise, they appear in \
				document order. On an index whose `source` is `none` each \
				value holds its `stored` fields, and the array is omitted when \
				nothing of the values is stored."""
		)
		@JsonSerialize(contentUsing = DocumentSerializer.class)
		List<Document> values,

		/**
		 * Total count of matched values for the object field in the document.
		 * Exceeds the number of entries under `values` when the limit is
		 * reached.
		 */
		@Schema(description = """
			Total count of matched values for the nested field in the \
			document. Exceeds the number of entries under `values` when the \
			limit is reached.""")
		int totalValues
	) {
	}

	/**
	 * Match counts for distinct values of one faceted field.
	 *
	 * <p>Facet counts exclude filter entries on the facet's own field by
	 * default, allowing other values to remain visible. Query clauses and other
	 * filters narrow facet counts.
	 *
	 * <p>Counting per value returns {@code values} with {@code totalValues};
	 * counting into ranges returns {@code buckets}, omitting the other
	 * representation.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Match counts for one faceted field. Counting per value returns \
		`values` with `totalValues`; counting into ranges returns `buckets`, \
		omitting the other representation. Facet counts exclude filter entries \
		on the facet's own field by default.""")
	public record Facet(
		/**
		 * Facet value objects with counts, in the requested sort order and
		 * limited to the configured maximum.
		 */
		@Schema(description = """
			Array of facet value objects, in the requested order and limited \
			to the configured maximum.""")
		List<FacetValue> values,

		/**
		 * Total count of distinct values matching the query. Exceeds the number
		 * of returned values when the limit is reached.
		 */
		@Schema(description = """
			Total count of distinct values matching the query. Exceeds the \
			number of entries under `values` when the limit is reached.""")
		Integer totalValues,

		/**
		 * Range bucket objects with match counts, in the requested order.
		 */
		@Schema(description = """
			Array of range bucket objects with match counts, in the requested \
			order.""")
		List<FacetBucket> buckets
	) {
	}

	/**
	 * One value of a faceted field with its match count.
	 *
	 * <p>For hierarchical fields, returns an entry per hierarchy level and
	 * nests child levels under `values`. Other fields omit hierarchical
	 * properties.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		One value of a faceted field with its match count. For hierarchical \
		fields, returns an entry per hierarchy level and nests child levels \
		under `values`. Other fields omit hierarchical properties.""")
	public record FacetValue(
		/**
		 * The facet value in its stored format: a string, boolean, number, or
		 * ISO 8601 timestamp string. For a hierarchical field, returns the
		 * label of the current level.
		 */
		@Schema(
			description = """
				The facet value in its stored format: a string, boolean, \
				number, or ISO 8601 timestamp string. For a hierarchical \
				field, returns the label of the current level.""",
			examples = "fiction"
		)
		Object value,

		/**
		 * Number of matching documents containing this value.
		 */
		@Schema(description = "Number of matching documents containing this value.", examples = "87")
		long count,

		/**
		 * The full path to this level, used in filter matchers. Omitted for
		 * non-hierarchical fields.
		 */
		@Schema(
			description = """
				The full path to the level, used in `under` filter matchers. \
				Omitted for non-hierarchical fields.""",
			examples = "Men/Shoes"
		)
		String path,

		/**
		 * Child hierarchy levels with their counts, evaluated up to the
		 * requested depth. Omitted at the maximum counted depth and for
		 * non-hierarchical fields.
		 */
		@Schema(description = """
			Child hierarchy levels with their counts, evaluated up to `depth` \
			levels below the current path. Omitted at the maximum counted \
			depth and for non-hierarchical fields.""")
		List<FacetValue> values,

		/**
		 * Total count of distinct child values below this level. Exceeds the
		 * number of entries under `values` when the limit is reached. Omitted
		 * for non-hierarchical fields.
		 */
		@Schema(description = """
			Total count of distinct child values below this level. Exceeds the \
			number of entries under `values` when the limit is reached. \
			Omitted for non-hierarchical fields.""")
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
	 * One bucket of a faceted field with its match count.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = "One range bucket of a faceted field with its match count.")
	public record FacetBucket(
		/**
		 * Inclusive lower bound of the range bucket, as specified in the
		 * request. Omitted for open-ended ranges.
		 */
		@Schema(
			description = """
				Inclusive lower bound of the range bucket, as specified in the \
				request. Omitted for open-ended ranges.""",
			examples = "100"
		)
		Object from,

		/**
		 * Exclusive upper bound of the range bucket, as specified in the
		 * request. Omitted for open-ended ranges.
		 */
		@Schema(
			description = """
				Exclusive upper bound of the range bucket, as specified in the \
				request. Omitted for open-ended ranges.""",
			examples = "200"
		)
		Object to,

		/**
		 * Number of matching documents with values falling within the range
		 * bucket.
		 */
		@Schema(description = """
			Number of matching documents with values falling within the range \
			bucket.""", examples = "17")
		long count
	) {
	}

	/**
	 * The filters a search read out of the query text, and the text that was
	 * left once their words were taken out.
	 *
	 * <p>Present only when something was read. The words of a filter are still
	 * searched as text, so the results hold what the filter finds and what
	 * the words find as text.
	 */
	@Schema(description = """
		The filters a search read out of the query text, and the text that \
		was left once their words were taken out. Present only when \
		something was read. The words of a filter are still searched as \
		text, so the results hold what the filter finds as well as what the \
		words find as text, with the filter ranked first.""")
	public record Interpreted(
		/**
		 * The filters that were read, in the order their words were typed.
		 */
		@Schema(description = """
			The filters that were read, in the order their words were typed. \
			Two fields declaring the same unit read the same words as two \
			filters, either of which a document may satisfy.""")
		List<Filter> filters,

		/**
		 * The text that was left once the words of the filters were taken
		 * out, as a query text that reads back to the same search.
		 */
		@Schema(
			description = """
				The text that was left once the words of the filters were \
				taken out. Empty when everything typed was read.""",
			examples = "shoes"
		)
		String text
	) {
		/**
		 * One filter read out of the query text.
		 */
		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = "One filter read out of the query text.")
		public record Filter(
			/**
			 * The field the filter is on.
			 */
			@Schema(description = "The field the filter is on.", examples = "price")
			String field,

			/**
			 * Clauses that hold where the filter is read, as the target the
			 * request named said.
			 */
			@Schema(description = """
				Clauses that hold where the filter is read, as the `when` of \
				the target the request named. Absent when the filter is read \
				wherever the field holds a value.""")
			List<Clause> when,

			/**
			 * What the values of the field have to satisfy, in the shape a
			 * `field` clause takes.
			 */
			@Schema(description = """
				What the values of the field have to satisfy, in the shape the \
				`match` of a `field` clause takes: a `range` for a bound, an \
				`equals` for a number written with its unit and nothing else. \
				Can be sent back as a filter as it is.""")
			Matcher match,

			/**
			 * The words the filter was read from, as typed.
			 */
			@Schema(description = """
				The words the filter was read from, as they were typed and in \
				the order they were typed.""")
			List<String> words,

			/**
			 * The targets read instead where a document holds no value on the
			 * field, as the request named them.
			 */
			@Schema(description = """
				The targets read instead where a document holds no value on \
				the field, in order, as the `fallback` of the target the \
				request named. Absent when there are none.""")
			List<Clause.Text.Target> fallback
		) {
			public Filter(String field, Matcher match, List<String> words) {
				this(field, null, match, words, null);
			}
		}
	}

	/**
	 * Details of dropped query terms when query relaxation was applied.
	 *
	 * <p>Present only when the initial query produced zero results. Total
	 * counts and facet counts reflect the relaxed search.
	 */
	@Schema(description = """
		Details of dropped query terms when query relaxation was applied. \
		Present only when the initial query produced zero results. Total \
		counts and facet counts reflect the relaxed search.""")
	public record Relaxed(
		/**
		 * List of dropped words and the reason each was removed, in the order
		 * they appeared in the query.
		 */
		@Schema(description = """
			List of dropped words and the reason each was removed, in the \
			order they appeared in the query.""")
		List<Dropped> dropped,

		/**
		 * The effective query string used to execute the search.
		 */
		@Schema(
			description = "The effective query string used to execute the search.",
			examples = "running shoes"
		)
		String text
	) {
		/**
		 * Details of a single dropped query word and the reason for its
		 * removal.
		 */
		@Schema(description = """
			Details of a single dropped query word and the reason for its \
			removal.""")
		public record Dropped(
			/**
			 * The word as it was typed.
			 */
			@Schema(description = "The word as it was typed.", examples = "waterproof")
			String word,

			/**
			 * Reason the word was dropped.
			 */
			@Schema(description = "Reason the word was dropped.")
			Reason reason
		) {
			/**
			 * Reason a query word was dropped.
			 */
			@Schema(description = """
				Reason a word was dropped: `unmatched` when the word does not \
				exist in the index; `common` when it is one of the most common \
				words across documents.""")
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
	 * Total match count, measured in whatever unit the search returns.
	 */
	@Schema(description = "Total match count, measured in whatever unit the search returns.")
	public record Total(
		/**
		 * Total number of matching results.
		 */
		@Schema(description = "Total number of matching results.", examples = "128")
		long count,

		/**
		 * Whether the count is exact or a lower bound. Always true when exact
		 * counting or facets are requested.
		 */
		@Schema(description = """
			Whether `count` is exact or a lower bound. Always true when \
			`"total": "exact"` is requested or when calculating facets.""")
		boolean exact
	) {
	}

	/**
	 * Pagination state and navigation cursors for the result window.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = "Pagination state and navigation cursors for the result window.")
	public record Page(
		/**
		 * Maximum number of results returned in the page window.
		 */
		@Schema(description = "Maximum number of results returned in the page window.", examples = "20")
		int limit,

		/**
		 * Number of matching results skipped before the window. Omitted when
		 * navigating with {@code next} or {@code previous} cursors, which
		 * encode positions rather than count offsets.
		 */
		@Schema(description = """
			Number of matching results skipped before the window. Omitted when \
			navigating with `next` or `previous` cursors. Cursors encode \
			positions rather than count offsets and are not restricted by \
			`EXOFIND_SEARCH_MAX_PAGE_DEPTH`.""")
		Integer offset,

		/**
		 * Cursor for the preceding window. Omitted on the first window.
		 */
		@Schema(description = """
			Cursor for the preceding window, passed in `before`. Omitted on \
			the first window.""")
		String previous,

		/**
		 * Cursor for the next window. Omitted on the final window.
		 */
		@Schema(description = """
			Cursor for the next window, passed in `after`. Omitted on the \
			final window.""")
		String next,

		/**
		 * Numbered page metadata, present when requested.
		 */
		@Schema(description = "Numbered page metadata, present when requested.")
		Pages pages
	) {
	}

	/**
	 * Numbered page metadata, divided into {@code start}, {@code middle} and
	 * {@code end} arrays. A client renders them as {@code 1 2 3 … 7}, with an
	 * ellipsis wherever {@code middle} is not adjacent to the numbers beside
	 * it.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Numbered page metadata, divided into `start`, `middle`, and `end` \
		arrays to render `1 2 3 … 7` with ellipses at window boundaries. Page \
		numbers are 1-based. Cursors inside encode count offsets and remain \
		subject to `EXOFIND_SEARCH_MAX_PAGE_DEPTH`.""")
	public record Pages(
		/**
		 * Total number of pages.
		 */
		@Schema(description = "Total number of pages.", examples = "7")
		long count,

		/**
		 * Metadata for the page preceding the current page. Omitted on the
		 * first page.
		 */
		@Schema(description = """
			Metadata for the page preceding the current page. Omitted on the \
			first page.""")
		PageRef previous,

		/**
		 * Metadata for the page following the current page. Omitted on the
		 * final page and when the page exceeds maximum page depth.
		 */
		@Schema(description = """
			Metadata for the page following the current page. Omitted on the \
			final page and when the page exceeds maximum page depth.""")
		PageRef next,

		/**
		 * Page entries at the start of the list.
		 */
		@Schema(description = "Page entries at the start of the list.")
		List<PageRef> start,

		/**
		 * Page entries surrounding the current page, present when they touch
		 * neither end of the list.
		 */
		@Schema(description = """
			Page entries surrounding the current page, present when they touch \
			neither end of the list.""")
		List<PageRef> middle,

		/**
		 * Page entries at the end of the list. Omitted when the final page
		 * exceeds maximum page depth.
		 */
		@Schema(description = """
			Page entries at the end of the list. Omitted when the final page \
			exceeds maximum page depth.""")
		List<PageRef> end
	) {
	}

	/**
	 * Metadata for a single numbered page.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = "Metadata for a single numbered page.")
	public record PageRef(
		/**
		 * 1-based page number.
		 */
		@Schema(description = "1-based page number.", examples = "3")
		long number,

		/**
		 * Cursor that fetches the page.
		 */
		@Schema(description = "Cursor that fetches the page, passed in `after`.")
		String cursor,

		/**
		 * True for the current page; omitted on all other pages.
		 */
		@Schema(description = """
			True for the current page; omitted on all other pages.""")
		Boolean current
	) {
	}
}
