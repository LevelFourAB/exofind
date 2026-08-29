package se.l4.exofind.engine.api.v1alpha1.search.model;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A search as it is received over the API.
 *
 * Everything is optional - an empty request matches every document and brings
 * back the first few of them. The clause list is an implicit AND, so a search
 * box together with the scope it searches in is a flat list rather than a
 * nested tree. The refinements a user ticks go in {@code filters} instead,
 * which is what lets a facet count sideways of the filter on its own field:
 *
 * <pre>
 * {
 *   "query": [
 *     { "type": "text", "text": "silent spr", "fields": { "name": 3 } },
 *     { "field": "published", "match": { "value": true } }
 *   ],
 *   "filters": [
 *     { "field": "category", "match": { "type": "in", "values": ["fiction", "poetry"] } }
 *   ],
 *   "facets": [ { "field": "category" } ],
 *   "sort": [ { "type": "score" }, { "field": "name", "order": "asc" } ],
 *   "fields": ["name", "price"],
 *   "limit": 20
 * }
 * </pre>
 *
 * Where in the results the answer starts is said with {@code limit} together
 * with at most one of {@code offset}, {@code after} and {@code before}. The
 * cursors are the opaque tokens a previous response handed out - {@code after}
 * continues past the window it came from, {@code before} is the window
 * preceding it, and hits always come back in sort order.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = """
	All request properties are optional. An empty request matches all \
	documents in the index.""")
