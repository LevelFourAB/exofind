package se.l4.exofind.engine.api.v1alpha1.search;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.api.ExofindApi;
import se.l4.exofind.engine.api.auth.RequiresPermission;
import se.l4.exofind.engine.api.errors.ErrorResponse;
import se.l4.exofind.engine.api.routing.ServedBy;
import se.l4.exofind.engine.api.v1alpha1.search.model.ExplainResponse;
import se.l4.exofind.engine.api.v1alpha1.search.model.FacetValuesRequest;
import se.l4.exofind.engine.api.v1alpha1.search.model.FacetValuesResponse;
import se.l4.exofind.engine.api.v1alpha1.search.model.SearchRequest;
import se.l4.exofind.engine.api.v1alpha1.search.model.SearchResponse;
import se.l4.exofind.engine.api.v1alpha1.search.model.SuggestRequest;
import se.l4.exofind.engine.api.v1alpha1.search.model.SuggestResponse;
import se.l4.exofind.engine.auth.Permission;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.index.IndexException;
import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.SearchDeadline;
import se.l4.exofind.engine.index.SearchTimeoutException;
import se.l4.exofind.engine.index.settings.SearchSettings;
import se.l4.exofind.engine.metrics.Meters;
import se.l4.exofind.engine.metrics.RequestMetrics;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchExplanation;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.SuggestResult;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * Searching the indexes on this node.
 *
 * <p>A search runs on whichever node receives it, using the data that the node
 * has pulled. Requests are not forwarded to an index writer.
 *
 * <p>Offset pagination depth is capped because skipping results costs as much
 * as ranking them. Following `next`/`previous` cursors avoids depth caps
 * because those cursors carry the hit where a window ended rather than a count,
 * costing the same at any depth.
 *
 * <p>Searches with rescoring are an exception. Inside the rescore window, a
 * second pass reorders results and cursors count results instead. The cursor
 * moving past the window carries a hit position again.
 *
 * <p>The values of one facet field that start with a prefix are answered
 * beside the search, under {@code /facets/{field}/values}, counted under the
 * same query and filters a search would count them under.
 */
@Tag(
	name = "Search",
	description = "Finds documents in an index.",
	externalDocs = @ExternalDocumentation(
		description = "Search API reference",
		url = "https://exofind.dev/reference/search-api/"
	)
)
@SecurityRequirement(name = ExofindApi.API_KEY)
@Path("/v1alpha1/indexes/{name}")
@Produces(MediaType.APPLICATION_JSON)
public class SearchResource {
	private static final ErrorType IO_ERROR = ErrorType.withCode("index:io_error")
		.withArguments("index")
		.withMessage("The index `{{index}}` could not be searched");

	private final Indexes indexes;
	private final SearchSettings searchSettings;
	private final RequestMetrics metrics;
	private final SearchLimits limits;
	private final Duration timeout;
	private final Duration suggestTimeout;

	@Inject
	public SearchResource(
		Indexes indexes,
		SearchSettings searchSettings,
		RequestMetrics metrics,
		@ConfigProperty(
			name = "exofind.search.max-limit",
			defaultValue = SearchLimits.DEFAULT_MAX_LIMIT
		)
		int maxLimit,
		@ConfigProperty(
			name = "exofind.search.max-page-depth",
			defaultValue = SearchLimits.DEFAULT_MAX_PAGE_DEPTH
		)
		int maxPageDepth,
		@ConfigProperty(
			name = "exofind.search.max-rescore-window",
			defaultValue = SearchLimits.DEFAULT_MAX_RESCORE_WINDOW
		)
		int maxRescoreWindow,
		@ConfigProperty(
			name = "exofind.search.max-knn-k",
			defaultValue = SearchLimits.DEFAULT_MAX_KNN_K
		)
		int maxKnnK,
		@ConfigProperty(
			name = "exofind.search.max-fuse-depth",
			defaultValue = SearchLimits.DEFAULT_MAX_FUSE_DEPTH
		)
		int maxFuseDepth,
		@ConfigProperty(
			name = "exofind.search.max-clauses",
			defaultValue = SearchLimits.DEFAULT_MAX_CLAUSES
		)
		int maxClauses,
		@ConfigProperty(
			name = "exofind.search.max-clause-depth",
			defaultValue = SearchLimits.DEFAULT_MAX_CLAUSE_DEPTH
		)
		int maxClauseDepth,
		@ConfigProperty(name = "exofind.search.timeout", defaultValue = "30s")
		Duration timeout,
		@ConfigProperty(name = "exofind.suggest.timeout", defaultValue = "2s")
		Duration suggestTimeout
	) {
		this(
			indexes,
			searchSettings,
			metrics,
			new SearchLimits(
				maxLimit,
				maxPageDepth,
				maxRescoreWindow,
				maxKnnK,
				maxFuseDepth,
				maxClauses,
				maxClauseDepth
			),
			timeout,
			suggestTimeout
		);
	}

