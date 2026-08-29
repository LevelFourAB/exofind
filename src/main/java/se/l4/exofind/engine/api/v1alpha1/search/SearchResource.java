package se.l4.exofind.engine.api.v1alpha1.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.api.ExofindApi;
import se.l4.exofind.engine.api.auth.RequiresPermission;
import se.l4.exofind.engine.api.errors.ErrorResponse;
import se.l4.exofind.engine.api.routing.ServedBy;
import se.l4.exofind.engine.api.v1alpha1.search.model.ExplainResponse;
import se.l4.exofind.engine.api.v1alpha1.search.model.SearchRequest;
import se.l4.exofind.engine.api.v1alpha1.search.model.SearchResponse;
import se.l4.exofind.engine.auth.Permission;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.index.IndexException;
import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.settings.SearchSettings;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchExplanation;
import se.l4.exofind.engine.query.SearchResult;
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
 * A search runs on whichever node receives it, against the state that node
 * has - which for a node that is not the indexer is whatever it last pulled.
 * There is nothing here that has to reach the indexer, so nothing is
 * forwarded there.
 *
 * How deep offset paging may reach is capped, because skipping results costs
 * as much as ranking them - a request past the cap is refused with its own
 * error code rather than answered slowly. Following `next`/`previous` is the
 * way past the cap: those cursors carry the hit a window ended at rather than
 * a count, so continuing from one costs the same at any depth.
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
@Path("/v1alpha1/indexes/{name}/search")
@Produces(MediaType.APPLICATION_JSON)
public class SearchResource {
	private static final ErrorType IO_ERROR = ErrorType.withCode("index:io_error")
		.withArguments("index")
		.withMessage("The index `{{index}}` could not be searched");

	private final Indexes indexes;
	private final SearchSettings searchSettings;
	private final int maxPageDepth;

	public SearchResource(
		Indexes indexes,
		SearchSettings searchSettings,
		@ConfigProperty(name = "exofind.search.max-page-depth", defaultValue = "10000")
		int maxPageDepth
	) {
		this.indexes = indexes;
		this.searchSettings = searchSettings;
		this.maxPageDepth = maxPageDepth;
	}

	/**
	 * Search an index.
	 *
	 * @param body
	 *   what to search for, all of it optional - no body matches everything
	 */
	@POST
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
		content = @Content(schema = @Schema(implementation = SearchResponse.class))
	)
	@APIResponse(
		responseCode = "400",
		description = """
			The request is not a valid search. The `code` property names the \
			reason, such as `search:filter:scores` when a filter clause \
			affects the score, `index:query:usage_not_enabled` when a field is \
			used in a way the definition does not enable, or \
			`search:page:too_deep` when `offset` reaches past \
			`EXOFIND_SEARCH_MAX_PAGE_DEPTH`.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "401",
		description = """
			The request carries no credential this node accepts. Absent, \
			malformed, unknown and lapsed keys all answer this, so a refusal \
			cannot be used to find out which keys exist. The response carries \
			`WWW-Authenticate: Bearer`.""",
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
			No index has this name, or the key has no grant covering it - an \
			index a key was granted nothing on is answered as though it did \
			not exist.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = """
			The index exists but answers for none of its generations \
			(`index:no_live_generation`). Promote a generation and send the \
			request again.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "503",
		description = """
			The search raced the index being closed to free local resources \
			(`index:closed`). Sending the same request again reopens it.""",
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
		SearchRequest body
	) {
		var started = System.nanoTime();

		var index = indexes.getOrThrow(name);
		var mapped = SearchRequestMapper.toEngine(body, maxPageDepth);

		/*
		 * Settings belong to the index name, so a search naming one generation
		 * runs with the same settings the bare name does.
		 */
		var settings = searchSettings.get(IndexName.parse(name).index()).orElse(null);

		SearchResult result;
		try {
			result = index.search(mapped.request(), settings);
		} catch(IOException e) {
			throw new IndexException(IO_ERROR, e, "index", name);
		}

		/*
		 * Kept to microseconds rather than the nanoseconds measured, so that
		 * the number reads as a time and not as the last digits of how a
		 * double divides.
		 */
		var tookMs = Math.round((System.nanoTime() - started) / 1_000d) / 1_000d;
		return toResponse(mapped, result, tookMs);
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
	@Path("/actions/explain")
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
		content = @Content(schema = @Schema(implementation = ExplainResponse.class))
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
			No index has this name, or the key has no grant covering it. Also \
			when the index holds no document under `key` \
			(`index:explain:document_not_found`), or that document has no \
			value of the `hits` path at `index` \
			(`index:explain:value_not_found`).""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = """
			The index exists but answers for none of its generations \
			(`index:no_live_generation`).""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "503",
		description = """
			The request raced the index being closed to free local resources \
			(`index:closed`). Sending it again reopens the index.""",
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
				Primary key of the document, read as the type of the key field \
				- so a numeric key is written the way a document writes it. \
				For a search whose `hits` names an object field, the key of \
				the document holding the value, which is what such a hit \
				reports as its `id`.""",
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
		SearchRequest body
	) {
		var index = indexes.getOrThrow(name);
		var mapped = SearchRequestMapper.toEngine(body, maxPageDepth);

		// Settings belong to the index name, the way they do for a search
		var settings = searchSettings.get(IndexName.parse(name).index()).orElse(null);

		SearchExplanation explanation;
		try {
			explanation = index.explain(
				mapped.request(),
				index.parsePrimaryKey(key),
				valueIndex,
				settings
			);
		} catch(IOException e) {
			throw new IndexException(IO_ERROR, e, "index", name);
		}

		return new ExplainResponse(
			explanation.matched(),
			explanation.score(),
			toDetailJson(explanation.detail()),
			toRelaxedJson(explanation.relaxed())
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
			tookMs
		);
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

		var pages = mapped.pagesMax() != null
			? toPages(fingerprint, limit, request.offset(), result.total().count(), mapped.pagesMax())
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
		var lastReachable = maxPageDepth / limit;

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