public record SearchRequest(
	/**
	 * The clauses a document has to satisfy, all of them. Left out to match
	 * every document. A clause here narrows every facet count - scope a
	 * search here, tick filters in {@code filters}.
	 */
	@Schema(description = """
		Clauses that a matching document must satisfy. Clauses in the array \
		are combined with an implicit `AND`. Evaluated clauses narrow all \
		facet counts. If omitted, matches all documents.""")
	List<Clause> query,

	/**
	 * The ticked refinements of a filtering UI, each narrowing the results
	 * the way a {@code query} clause does. Kept apart from the query because
	 * facets are counted sideways of them: a facet leaves the entries it
	 * excludes - by default the ones on its own field - out of its counts,
	 * so ticking a category still shows what the other categories would
	 * hold.
	 *
	 * Only {@code field} and {@code nested} clauses may sit here - a
	 * condition on a field inside an object is a {@code nested} clause
	 * naming it - and no entry may rank, so ticking a filter never
	 * reshuffles the results. Exclusion is per entry: send one entry per
	 * facet field, several ticked values through one matcher.
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
	 * What to count the matches per value of, left out for no counting. The
	 * response keys each facet's counts by its name.
	 */
	@Schema(description = """
		Fields to aggregate match counts for. See \
		[Facets](https://exofind.dev/reference/search-api/#facets). \
		If omitted, no facet counts are calculated.""")
	List<Facet> facets,

	/**
	 * The order results come back in, left out for the best matches first.
	 */
	@Schema(description = """
		Order in which results are returned. If omitted, results are sorted \
		by relevance score in descending order.""")
	List<Sort> sort,

	/**
	 * The locale the search reads locale specific fields in (BCP-47), left
	 * out to leave every field to its own default locale.
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
	 * The fields to bring back with each result, left out for every stored
	 * field. The primary key is always included. A field inside an object is
	 * named by its dotted path and comes back inside the object, which then
	 * holds only the fields that were asked for.
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
	 * Ask for highlighted fragments with each hit, left out for none.
	 */
	@Schema(description = """
		Fields to return highlighted snippets for. See \
		[Highlighting](https://exofind.dev/reference/search-api/#highlighting).""")
	Highlight highlight,

	/**
	 * Ask each hit which values of an object field matched, left out for
	 * none.
	 */
	@Schema(description = """
		Nested object fields for which to return matched values with each \
		hit. See [Matched \
		values](https://exofind.dev/reference/search-api/#matched-values).""")
	Matched matched,

	/**
	 * Change what a hit stands for: each matched value of an object field
	 * becomes a hit of its own, instead of the document holding it. Left out
	 * for hits that are documents. See {@link Hits}.
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
			Maximum number of results to return. Setting `limit` to `0` \
			returns the total match count without hits.""",
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
	 * Ask for numbered pages in the response, so a pager can be rendered.
	 * Being present is what asks, and implies {@code total} being
	 * {@code exact} - pages can not be numbered against a lower bound.
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
	 * The values of the documents themselves to take into their relevance,
	 * left out to rank by the ones the index declares. Given, they replace
	 * those - an empty list ranks by how well documents match alone. Only read
	 * where relevance is the ordering, so a search that gives a {@code sort}
	 * of its own is unaffected.
	 */
	@Schema(description = """
		Document ranking signals used to adjust relevance scoring. See \
		[Signals](https://exofind.dev/reference/search-api/#signals). \
		If omitted, uses the ranking signals configured on the index.""")
	List<Signal> signals,

	/**
	 * A second pass reordering the best results, left out to answer in the
	 * order the ranking gave. Read under the same rule the signals are, and
	 * refused together with {@code hits}.
	 */
	@Schema(description = """
		Reorders the best results in a second pass. See \
		[Rescoring](https://exofind.dev/reference/search-api/#rescoring).""")
	Rescore rescore
) {
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
			after, before, pages, total, signals, null
		);
	}

	/**
	 * How far the total of a search is counted.
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
	 * A request to count the matches per value of one field, for building the
	 * list of filters a user picks from - or into buckets, when {@code ranges}
	 * is given.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Computes match counts for distinct values of a field. The target \
		field must have `facet` enabled in its field definition; otherwise, \
		the request returns `index:query:usage_not_enabled`.""")
	public record Facet(
		/**
		 * What the counts are keyed by in the response. Left out to key them
		 * by the field - only needed when one search counts the same field
		 * twice.
		 */
		@Schema(description = """
			Key used for the facet in the response. Required when faceting \
			on the same field multiple times. Duplicate facet names return \
			`search:facet:duplicate_name`. Defaults to the field name.""")
		String name,

		/**
		 * Name of the field to count, as it is called in the definition of
		 * the index. The field has to be defined for faceting.
		 */
		@Schema(
			description = "Target field to aggregate.",
			required = true,
			examples = "category"
		)
		String field,

		/**
		 * How many values to bring back at most, left out for 10. Capped at
		 * 1000. Does not combine with {@code ranges}.
		 */
		@Schema(
			description = "Maximum number of facet values to return.",
			defaultValue = "10",
			minimum = "1",
			maximum = "1000"
		)
		Integer limit,

		/**
		 * The order values come back in, left out for {@code count}. Does not
		 * combine with {@code ranges}.
		 */
		@Schema(
			description = """
				Sort order of facet values: `"count"` (descending by count) \
				or `"value"` (ascending by value).""",
			defaultValue = "count"
		)
		Order order,

		/**
		 * The buckets to count the matches into instead of per value - what a
		 * price or date facet shows. Being present is what asks for it; the
		 * counts come back one per bucket, in this order.
		 */
		@Schema(description = """
			Array of range bucket definitions. See [Range \
			buckets](https://exofind.dev/reference/search-api/#range-buckets). \
			Cannot be combined with `limit` or `order` \
			(`search:facet:ranges_conflicting`).""")
		List<Range> ranges,

		/**
		 * The level of the tree to count the children of, left out to count
		 * from the top. Only a field whose values are read as paths can
		 * answer it, and the value to send is the `path` of a level a
		 * previous response answered with.
		 */
		@Schema(description = """
			Starting path level for hierarchical fields. See [Counting down \
			a \
			tree](https://exofind.dev/reference/search-api/#counting-down-a-tree). \
			Defaults to the root.""")
		String path,

		/**
		 * How many levels below `path` to count, left out for one. At most
		 * 10, and `limit` and `order` apply per level.
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
		 * The field paths whose filter entries are left out of this facet's
		 * counts - an entry is left out when the path it names equals one of
		 * these or falls under it. Left out for the facet's own field, which
		 * is the sideways rule a filtering UI wants. An empty list leaves
		 * nothing out, so the counts are exactly the results; more paths
		 * widen the scope, for one control backed by several fields.
		 */
		@Schema(description = """
			List of field paths whose filter entries are excluded from this \
			facet's calculation. Defaults to the facet's own field path. An \
			empty array `[]` disables filter exclusion. A blank path returns \
			`search:facet:exclude_filters_invalid`.""")
		List<String> excludeFilters
	) {
		/**
		 * The order the values of a facet come back in.
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
		 * One bucket, holding the values from {@code from} up to but not
		 * including {@code to} - so adjacent buckets sharing a bound count no
		 * value twice. At least one bound has to be given.
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
			 * The lowest value the bucket holds, itself included. Left out
			 * for no lower end.
			 */
			@Schema(
				description = """
					The lowest value the bucket holds, itself included. Omit \
					for no lower end.""",
				examples = "100"
			)
			Object from,

			/**
			 * Where the bucket ends, itself not included. Left out for no
			 * upper end.
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
	 * How numbered pages are asked for.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Requests numbered page metadata. Sending an empty object asks for the \
		defaults. Can be combined with `offset` or page cursors, but not with \
		`after` or `before`.""")
	public record Pages(
		/**
		 * How many page entries the response may hold at most, left out for
		 * a window of nine.
		 */
		@Schema(
			description = "Maximum number of page entries to return.",
			defaultValue = "9"
		)
		Integer max
	) {
	}

	/**
	 * What to highlight. Fragments are built from the text part of the
	 * search - what only narrows the results is never highlighted.
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
		 * The fields to return fragments for, keyed by the name a field has
		 * in the definition of the index. An empty options object asks for
		 * the defaults. A field that was not defined for highlighting is
		 * refused.
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
	 * What to ask about matched values. The values are the ones the `nested`
	 * clauses every result had to satisfy asked for - the same values a sort
	 * or a facet on the field reads. A search that asked nothing of the
	 * values matched all of them.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Requests the matched values of `nested` object fields with each hit. \
		Cannot be combined with `hits` (`search:hits:with_matched`). See \
		[Matched \
		values](https://exofind.dev/reference/search-api/#matched-values).""")
	public record Matched(
		/**
		 * The object fields to answer for, keyed by the name a field has in
		 * the definition of the index. An empty options object asks for the
		 * defaults. A field that is not a `nested` object is refused.
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
	 * What a hit stands for, when it is not a document.
	 *
	 * With a path given, every matched value of that object field is a hit of
	 * its own: the total counts values, facets count value hits, and the
	 * cursors move through values. The query keeps its meaning - clauses on
	 * the fields of the index still say which documents take part, and
	 * `nested` clauses on the path still say which of their values matched.
	 *
	 * With `when` given as well, only the documents satisfying it answer as
	 * their values and every other document answers as itself, so one page
	 * holds both kinds of hit. The total then counts hits, a document that
	 * expanded counting once per value, while the facets count documents.
	 *
	 * A search whose hits are values can not also ask for `matched` or
	 * `highlight`, hold a `knn` clause, or sort by distance or by a field of
	 * the index - it is ordered by score or by fields inside the path. With
	 * `when` given it is ordered by score alone.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Makes each matched value of a `nested` object field a hit of its own \
		instead of a document hit. Totals count matching nested values, facets \
		count value hits, and pagination cursors step through values. With \
		`when`, only the documents it matches expand and the rest stay \
		document hits, totals count hits of both kinds and facets count \
		documents. Cannot be combined with `matched` \
		(`search:hits:with_matched`), `highlight` \
		(`search:hits:with_highlight`) or `knn` clauses \
		(`search:hits:with_knn`). See [What a hit stands \
		for](https://exofind.dev/reference/search-api/#what-a-hit-stands-for).""")
	public record Hits(
		/**
		 * Name of the object field whose matched values are the hits, as it
		 * is called in the definition of the index. The field has to be an
		 * object in `nested` mode.
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
		 * The fields of each value to bring back, named by their dotted
		 * paths, left out for all of them. A name that is not inside the
		 * object, or any name on an index that keeps no copy of its
		 * documents, is refused.
		 */
		@Schema(description = """
			Dotted field paths inside the nested object to return in `value`, \
			defaulting to all of them. Names must be prefixed by `path` \
			(`search:hits:field_not_inside`) and exist in the index \
			(`index:query:field_not_found`). On an index whose `source` is \
			`none`, naming any field returns `index:query:source_not_kept`.""")
		List<String> fields,

		/**
		 * The clauses a document has to satisfy to answer as its values,
		 * left out for all of them. Only `field` and `nested` clauses may
		 * sit here, and none of them may rank.
		 *
		 * A document satisfying these with no matching value under `path`
		 * answers with nothing at all - it is neither expanded nor kept as a
		 * document hit.
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
	 * How the matched values of one field come back.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = "How the matched values of one nested object field come back.")
	public record MatchedField(
		/**
		 * How many values to bring back at most, between 1 and 100. Left out
		 * for three. How many matched in all always comes back beside them.
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
		 * The fields of each value to bring back, named by their dotted
		 * paths, left out for all of them. A name that is not inside the
		 * object, or any name on an index that keeps no copy of its
		 * documents, is refused.
		 */
		@Schema(description = """
			Field paths inside the nested object to include in each returned \
			value, defaulting to all of them. Paths must reside under the \
			target object path (`search:matched:field_not_inside`) and exist \
			in the schema (`index:query:field_not_found`). On an index whose \
			`source` is `none`, naming any field returns \
			`index:query:source_not_kept`.""")
		List<String> fields
	) {
	}

	/**
	 * How to build the fragments of one field.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = "How the fragments of one highlighted field are built.")
	public record HighlightField(
		/**
		 * How many fragments to return at most, left out for three.
		 */
		@Schema(
			description = "Maximum number of fragments to return.",
			defaultValue = "3"
		)
		Integer fragments,

		/**
		 * How long a fragment aims to be in characters, left out for 150.
		 * Text shorter than this comes back as a single fragment holding all
		 * of it.
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
		 * What to put in front of each match, left out for {@code <em>}. May
		 * be empty.
		 */
		@Schema(
			description = """
				Prefix tag inserted before highlighted terms. May be empty.""",
			defaultValue = "<em>"
		)
		String pre,

		/**
		 * What to put after each match, left out for {@code </em>}. May be
		 * empty.
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
