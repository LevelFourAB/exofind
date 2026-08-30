package se.l4.exofind.engine.api.v1alpha1.search.model;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A search request sent over the API.
 *
 * <p>All request properties are optional. An empty request matches all
 * documents in the index and returns the first page of results. Query clauses
 * are combined with an implicit AND. Filter refinements are specified in
 * {@code filters} so that facets can exclude filter entries on their own fields
 * from match counts:
 *
 * <pre>{@value #EXAMPLE}</pre>
 *
 * <p>Result pagination is configured using {@code limit} together with at most
 * one of {@code offset}, {@code after}, or {@code before}. Cursors are opaque
 * tokens returned in previous responses: {@code after} fetches the next page
 * following the window, {@code before} fetches the preceding page, and results
 * are always returned in sort order.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
	description = """
		All request properties are optional. An empty request matches all \
		documents in the index.""",
	examples = SearchRequest.EXAMPLE
)
public record SearchRequest(
	/**
	 * Clauses that a matching document must satisfy. If omitted, matches all
	 * documents. Evaluated clauses narrow all facet counts. Refinement filters
	 * belong in {@code filters}.
	 */
	@Schema(description = """
		Clauses that a matching document must satisfy. Clauses in the array \
		are combined with an implicit `AND`. Evaluated clauses narrow all \
		facet counts. If omitted, matches all documents.""")
	List<Clause> query,

	/**
	 * Refinement clauses that narrow matching hits, specified separately from
	 * {@code query} clauses so that facets can exclude filter entries on their
	 * own fields from match counts.
	 *
	 * <p>Only {@code field} and {@code nested} clauses are supported.
	 * Conditions on fields inside nested objects are specified using
	 * {@code nested} clauses. Filter clauses do not score results and do not
	 * affect result ranking. Filter exclusions apply per filter entry: specify
	 * separate entries per faceted field and combine multiple filter values
	 * within a single matcher.
	 */
	@Schema(description = """
		Refinement clauses, specified as `field` clauses or `nested` \
		clauses. Filters narrow hits, but facets on the filtered field \
		exclude their own filter entries from counts by default (see \
		[Facets](https://exofind.dev/reference/search-api/#facets)). \
		Unsupported clause types return `search:filter:clause_invalid`. \
		Clauses that score results return `search:filter:scores`.""")
	List<Clause> filters,

	/**
	 * Fields to aggregate match counts for. If omitted, no facet counts are
	 * calculated. Facet results in the response are keyed by facet name.
	 */
	@Schema(description = """
		Fields to aggregate match counts for. See \
		[Facets](https://exofind.dev/reference/search-api/#facets). \
		If omitted, no facet counts are calculated.""")
	List<Facet> facets,

	/**
	 * Order in which results are returned. If omitted, results are sorted by
	 * relevance score in descending order.
	 */
	@Schema(description = """
		Order in which results are returned. If omitted, results are sorted \
		by relevance score in descending order.""")
	List<Sort> sort,

	/**
	 * BCP-47 locale tag used to read and return locale-specific fields. If
	 * omitted, uses each field's default locale.
	 */
	@Schema(
		description = """
			BCP-47 locale tag used to read and return locale-specific \
			fields. Matches the closest declared locale on each field (for \
			example, `sv-SE` falls back to `sv`). If no matching variant \
			exists, uses the field default.""",
		examples = "sv"
	)
	String locale,

	/**
	 * Document fields to return with each result. If omitted, returns all
	 * stored fields. The primary key is always included. Fields inside an
	 * object are specified by dotted path and returned nested inside the
	 * object, containing only the requested fields.
	 */
	@Schema(description = """
		Document fields to return with each result. Fields inside an \
		[`object`](https://exofind.dev/reference/field-types/#object) \
		are specified by dotted path and returned nested inside the object. \
		Requesting unretrievable fields returns an error (see [Document \
		source](https://exofind.dev/reference/field-types/#document-source)). \
		The primary key is always included.""")
	List<String> fields,

	/**
	 * Fields to return highlighted snippets for. If omitted, no highlights are
	 * returned.
	 */
	@Schema(description = """
		Fields to return highlighted snippets for. See \
		[Highlighting](https://exofind.dev/reference/search-api/#highlighting).""")
	Highlight highlight,

	/**
	 * Nested object fields for which to return matched values with each hit. If
	 * omitted, matched values are not returned.
	 */
	@Schema(description = """
		Nested object fields for which to return matched values with each \
		hit. See [Matched \
		values](https://exofind.dev/reference/search-api/#matched-values).""")
	Matched matched,

	/**
	 * Specifies an object field whose matched values return as individual hits
	 * instead of full documents. If omitted, hits represent documents. See
	 * {@link Hits}.
	 */
	@Schema(description = """
		Specifies an object field whose matched values return as individual \
		hits instead of full documents. See [What a hit stands \
		for](https://exofind.dev/reference/search-api/#what-a-hit-stands-for).""")
	Hits hits,

	/**
	 * How many results to return. Zero returns how many there are without
	 * returning any of them.
	 */
	@Schema(
		description = """
			Maximum number of results to return, at most \
			`EXOFIND_SEARCH_MAX_LIMIT`. Setting `limit` to `0` returns the \
			total match count without hits.""",
		defaultValue = "10"
	)
	Integer limit,

	/**
	 * How many results to skip before the ones being returned.
	 */
	@Schema(
		description = """
			Number of matching results to skip. Specify at most one of \
			`offset`, `after`, or `before`.""",
		defaultValue = "0"
	)
	Integer offset,

	/**
	 * Cursor to continue after, from the {@code next} of a previous response.
	 */
	@Schema(description = """
		Cursor string from the `next` property of a previous response to \
		fetch the next page.""")
	String after,

	/**
	 * Cursor to read the window preceding, from the {@code previous} of a
	 * previous response.
	 */
	@Schema(description = """
		Cursor string from the `previous` property of a previous response to \
		fetch the preceding page.""")
	String before,

	/**
	 * Requests numbered page metadata in the response. Implies {@code total}
	 * being {@code exact}; pages cannot be numbered against a lower bound.
	 */
	@Schema(description = """
		Requests numbered page metadata. Accepts an optional \
		`{ "max": n }` object to limit the number of page entries (default \
		`9`). Implies `"total": "exact"`.""")
	Pages pages,

	/**
	 * How far the total is counted, left out for {@code estimate}.
	 */
	@Schema(defaultValue = "estimate")
	Total total,

	/**
	 * Document ranking signals used to adjust relevance scoring. Added to the
	 * signals configured on the index unless {@code signalsMode} says
	 * otherwise. Evaluated only when results are ordered by relevance;
	 * providing {@code sort} overrides ranking signals. If omitted, uses the
	 * ranking signals configured on the index.
	 */
	@Schema(description = """
		Document ranking signals used to adjust relevance scoring. Added to \
		the signals configured on the index unless `signalsMode` says \
		otherwise. See \
		[Signals](https://exofind.dev/reference/search-api/#signals). \
		If omitted, uses the ranking signals configured on the index.""")
	List<Signal> signals,

	/**
	 * Controls how {@code signals} meets the ranking configured on the index.
	 * Defaults to {@code add}. Supplying this without {@code signals} returns
	 * an error.
	 */
	@Schema(
		description = """
			How `signals` meets the ranking configured on the index: `"add"` \
			ranks by both, with a signal here standing in for one on the same \
			field; `"replace"` ranks by `signals` alone. Supplying this \
			without `signals` returns `search:signal:mode_without_signals`.""",
		defaultValue = "add"
	)
	SignalsMode signalsMode,

	/**
	 * Reorders the best results of a search in a second pass without changing
	 * which documents matched. Evaluated only when results are ordered by
	 * relevance. Cannot be combined with {@code hits}.
	 */
	@Schema(description = """
		Reorders the best results of a search in a second pass without \
		changing which documents matched. See \
		[Rescoring](https://exofind.dev/reference/search-api/#rescoring).""")
	Rescore rescore
) {
	/**
	 * The example search, as the JSON a client sends. The class Javadoc and
	 * the OpenAPI schema of this record both show this text.
	 *
	 * <p>{@code SchemaExampleTest} reads it back into this record with unknown
	 * properties rejected, so an example naming a property the record has lost
	 * fails the build.
	 */
	public static final String EXAMPLE = """
		{
		  "query": [
		    { "type": "text", "text": "silent spr", "fields": { "name": 3 } },
		    { "field": "published", "match": { "value": true } }
		  ],
		  "filters": [
		    { "field": "category", "match": { "type": "in", "values": ["fiction", "poetry"] } }
		  ],
		  "facets": [ { "field": "category" } ],
		  "sort": [ { "type": "score" }, { "field": "name", "order": "asc" } ],
		  "fields": ["name", "price"],
		  "limit": 20
		}""";

	/**
	 * A search that answers in the order its ranking gave, without a second
	 * pass over the best of them.
	 */
	public SearchRequest(
		List<Clause> query,
		List<Clause> filters,
		List<Facet> facets,
		List<Sort> sort,
		String locale,
		List<String> fields,
		Highlight highlight,
		Matched matched,
		Hits hits,
		Integer limit,
		Integer offset,
		String after,
		String before,
		Pages pages,
		Total total,
		List<Signal> signals
	) {
		this(
			query, filters, facets, sort, locale, fields, highlight, matched, hits, limit, offset,
			after, before, pages, total, signals, null, null
		);
	}

	/**
	 * A search adding whatever signals it brings to the ranking of the index.
	 */
	public SearchRequest(
		List<Clause> query,
		List<Clause> filters,
		List<Facet> facets,
		List<Sort> sort,
		String locale,
		List<String> fields,
		Highlight highlight,
		Matched matched,
		Hits hits,
		Integer limit,
		Integer offset,
		String after,
		String before,
		Pages pages,
		Total total,
		List<Signal> signals,
		Rescore rescore
	) {
		this(
			query, filters, facets, sort, locale, fields, highlight, matched, hits, limit, offset,
			after, before, pages, total, signals, null, rescore
		);
	}

	/**
	 * Controls how search request signals meet the ranking configured on the
	 * index.
	 */
	@Schema(description = """
		How search request signals meet the ranking configured on the index: \
		`"add"` ranks by both; `"replace"` ranks by the request's signals \
		alone.""")
	public enum SignalsMode {
		/**
		 * Rank by both. A signal naming a field the index also ranks by stands
		 * in for the index's.
		 */
		@JsonProperty("add")
		ADD,

		/**
		 * Rank by the signals of the request alone. An empty list then ranks by
		 * how well documents match and nothing else.
		 */
		@JsonProperty("replace")
		REPLACE
	}

	/**
	 * Counting mode for the total matching document count.
	 */
	@Schema(description = """
		Counting mode for the total matching document count: `"estimate"` \
		counts until exceeding the returned window; `"exact"` counts every \
		matching document.""")
	public enum Total {
		/**
		 * Stop counting once it is known there are more matches than the
		 * search brings back, leaving the count a lower bound.
		 */
		@JsonProperty("estimate")
		ESTIMATE,

		/**
		 * Count every match, however many there are.
		 */
		@JsonProperty("exact")
		EXACT
	}

	/**
	 * Computes match counts for distinct values of a field, or across range
	 * buckets when {@code ranges} is specified.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Computes match counts for distinct values of a field. The target \
		field must have `facet` enabled in its field definition; otherwise, \
		the request returns `index:query:usage_not_enabled`.""")
	public record Facet(
		/**
		 * Key used for the facet in the response. Defaults to the field name.
		 * Required when faceting on the same field multiple times.
		 */
		@Schema(description = """
			Key used for the facet in the response. Required when faceting \
			on the same field multiple times. Duplicate facet names return \
			`search:facet:duplicate_name`. Defaults to the field name.""")
		String name,

		/**
		 * Name of the target field to aggregate, as declared in the index
		 * definition. The field must have faceting enabled.
		 */
		@Schema(
			description = "Target field to aggregate.",
			required = true,
			examples = "category"
		)
		String field,

		/**
		 * Maximum number of facet values to return (1 to 1000). Defaults to 10.
		 * Cannot be combined with {@code ranges}.
		 */
		@Schema(
			description = "Maximum number of facet values to return.",
			defaultValue = "10",
			minimum = "1",
			maximum = "1000"
		)
		Integer limit,

		/**
		 * Sort order of facet values. Defaults to {@code count}. Cannot be
		 * combined with {@code ranges}.
		 */
		@Schema(
			description = """
				Sort order of facet values: `"count"` (descending by count) \
				or `"value"` (ascending by value).""",
			defaultValue = "count"
		)
		Order order,

		/**
		 * Array of range bucket definitions to aggregate match counts into.
		 * Results return one count per bucket in the specified order.
		 */
		@Schema(description = """
			Array of range bucket definitions. See [Range \
			buckets](https://exofind.dev/reference/search-api/#range-buckets). \
			Cannot be combined with `limit` or `order` \
			(`search:facet:ranges_conflicting`).""")
		List<Range> ranges,

		/**
		 * Starting path level for hierarchical fields, defaulting to the root.
		 * Requires a field configured with hierarchy. Specify the `path`
		 * returned by a previous facet response.
		 */
		@Schema(description = """
			Starting path level for hierarchical fields. See [Counting down \
			a \
			tree](https://exofind.dev/reference/search-api/#counting-down-a-tree). \
			Defaults to the root.""")
		String path,

		/**
		 * Number of hierarchical levels below `path` to count (1 to 10).
		 * Defaults to 1. The `limit` and `order` options apply per level.
		 */
		@Schema(
			description = """
				Number of hierarchical levels below `path` to count.""",
			defaultValue = "1",
			minimum = "1",
			maximum = "10"
		)
		Integer depth,

		/**
		 * List of field paths whose filter entries are excluded from this
		 * facet's calculation. A filter entry is excluded when its path equals
		 * or falls under one of these paths. Defaults to the facet's own field
		 * path. An empty list disables filter exclusion.
		 */
		@Schema(description = """
			List of field paths whose filter entries are excluded from this \
			facet's calculation. Defaults to the facet's own field path. An \
			empty array `[]` disables filter exclusion. A blank path returns \
			`search:facet:exclude_filters_invalid`.""")
		List<String> excludeFilters
	) {
		/**
		 * Sort order of facet values: descending by count or ascending by
		 * value.
		 */
		@Schema(description = """
			Sort order of facet values: `count` (descending by count) or \
			`value` (ascending by value).""")
		public enum Order {
			/**
			 * The most common values first.
			 */
			@JsonProperty("count")
			COUNT,

			/**
			 * Ascending by the value itself.
			 */
			@JsonProperty("value")
			VALUE
		}

		/**
		 * A range bucket holding values from {@code from} (inclusive) up to
		 * {@code to} (exclusive). At least one bound is required.
		 */
		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(
			name = "FacetRange",
			description = """
				One range bucket, holding values from `from` (inclusive) up to \
				`to` (exclusive), so adjacent buckets sharing a bound count no \
				value twice. Either bound may be omitted for an open-ended \
				range, but not both (`search:facet:range_empty`), and `to` \
				must be greater than `from` \
				(`index:query:facet_range_empty`). At most 1000 buckets per \
				facet (`search:facet:ranges_too_many`); using `ranges` on an \
				unsupported field type returns `index:invalid-query-type`."""
		)
		public record Range(
			/**
			 * Inclusive lower bound for the range bucket. Omit for an
			 * open-ended lower bound.
			 */
			@Schema(
				description = """
					The lowest value the bucket holds, itself included. Omit \
					for no lower end.""",
				examples = "100"
			)
			Object from,

			/**
			 * Exclusive upper bound for the range bucket. Omit for an
			 * open-ended upper bound.
			 */
			@Schema(
				description = """
					Where the bucket ends, itself not included. Omit for no \
					upper end.""",
				examples = "200"
			)
			Object to
		) {
		}
	}

	/**
	 * Requests numbered page metadata.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Requests numbered page metadata. Sending an empty object asks for the \
		defaults. Can be combined with `offset` or page cursors, but not with \
		`after` or `before`.""")
	public record Pages(
		/**
		 * Maximum number of page entries to return. Defaults to 9.
		 */
		@Schema(
			description = "Maximum number of page entries to return.",
			defaultValue = "9"
		)
		Integer max
	) {
	}

	/**
	 * Requests highlighted snippets for specified fields. Fragments are
	 * generated only from scoring clauses; non-scoring filter clauses produce
	 * no highlights.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Requests highlighted snippets. Fragments are generated only from \
		scoring clauses, so non-scoring filter clauses produce no highlights. \
		Highlighted text is not HTML-escaped, and text beyond the first 10,000 \
		characters of a field value is not evaluated. See \
		[Highlighting](https://exofind.dev/reference/search-api/#highlighting).""")
	public record Highlight(
		/**
		 * Fields to return fragments for, keyed by field name in the index
		 * definition. An empty options object uses the defaults. Fields must
		 * have highlighting enabled in their field definitions.
		 */
		@Schema(
			description = """
				Fields to return fragments for, keyed by the name the field \
				has in the index definition. An empty options object asks for \
				the defaults. Fields must have highlighting enabled \
				(`matching` or `autocomplete`); requesting an unconfigured \
				field returns `index:query:usage_not_enabled`.""",
			required = true
		)
		Map<String, HighlightField> fields
	) {
	}

	/**
	 * Requests matched values of `nested` object fields for each hit. Returns
	 * the values that satisfied the query's `nested` clauses.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Requests the matched values of `nested` object fields with each hit. \
		Cannot be combined with `hits` (`search:hits:with_matched`). See \
		[Matched \
		values](https://exofind.dev/reference/search-api/#matched-values).""")
	public record Matched(
		/**
		 * Object fields to return matched values for, keyed by field name in
		 * the index definition. An empty options object uses the defaults.
		 * Targeting a field that is not a `nested` object returns an error.
		 */
		@Schema(
			description = """
				Object fields to answer for, keyed by the name the field has \
				in the index definition. An empty options object asks for the \
				defaults. Targeting a field that is not a `nested` object \
				returns `index:query:matched:not_object`.""",
			required = true
		)
		Map<String, MatchedField> fields
	) {
	}

	/**
	 * Specifies an object field whose matched values return as individual hits
	 * instead of full documents.
	 *
	 * <p>When a path is specified, each matched value of that object field
	 * returns as an individual hit. Totals count matching nested values, facets
	 * count value hits, and pagination cursors step through values. Clauses on
	 * index fields select matching documents, and `nested` clauses on the path
	 * select matching values.
	 *
	 * <p>With `when` specified, only matching documents expand into value hits;
	 * other matching documents return as document hits. The total counts hits,
	 * counting expanded documents once per matching nested value, while facets
	 * count matching documents.
	 *
	 * <p>Value hits cannot be combined with `matched`, cannot include a `knn`
	 * clause, and cannot sort by distance or by index root fields. `highlight`
	 * may name fields inside the path, and each hit returns fragments of its
	 * own value. Results are ordered by score or by fields inside the path.
	 * With `when` specified, results are ordered by score alone.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Makes each matched value of a `nested` object field a hit of its own \
		instead of a document hit. Totals count matching nested values, facets \
		count value hits, and pagination cursors step through values. With \
		`when`, only the documents it matches expand and the rest stay \
		document hits, totals count hits of both kinds and facets count \
		documents. Cannot be combined with `matched` \
		(`search:hits:with_matched`) or `knn` clauses \
		(`search:hits:with_knn`); `highlight` may only name fields inside \
		`path` (`search:hits:with_highlight`), and each hit returns fragments \
		of its own value. See [What a hit stands \
		for](https://exofind.dev/reference/search-api/#what-a-hit-stands-for).""")
	public record Hits(
		/**
		 * Name of the object field whose matched values are the hits, as named
		 * in the index definition. The field must be an object in `nested`
		 * mode.
		 */
		@Schema(
			description = """
				Dotted path of the nested object field whose matched values \
				become hits. Targeting a field that is not a `nested` object \
				returns `index:query:hits:not_object`.""",
			required = true,
			examples = "variants"
		)
		String path,

		/**
		 * Field paths inside the nested object to return with each value hit,
		 * specified by dotted path. If omitted, returns all object fields.
		 * Field paths not located inside the object are rejected, and an index
		 * that does not store document source answers only stored fields.
		 */
		@Schema(description = """
			Dotted field paths inside the nested object to return in `value`, \
			defaulting to all of them. Names must be prefixed by `path` \
			(`search:hits:field_not_inside`) and exist in the index \
			(`index:query:field_not_found`). On an index whose `source` is \
			`none`, a named field has to be `stored` \
			(`index:query:usage_not_enabled`).""")
		List<String> fields,

		/**
		 * Clauses selecting which documents expand into value hits, defaulting
		 * to all matching documents. Only `field` and `nested` clauses are
		 * supported, and clauses must not score.
		 *
		 * <p>A document that satisfies these clauses but contains no matching
		 * values under `path` returns no hit, and is not returned as a document
		 * hit.
		 */
		@Schema(description = """
			Clauses deciding which documents expand into value hits; every \
			other matching document stays a document hit. Combined with an \
			implicit `AND`, and specified as `field` or `nested` clauses. If \
			omitted, every matching document expands. Unsupported clause \
			types return `search:hits:when_clause_invalid`; clauses that \
			score return `search:hits:when_scores`. Sorting by a field is \
			refused while this is set (`search:hits:when_field_sort`).""")
		List<Clause> when
	) {
	}

	/**
	 * Configuration for returning matched values of a nested object field.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = "Configuration for returning matched values of a nested object field.")
	public record MatchedField(
		/**
		 * Maximum number of matched values to return per hit, from 1 to 100.
		 * Defaults to 3. The total count of matching values is returned
		 * alongside them.
		 */
		@Schema(
			description = """
				Maximum number of matched values to return per hit. How many \
				matched in all always comes back beside them.""",
			defaultValue = "3",
			minimum = "1",
			maximum = "100"
		)
		Integer limit,

		/**
		 * Field paths inside the nested object to include in each returned
		 * value, specified by dotted path. If omitted, returns all object
		 * fields. Specifying fields outside the object is rejected, and an
		 * index that does not store document source answers only stored
		 * fields.
		 */
		@Schema(description = """
			Field paths inside the nested object to include in each returned \
			value, defaulting to all of them. Paths must reside under the \
			target object path (`search:matched:field_not_inside`) and exist \
			in the schema (`index:query:field_not_found`). On an index whose \
			`source` is `none`, a named field has to be `stored` \
			(`index:query:usage_not_enabled`).""")
		List<String> fields
	) {
	}

	/**
	 * Configuration for highlighting text fragments in a single field.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = "Configuration for highlighting text fragments in a single field.")
	public record HighlightField(
		/**
		 * Maximum number of highlighted fragments to return. Defaults to 3.
		 */
		@Schema(
			description = "Maximum number of fragments to return.",
			defaultValue = "3"
		)
		Integer fragments,

		/**
		 * Target character length per fragment, defaulting to 150. Fragments
		 * break on sentence boundaries, and text shorter than this returns as a
		 * single fragment.
		 */
		@Schema(
			description = """
				Target character length per fragment. Fragments break on \
				sentence boundaries, and text shorter than this comes back as \
				a single fragment holding all of it.""",
			defaultValue = "150",
			minimum = "1",
			maximum = "10000"
		)
		Integer length,

		/**
		 * Prefix tag inserted before highlighted terms, defaulting to
		 * {@code <em>}. May be empty.
		 */
		@Schema(
			description = """
				Prefix tag inserted before highlighted terms. May be empty.""",
			defaultValue = "<em>"
		)
		String pre,

		/**
		 * Postfix tag inserted after highlighted terms, defaulting to
		 * {@code </em>}. May be empty.
		 */
		@Schema(
			description = """
				Postfix tag inserted after highlighted terms. May be empty.""",
			defaultValue = "</em>"
		)
		String post
	) {
	}
}