	/**
	 * Create a resource with its limits given directly, the way a test does,
	 * instead of reading them from the configuration. A suggest request runs
	 * under the same budget as a search.
	 *
	 * @param limits
	 *   how much of the node one search may ask for
	 * @param timeout
	 *   how long a search may collect for. {@code null}, zero and negative
	 *   durations let a search run until it finishes
	 */
	public SearchResource(
		Indexes indexes,
		SearchSettings searchSettings,
		RequestMetrics metrics,
		SearchLimits limits,
		Duration timeout
	) {
		this(indexes, searchSettings, metrics, limits, timeout, timeout);
	}

	/**
	 * Create a resource with its limits given directly, the way a test does,
	 * instead of reading them from the configuration.
	 *
	 * @param limits
	 *   how much of the node one search may ask for
	 * @param timeout
	 *   how long a search may collect for. {@code null}, zero and negative
	 *   durations let a search run until it finishes
	 * @param suggestTimeout
	 *   how long a suggest request may count for, bounded the same way
	 */
	public SearchResource(
		Indexes indexes,
		SearchSettings searchSettings,
		RequestMetrics metrics,
		SearchLimits limits,
		Duration timeout,
		Duration suggestTimeout
	) {
		this.indexes = indexes;
		this.searchSettings = searchSettings;
		this.metrics = metrics;
		this.limits = limits;
		this.timeout = timeout;
		this.suggestTimeout = suggestTimeout;
	}

	/**
	 * Search an index.
	 *
	 * @param body
	 *   what to search for; all properties are optional, and omitting the body
	 *   matches all documents
	 */
	@POST
	@Path("/search")
	@Consumes(MediaType.APPLICATION_JSON)
	@RequiresPermission(Permission.SEARCH)
	@ServedBy(ServedBy.Node.ANY_NODE)
	@Operation(
		operationId = "search",
		summary = "Search an index",
		description = """
			Executes a search query against an index on the node that \
			receives the request. A node that does not index the target \
			answers from the generation it last pulled, so a recently \
			indexed document may not appear yet.

			Requires the `search` permission."""
	)
	@APIResponse(
		responseCode = "200",
		description = "The matching documents, in the order that `sort` asks for.",
		content = @Content(
			schema = @Schema(implementation = SearchResponse.class),
			examples = @ExampleObject(
				name = "results",
				summary = "The answer to the example request",
				value = SearchResponse.EXAMPLE
			)
		)
	)
	@APIResponse(
		responseCode = "400",
		description = """
			The request is not a valid search. The `code` property names the \
			reason, such as `search:filter:scores` when a filter clause \
			affects the score, `index:query:usage_not_enabled` when a field is \
			not configured for the requested usage, or `search:page:too_deep` \
			when `offset` reaches past `EXOFIND_SEARCH_MAX_PAGE_DEPTH`.

			A request that asks for more than the node allows is refused with \
			the same status. The `code` names the cap it exceeded: \
			`search:limit:too_large`, `search:query:too_many_clauses`, \
			`search:query:too_deep`, `search:clause:k_too_large`, or \
			`search:clause:depth_too_large`. See \
			[Search configuration](https://exofind.dev/reference/configuration/#search).""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "401",
		description = """
			The request carries no credential this node accepts. Absent, \
			malformed, unknown, and lapsed keys all return this status, so a \
			refusal cannot be used to find out which keys exist. The response \
			carries `WWW-Authenticate: Bearer`.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The API key does not have the `search` permission.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "404",
		description = """
			The index does not exist, or the key has no permissions on it. An \
			index on which a key has no permissions returns this status as \
			though it did not exist.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = """
			The index currently has no live generation \
			(`index:no_live_generation`). Promote a generation and send the \
			request again.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "503",
		description = """
			The request raced the index being closed to free local resources \
			(`index:closed`). Sending the same request again reopens it.

			Also returned when the search collected for longer than \
			`EXOFIND_SEARCH_TIMEOUT` (`search:timeout`). The results collected \
			before the node stopped are dropped, so narrow the search instead \
			of repeating it.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	public SearchResponse search(
		@Parameter(
			description = """
				Name of the index to search. To search one generation, add \
				`@` and the name of the generation, such as `books@2`.""",
			example = "books"
		)
		@PathParam("name") String name,
		@RequestBody(content = @Content(
			schema = @Schema(implementation = SearchRequest.class),
			examples = @ExampleObject(
				name = "search",
				summary = "Text and a filter, counted by category",
				value = SearchRequest.EXAMPLE
			)
		))
		SearchRequest body
	) {
		var started = System.nanoTime();

		var index = indexes.getOrThrow(name);
		var mapped = SearchRequestMapper.toEngine(body, limits);

		/*
		 * Settings belong to the index name, so a search naming one generation
		 * runs with the same settings the bare name does.
		 */
		var settings = searchSettings.get(IndexName.parse(name).index()).orElse(null);

		SearchResult result;
		boolean timedOut;
		try(var budget = SearchDeadline.start(timeout)) {
			result = index.search(mapped.request(), settings);
			timedOut = budget.exceeded();
		} catch(IOException e) {
			metrics.recordSearch(name, System.nanoTime() - started, false);
			throw new IndexException(IO_ERROR, e, "index", name);
		} catch(RuntimeException e) {
			metrics.recordSearch(name, System.nanoTime() - started, false);
			throw e;
		}

		/*
		 * A search that ran out of time returns what it collected before it
		 * stopped, and that page reads like a complete one. Refused here
		 * instead of answered.
		 */
		if(timedOut) {
			metrics.recordSearch(name, System.nanoTime() - started, false);
			throw new SearchTimeoutException(name, timeout);
		}

		var took = System.nanoTime() - started;
		metrics.recordSearch(name, took, true);

		if(result.relaxed() != null) {
			for(var dropped : result.relaxed().dropped()) {
				metrics.recordRelaxation(dropped.reason().name());
			}
		}

		if(result.interpreted() != null) {
			for(var filter : result.interpreted().filters()) {
				metrics.recordInterpretation(switch(filter.kind()) {
					case NUMBER -> Meters.KIND_NUMBER;
					case VALUE -> Meters.KIND_VALUE;
				});
			}
		}

		/*
		 * Kept to microseconds rather than the nanoseconds measured, so that
		 * the number reads as a time and not as the last digits of how a
		 * double divides.
		 */
		var tookMs = Math.round(took / 1_000d) / 1_000d;
		return toResponse(mapped, result, tookMs);
	}

	/**
	 * Count the values of one facet field that start with a prefix, under a
	 * search.
	 *
	 * @param body
	 *   what to count under and what the values start with; all properties
	 *   are optional, and omitting the body counts every value of the field
	 *   under everything the index holds
	 */
	@POST
	@Path("/facets/{field}/values")
	@Consumes(MediaType.APPLICATION_JSON)
	@RequiresPermission(Permission.SEARCH)
	@ServedBy(ServedBy.Node.ANY_NODE)
	@Operation(
		operationId = "searchFacetValues",
		summary = "Search the values of a facet",
		description = """
			Answers the values of one facet field that start with a prefix, \
			each with how many documents hold it under the given query and \
			filters. A filter panel asks for this while a value is typed into \
			it, to reach the values a facet of a search cut off at its limit. \
			The counts are the ones a facet of the same search answers: filter \
			entries on the facet's own field are left out, and the query and \
			every other filter narrow them.

			The prefix and the values of a string field are compared folded, \
			in case and Unicode form, so `rö` finds `Röd`. A number, boolean \
			or timestamp field compares the prefix with the value as a search \
			response shows it, ignoring case. A field whose values are paths \
			through a tree refuses a prefix. See [Searching the values of a \
			facet](https://exofind.dev/reference/search-api/#searching-the-values-of-a-facet).

			Requires the `search` permission."""
	)
	@APIResponse(
		responseCode = "200",
		description = "The values that start with the prefix, in the order that `order` asks for.",
		content = @Content(
			schema = @Schema(implementation = FacetValuesResponse.class),
			examples = @ExampleObject(
				name = "values",
				summary = "The answer to the example request",
				value = FacetValuesResponse.EXAMPLE
			)
		)
	)
	@APIResponse(
		responseCode = "400",
		description = """
			The request is not one that can be counted. The `code` property \
			names the reason, such as `index:query:field_not_found` when the \
			field does not exist, `index:query:usage_not_enabled` when it is \
			not defined for `facet`, `index:query:facet_prefix_on_a_tree` \
			when its values are paths through a tree, \
			`search:facet:limit_invalid` when `limit` is outside 1 to 1000, \
			or `search:filter:scores` when a filter clause affects the score.

			A request that asks for more than the node allows is refused with \
			the same status: `search:query:too_many_clauses`, \
			`search:query:too_deep`, `search:clause:k_too_large`, or \
			`search:clause:depth_too_large`. See \
			[Search configuration](https://exofind.dev/reference/configuration/#search).""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "401",
		description = """
			The request carries no credential this node accepts. Absent, \
			malformed, unknown, and lapsed keys all return this status, so a \
			refusal cannot be used to find out which keys exist. The response \
			carries `WWW-Authenticate: Bearer`.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The API key does not have the `search` permission.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "404",
		description = """
			The index does not exist, or the key has no permissions on it. An \
			index on which a key has no permissions returns this status as \
			though it did not exist.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = """
			The index currently has no live generation \
			(`index:no_live_generation`). Promote a generation and send the \
			request again.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "503",
		description = """
			The request raced the index being closed to free local resources \
			(`index:closed`). Sending the same request again reopens it.

			Also returned when counting collected for longer than \
			`EXOFIND_SEARCH_TIMEOUT` (`search:timeout`). The counts collected \
			before the node stopped are dropped, so narrow the search instead \
			of repeating it.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	public FacetValuesResponse facetValues(
		@Parameter(
			description = """
				Name of the index. To count one generation, add `@` and the \
				name of the generation, such as `books@2`.""",
			example = "books"
		)
		@PathParam("name") String name,
		@Parameter(
			description = """
				The field whose values to answer, as declared in the index \
				definition. The field must have `facet` enabled.""",
			example = "brand"
		)
		@PathParam("field") String field,
		@RequestBody(content = @Content(
			schema = @Schema(implementation = FacetValuesRequest.class),
			examples = @ExampleObject(
				name = "values",
				summary = "Brands starting with `adi` among running shoes",
				value = FacetValuesRequest.EXAMPLE
			)
		))
		FacetValuesRequest body
	) {
		var started = System.nanoTime();

		var index = indexes.getOrThrow(name);
		var request = FacetValuesRequestMapper.toEngine(field, body, limits);

		// Settings belong to the index name, see search
		var settings = searchSettings.get(IndexName.parse(name).index()).orElse(null);

		SearchResult result;
		boolean timedOut;
		try(var budget = SearchDeadline.start(timeout)) {
			result = index.search(request, settings);
			timedOut = budget.exceeded();
		} catch(IOException e) {
			throw new IndexException(IO_ERROR, e, "index", name);
		}

		// Counts collected over a spent budget describe part of the index, see search
		if(timedOut) {
			throw new SearchTimeoutException(name, timeout);
		}

		var took = System.nanoTime() - started;
		var counts = result.facets().get(field);

		return new FacetValuesResponse(
			toFacetValuesJson(counts.values()),
			counts.totalValues(),
			Math.round(took / 1_000d) / 1_000d
		);
	}

	/**
	 * Suggest what to search for, from the text typed so far.
	 *
	 * @param body
	 *   what has been typed and what to count under; all properties are
	 *   optional, and omitting the body answers the most common values
	 */
	@POST
	@Path("/suggest")
	@Consumes(MediaType.APPLICATION_JSON)
	@RequiresPermission(Permission.SEARCH)
	@ServedBy(ServedBy.Node.ANY_NODE)
	@Operation(
		operationId = "suggest",
		summary = "Suggest what to search for",
		description = """
			Answers what to search for, from the text typed into a search box \
			so far. A search box asks for this on every keystroke, and shows \
			the answer as a list to pick from.

			The suggestions are the values of the fields the search settings \
			of the index opt in with `suggest` (see [Field \
			settings](https://exofind.dev/reference/admin-api/#field-settings)), \
			that start with the text, each with how many documents hold it \
			under the given filters. An index whose settings suggest no field \
			answers an empty list. Each suggestion says how many characters \
			of it were typed, so a search box can mark the part that \
			completes the text.

			Matching rules:

			- **Folding**: The text and the values of a field are compared \
			folded in case and Unicode form, by the `normalize` step of the \
			field's `autocomplete` analyzer chain, or of the chain the engine \
			builds for `autocomplete` when the field declares none. `rö` \
			finds `Röd`. Words are not stemmed, so `shoes` does not find \
			`Shoe`.
			- **Declared labels**: The text is also compared with the label \
			the search settings declare for a value in the locale of the \
			request, so `rö` suggests the value `red` labelled `Röd` in \
			Swedish. A declared value no document holds is never suggested.
			- **Whole-value prefix**: The comparison is against the start of \
			the whole value, not of each word: `air` does not find \
			`Nike Air Max`.
			- **Ordering**: The most common values first; ties by field name, \
			then by value.
			- **Typo tolerance**: When fewer values than `limit` start with \
			a text of at least five characters and `typos` is `auto`, values \
			one mistake away from the text - a character inserted, dropped, \
			replaced, or two adjacent ones swapped - are suggested after \
			them, marked `corrected`, with `typed` at `0`. The first \
			character of the text is never read as a mistake.
			- **Counts**: The counts are the ones a facet of a search under \
			the same filters answers. A filter on a suggested field is left \
			out of that field's own counts, so a brand already ticked keeps \
			the other brands suggestable.

			A filter panel that completes the values of one facet uses \
			`POST /v1alpha1/indexes/{name}/facets/{field}/values` instead. \
			See [Suggesting what to search \
			for](https://exofind.dev/reference/search-api/#suggesting-what-to-search-for).

			Requires the `search` permission."""
	)
	@APIResponse(
		responseCode = "200",
		description = "The suggestions, the most common first.",
		content = @Content(
			schema = @Schema(implementation = SuggestResponse.class),
			examples = @ExampleObject(
				name = "suggestions",
				summary = "The answer to the example request",
				value = SuggestResponse.EXAMPLE
			)
		)
	)
	@APIResponse(
		responseCode = "400",
		description = """
			The request is not one that can be answered. The `code` property \
			names the reason, such as `search:suggest:limit_invalid` when \
			`limit` is outside 1 to 100, `search:locale:unsupported` when the \
			locale is one the node has no rules for, \
			`index:query:field_not_found` when a filter names a field the \
			index does not have, or `search:filter:scores` when a filter \
			clause affects the score.

			A request that asks for more than the node allows is refused with \
			the same status: `search:query:too_many_clauses`, \
			`search:query:too_deep`, `search:clause:k_too_large`, or \
			`search:clause:depth_too_large`. See \
			[Search configuration](https://exofind.dev/reference/configuration/#search).""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "401",
		description = """
			The request carries no credential this node accepts. Absent, \
			malformed, unknown, and lapsed keys all return this status, so a \
			refusal cannot be used to find out which keys exist. The response \
			carries `WWW-Authenticate: Bearer`.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The API key does not have the `search` permission.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "404",
		description = """
			The index does not exist, or the key has no permissions on it. An \
			index on which a key has no permissions returns this status as \
			though it did not exist.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = """
			The index currently has no live generation \
			(`index:no_live_generation`). Promote a generation and send the \
			request again.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "503",
		description = """
			The request raced the index being closed to free local resources \
			(`index:closed`). Sending the same request again reopens it.

			Also returned when counting collected for longer than \
			`EXOFIND_SUGGEST_TIMEOUT` (`search:timeout`). The counts collected \
			before the node stopped are dropped, so narrow the filters instead \
			of repeating the request.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	public SuggestResponse suggest(
		@Parameter(
			description = """
				Name of the index. To suggest from one generation, add `@` \
				and the name of the generation, such as `books@2`.""",
			example = "books"
		)
		@PathParam("name") String name,
		@RequestBody(content = @Content(
			schema = @Schema(implementation = SuggestRequest.class),
			examples = @ExampleObject(
				name = "suggestions",
				summary = "What to search for among shoes, from `adi`",
				value = SuggestRequest.EXAMPLE
			)
		))
		SuggestRequest body
	) {
		var started = System.nanoTime();

		var index = indexes.getOrThrow(name);
		var request = SuggestRequestMapper.toEngine(body, limits);

		// Settings belong to the index name, see search
		var settings = searchSettings.get(IndexName.parse(name).index()).orElse(null);

		SuggestResult result;
		boolean timedOut;
		try(var budget = SearchDeadline.start(suggestTimeout)) {
			result = index.suggest(request, settings);
			timedOut = budget.exceeded();
		} catch(IOException e) {
			metrics.recordSuggest(name, System.nanoTime() - started, false);
			throw new IndexException(IO_ERROR, e, "index", name);
		} catch(RuntimeException e) {
			metrics.recordSuggest(name, System.nanoTime() - started, false);
			throw e;
		}

		// Counts collected over a spent budget describe part of the index, see search
		if(timedOut) {
			metrics.recordSuggest(name, System.nanoTime() - started, false);
			throw new SearchTimeoutException(name, suggestTimeout);
		}

		var took = System.nanoTime() - started;
		metrics.recordSuggest(name, took, true);

		var suggestions = new ArrayList<SuggestResponse.Suggestion>(result.suggestions().size());
		for(var suggestion : result.suggestions()) {
			suggestions.add(new SuggestResponse.Suggestion(
				suggestion.text(),
				suggestion.typed(),
				suggestion.corrected() ? Boolean.TRUE : null,
				suggestion.field(),
				suggestion.value(),
				suggestion.label(),
				suggestion.count()
			));
		}

		return new SuggestResponse(suggestions, Math.round(took / 1_000d) / 1_000d);
	}

	/**
	 * Explain how one hit scores under a search.
	 *
	 * @param name
	 * @param key
	 *   primary key of the document
	 * @param valueIndex
	 *   which value of the request's {@code hits} path to explain
	 * @param body
	 *   the search to explain the hit under, the same body a search takes
	 */
	@POST
	@Path("/search/actions/explain")
	@Consumes(MediaType.APPLICATION_JSON)
	@RequiresPermission(Permission.SEARCH)
	@ServedBy(ServedBy.Node.ANY_NODE)
	@Operation(
		operationId = "explainSearch",
		summary = "Explain how one hit scores",
		description = """
			Reports how one hit scores under a search, as a tree of steps \
			naming the clauses of the request they were compiled from and the \
			fields of the index definition they read.

			The body is a search request; `limit`, `offset`, `after`, \
			`before`, `sort`, `facets`, `highlight` and `matched` are ignored. \
			A hit that the search does not match is reported with `matched` \
			set to `false` rather than refused.

			Requires the `search` permission."""
	)
	@APIResponse(
		responseCode = "200",
		description = "How the hit scores.",
		content = @Content(
			schema = @Schema(implementation = ExplainResponse.class),
			examples = @ExampleObject(name = "explanation", value = ExplainResponse.EXAMPLE)
		)
	)
	@APIResponse(
		responseCode = "400",
		description = """
			The request is not a valid search, or the index declares no primary \
			key so a hit cannot be named (`index:no_primary_key`).""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "401",
		description = """
			The request carries no credential this node accepts. The response \
			carries `WWW-Authenticate: Bearer`.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The API key does not have the `search` permission.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "404",
		description = """
			The index does not exist, or the key has no permissions on it. \
			Also returned when no document exists under `key` \
			(`index:explain:document_not_found`), or that document has no \
			value along the `hits` path at `index` \
			(`index:explain:value_not_found`).""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = """
			The index currently has no live generation \
			(`index:no_live_generation`).""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "503",
		description = """
			The request raced the index being closed to free local resources \
			(`index:closed`), or the search behind the explanation collected \
			for longer than `EXOFIND_SEARCH_TIMEOUT` (`search:timeout`).""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	public ExplainResponse explain(
		@Parameter(
			description = """
				Name of the index to search. To explain against one \
				generation, add `@` and the name of the generation, such as \
				`books@2`.""",
			example = "books"
		)
		@PathParam("name") String name,
		@Parameter(
			description = """
				Primary key of the document, read as the type of the key \
				field. For a search whose `hits` names an object field, \
				provide the key of the document holding the value, which is \
				what such a hit reports as its `id`.""",
			example = "9781234567890",
			required = true
		)
		@QueryParam("key") String key,
		@Parameter(
			description = """
				Position of the value to explain among the document's values \
				of the `hits` path, which is what such a hit reports as its \
				`index`. Read only by a search whose hits are values.""",
			example = "0"
		)
		@QueryParam("index") @DefaultValue("0") int valueIndex,
		@RequestBody(content = @Content(
			schema = @Schema(implementation = SearchRequest.class),
			examples = @ExampleObject(
				name = "search",
				summary = "The search to explain the hit against",
				value = SearchRequest.EXAMPLE
			)
		))
		SearchRequest body
	) {
		var index = indexes.getOrThrow(name);
		var mapped = SearchRequestMapper.toEngine(body, limits);

		// Settings belong to the index name, the way they do for a search
		var settings = searchSettings.get(IndexName.parse(name).index()).orElse(null);

		SearchExplanation explanation;
		boolean timedOut;
		try(var budget = SearchDeadline.start(timeout)) {
			explanation = index.explain(
				mapped.request(),
				index.parsePrimaryKey(key),
				valueIndex,
				settings
			);
			timedOut = budget.exceeded();
		} catch(IOException e) {
			throw new IndexException(IO_ERROR, e, "index", name);
		}

		if(timedOut) {
			throw new SearchTimeoutException(name, timeout);
		}

		return new ExplainResponse(
			explanation.matched(),
			explanation.score(),
			toDetailJson(explanation.detail()),
			toRelaxedJson(explanation.relaxed()),
			toInterpretedJson(explanation.interpreted())
		);
	}

	private static ExplainResponse.Detail toDetailJson(SearchExplanation.Detail detail) {
		var children = new ArrayList<ExplainResponse.Detail>(detail.children().size());
		for(var child : detail.children()) {
			children.add(toDetailJson(child));
		}

		return new ExplainResponse.Detail(
			detail.matched(),
			detail.score(),
			detail.description(),
			detail.clause(),
			detail.clauseType(),
			detail.field(),
			detail.usage(),
			detail.locale(),
			children
		);
	}

	private SearchResponse toResponse(
		SearchRequestMapper.Mapped mapped,
		SearchResult result,
		double tookMs
	) {
		/*
		 * A search made only of filters computes no scores, and a score is
		 * then left out of every hit rather than defaulted to something that
		 * looks like a value.
		 */
		var scores = mapped.request().query().anySatisfy(Query::scores);
		var highlighting = mapped.request().highlight().notEmpty();
		var matching = mapped.request().matched().notEmpty();

		var hits = new ArrayList<SearchResponse.Hit>(result.hits().size());
		for(var hit : result.hits()) {
			hits.add(
				new SearchResponse.Hit(
					hit.id(),
					hit.index(),
					hit.valueKey(),
					scores ? hit.score() : null,
					hit.value(),
					hit.document(),
					/*
					 * Present whenever highlighting was asked for, so a caller
					 * can count on the key - possibly empty, when nothing in
					 * the hit matched the text of the search.
					 */
					highlighting ? toHighlightsJson(hit) : null,
					matching ? toMatchedJson(hit) : null
				)
			);
		}

		return new SearchResponse(
			hits,
			new SearchResponse.Total(result.total().count(), result.total().exact()),
			result.documents() == null
				? null
				: new SearchResponse.Total(
					result.documents().count(),
					result.documents().exact()
				),
			toFacetsJson(mapped.request(), result),
			toPage(mapped, result),
			toRelaxedJson(result.relaxed()),
			toInterpretedJson(result.interpreted()),
			tookMs
		);
	}

	/**
	 * Shape what the search read out of its text, or {@code null} when it
	 * read nothing - the key is only there when the results answer a filter
	 * nobody wrote.
	 */
	private static SearchResponse.Interpreted toInterpretedJson(
		SearchResult.Interpreted interpreted
	) {
		if(interpreted == null) {
			return null;
		}

		var filters = new ArrayList<SearchResponse.Interpreted.Filter>(
			interpreted.filters().size()
		);

		for(var filter : interpreted.filters()) {
			var when = filter.when().isEmpty()
				? null
				: filter.when().collect(SearchRequestMapper::toClauseJson).toList();
			var fallback = filter.fallback().isEmpty()
				? null
				: SearchRequestMapper.toTargetsJson(filter.fallback());

			filters.add(
				new SearchResponse.Interpreted.Filter(
					filter.field(),
					when,
					SearchRequestMapper.toMatcherJson(filter.matcher()),
					filter.words().toList(),
					fallback
				)
			);
		}

		return new SearchResponse.Interpreted(filters, interpreted.text());
	}

	/**
	 * Shape what the search let go of, or {@code null} when it let go of
	 * nothing - the key is only there when the results answer less than what
	 * was asked for.
	 */
	private static SearchResponse.Relaxed toRelaxedJson(SearchResult.Relaxed relaxed) {
		if(relaxed == null) {
			return null;
		}

		var dropped = new ArrayList<SearchResponse.Relaxed.Dropped>(
			relaxed.dropped().size()
		);

		for(var word : relaxed.dropped()) {
			dropped.add(
				new SearchResponse.Relaxed.Dropped(
					word.word(),
					switch(word.reason()) {
						case UNMATCHED -> SearchResponse.Relaxed.Dropped.Reason.UNMATCHED;
						case COMMON -> SearchResponse.Relaxed.Dropped.Reason.COMMON;
					}
				)
			);
		}

		return new SearchResponse.Relaxed(dropped, relaxed.text());
	}

	/**
	 * Shape the facet counts, keyed by name in the order the request asked
	 * for them - or {@code null} when the request asked for none, so the key
	 * is only present when it was asked about.
	 */
	private static Map<String, SearchResponse.Facet> toFacetsJson(
		se.l4.exofind.engine.query.SearchRequest request,
		SearchResult result
	) {
		if(request.facets().isEmpty()) {
			return null;
		}

		var facets = new LinkedHashMap<String, SearchResponse.Facet>();
		for(var facet : request.facets()) {
			var counts = result.facets().get(facet.name());

			if(facet.ranges().isEmpty()) {
				facets.put(
					facet.name(),
					new SearchResponse.Facet(
						toFacetValuesJson(counts.values()),
						counts.totalValues(),
						null
					)
				);
			} else {
				var buckets = new ArrayList<SearchResponse.FacetBucket>(counts.buckets().size());
				for(var bucket : counts.buckets()) {
					buckets.add(
						new SearchResponse.FacetBucket(bucket.from(), bucket.to(), bucket.count())
					);
				}

				facets.put(facet.name(), new SearchResponse.Facet(null, null, buckets));
			}
		}

		return facets;
	}

	/**
	 * Shape the counts per value, keeping the levels of a tree nested the way
	 * the counting answered them. A value that is not part of a tree carries
	 * neither a path nor levels below it, and leaves both out of the response.
	 */
	private static List<SearchResponse.FacetValue> toFacetValuesJson(
		ListIterable<SearchResult.Facet.Value> values
	) {
		var result = new ArrayList<SearchResponse.FacetValue>(values.size());
		for(var value : values) {
			result.add(new SearchResponse.FacetValue(
				value.value(),
				value.count(),
				value.label(),
				value.path(),
				value.path() == null ? null : toFacetValuesJson(value.children()),
				value.path() == null ? null : value.totalChildren()
			));
		}

		return result;
	}

	private static Map<String, List<String>> toHighlightsJson(SearchResult.Hit hit) {
		var result = new LinkedHashMap<String, List<String>>();
		hit.highlights().forEachKeyValue(
			(field, fragments) -> result.put(field, fragments.castToList())
		);

		return result;
	}

	private static Map<String, SearchResponse.MatchedValues> toMatchedJson(SearchResult.Hit hit) {
		var result = new LinkedHashMap<String, SearchResponse.MatchedValues>();
		hit.matched().forEachKeyValue(
			(field, matched) -> result.put(
				field,
				new SearchResponse.MatchedValues(
					matched.values() == null ? null : matched.values().castToList(),
					matched.totalValues()
				)
			)
		);

		return result;
	}

	private SearchResponse.Page toPage(
		SearchRequestMapper.Mapped mapped,
		SearchResult result
	) {
		var request = mapped.request();
		var limit = request.limit();
		var fingerprint = mapped.fingerprint();

		/*
		 * A window reached through a keyset cursor sits after or before a
		 * hit rather than at a count, so there is no offset to report - and
		 * no numbered pages to build from one.
		 */
		var keyset = request.after() != null || request.before() != null;
		var offset = keyset ? null : (Integer) request.offset();

		/*
		 * `next` and `previous` continue past the hits of this window, so
		 * they are keyset cursors - which is what lets them keep going where
		 * offsets are capped. Whether there is anything to continue to is
		 * known exactly when the offset is; a window continued from a keyset
		 * cursor only knows a full window may have more, and a cursor that
		 * turns out to point past the end answers an empty window rather
		 * than being wrong to hand out.
		 */
		/*
		 * A second pass reorders the window it covers, and no key names a
		 * position in that order - so inside it the two cursors count results
		 * instead. The one continuing past the window is a key again: it is the
		 * position the first pass left off at, which is where the results below
		 * the window carry on from.
		 */
		var rescore = request.rescore();

		String previous = null;
		String next = null;

		if(limit > 0) {
			var hits = result.hits();

			if(hits.notEmpty()) {
				var first = new SearchCursor.Keyset(fingerprint, hits.getFirst().key()).encode();
				var last = new SearchCursor.Keyset(fingerprint, hits.getLast().key()).encode();
				var full = hits.size() == limit;

				if(request.after() != null) {
					previous = first;
					next = full ? last : null;
				} else if(request.before() != null) {
					previous = full ? first : null;
					next = last;
				} else if(rescore != null) {
					if(request.offset() > 0) {
						previous = new SearchCursor.Offset(
							fingerprint,
							Math.max(0, request.offset() - limit)
						).encode();
					}

					var nextOffset = (long) request.offset() + limit;
					if(nextOffset >= rescore.window()) {
						next = result.windowEnd() == null
							? null
							: new SearchCursor.Keyset(fingerprint, result.windowEnd()).encode();
					} else if(nextOffset < result.total().count()) {
						next = new SearchCursor.Offset(fingerprint, (int) nextOffset).encode();
					}
				} else {
					if(request.offset() > 0) {
						previous = first;
					}

					var nextOffset = (long) request.offset() + limit;
					if(nextOffset < result.total().count()) {
						next = last;
					}
				}
			} else if(!keyset && request.offset() > 0) {
				// A window past the end has no hit to key on, but still points back
				previous = new SearchCursor.Offset(
					fingerprint,
					Math.max(0, request.offset() - limit)
				).encode();
			}
		}

		/*
		 * Numbered pages stop where the second pass does. A page past the
		 * window is refused rather than answered in another order, so offering
		 * its number would offer an error.
		 */
		var numbered = rescore == null
			? result.total().count()
			: Math.min(result.total().count(), rescore.window());

		var pages = mapped.pagesMax() != null
			? toPages(fingerprint, limit, request.offset(), numbered, mapped.pagesMax())
			: null;

		return new SearchResponse.Page(limit, offset, previous, next, pages);
	}

	/**
	 * Number the pages, windowed so a pager renders {@code 1 2 3 … 7} - up to
	 * {@code max} entries split over the start of the list, the pages around
	 * the current one and the end.
	 */
	private SearchResponse.Pages toPages(
		int fingerprint,
		int limit,
		int offset,
		long totalCount,
		int max
	) {
		var count = (totalCount + limit - 1) / limit;
		var current = offset / limit + 1;

		/*
		 * The deepest page whose window still fits under the paging cap.
		 * Pages past it would be refused if asked for, so they are never
		 * offered - which is why the end of the list can be missing.
		 */
		var lastReachable = limits.maxPageDepth() / limit;

		if(count == 0) {
			return new SearchResponse.Pages(0, null, null, List.of(), List.of(), List.of());
		}

		long startLo = 1, startHi;
		long midLo = 0, midHi = -1;
		long endLo = 0, endHi = -1;

		if(count <= max) {
			startHi = count;
		} else {
			var window = max - 2;
			var lo = current - (window / 2);
			var hi = lo + window - 1;

			if(lo <= 2) {
				/*
				 * The window touches the start of the list, so the two run
				 * together instead of being separated by an ellipsis that
				 * would hide nothing.
				 */
				startHi = max - 1;
				endLo = endHi = count;
			} else if(hi >= count - 1) {
				startHi = 1;
				endLo = count - window;
				endHi = count;
			} else {
				startHi = 1;
				midLo = lo;
				midHi = hi;
				endLo = endHi = count;
			}
		}

		var previous = current > 1 && current - 1 <= count
			? pageRef(fingerprint, limit, current - 1, current)
			: null;
		var next = current < count && current + 1 <= lastReachable
			? pageRef(fingerprint, limit, current + 1, current)
			: null;

		var end = count <= lastReachable
			? pageRefs(fingerprint, limit, endLo, endHi, current, lastReachable)
			: null;

		return new SearchResponse.Pages(
			count,
			previous,
			next,
			pageRefs(fingerprint, limit, startLo, startHi, current, lastReachable),
			pageRefs(fingerprint, limit, midLo, midHi, current, lastReachable),
			end
		);
	}

	private static List<SearchResponse.PageRef> pageRefs(
		int fingerprint,
		int limit,
		long lo,
		long hi,
		long current,
		long lastReachable
	) {
		var refs = new ArrayList<SearchResponse.PageRef>();
		for(var number = lo; number <= hi && number <= lastReachable; number++) {
			refs.add(pageRef(fingerprint, limit, number, current));
		}

		return refs;
	}

	private static SearchResponse.PageRef pageRef(
		int fingerprint,
		int limit,
		long number,
		long current
	) {
		return new SearchResponse.PageRef(
			number,
			new SearchCursor.Offset(fingerprint, (int) ((number - 1) * limit)).encode(),
			number == current ? true : null
		);
	}
}
