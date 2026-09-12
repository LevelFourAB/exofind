package se.l4.exofind.engine.api.v1alpha1.admin;

import java.io.IOException;
import java.util.List;

import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.eclipse.microprofile.openapi.annotations.tags.Tags;

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.api.ExofindApi;
import se.l4.exofind.engine.api.auth.AuthContext;
import se.l4.exofind.engine.api.auth.RequiresPermission;
import se.l4.exofind.engine.api.errors.ErrorResponse;
import se.l4.exofind.engine.api.errors.ReturnsError;
import se.l4.exofind.engine.api.errors.UnrepresentableStateException;
import se.l4.exofind.engine.api.routing.ServedBy;
import se.l4.exofind.engine.api.v1alpha1.admin.model.GenerationSummary;
import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexInfo;
import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexListResponse;
import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexStatus;
import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexerInfo;
import se.l4.exofind.engine.api.v1alpha1.admin.model.ReindexInfo;
import se.l4.exofind.engine.api.v1alpha1.admin.model.ReindexRequest;
import se.l4.exofind.engine.auth.ForbiddenException;
import se.l4.exofind.engine.auth.Permission;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.IndexDefinitionIncompatibleException;
import se.l4.exofind.engine.index.IndexException;
import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.IndexNotFoundException;
import se.l4.exofind.engine.index.IndexVersionMismatchException;
import se.l4.exofind.engine.index.registry.RegisteredIndex;
import se.l4.exofind.engine.index.settings.SearchSettings;
import se.l4.exofind.engine.index.state.IndexerOwnership;
import se.l4.exofind.engine.logging.Log;
import se.l4.exofind.engine.reindex.ReindexJobs;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * Manages indexes, index definitions, generations, and remote synchronization.
 *
 * <p>Index definitions operate as assertions of desired state. Sending a
 * definition replaces any previous definition in full, so repeating the request
 * produces the same outcome. The definition version is returned in an
 * {@code ETag} header and can be supplied in an {@code If-Match} header on
 * subsequent requests to prevent overwriting concurrent updates.
 *
 * <p>An index holds generations, and definitions belong to a generation rather
 * than directly to the index. Endpoints accept either the index name,
 * referencing the live generation, or a specific generation by name such as
 * {@code books@2}. When a definition change affects how documents are indexed,
 * create and populate a new generation, then promote it.
 *
 * <p>Modifying requests run on the node that holds the index; requests received
 * by other nodes are forwarded automatically.
 *
 * <p>Indexing and searching documents are handled by separate APIs.
 */
@Tag(
	name = "Indexes",
	description = "Defines, reads, and deletes indexes and their generations.",
	externalDocs = @ExternalDocumentation(
		description = "Admin API reference",
		url = "https://exofind.dev/reference/admin-api/"
	)
)
@SecurityRequirement(name = ExofindApi.API_KEY)
@Path("/v1alpha1/admin/indexes")
@Produces(MediaType.APPLICATION_JSON)
public class IndexResource {
	private static final Log logger = Log.of(IndexResource.class);

	private static final ErrorType MISSING_BODY = ErrorType.withCode("request:missing_body")
		.withMessage("A definition is required");

	private static final ErrorType IO_ERROR = ErrorType.withCode("index:io_error")
		.withArguments("index")
		.withMessage("The index `{{index}}` could not be updated on disk");

	private static final ErrorType REINDEX_NEEDS_NEW_GENERATION =
		ErrorType.withCode("index:reindex_needs_new_generation")
			.withArguments("name")
			.withMessage(
				"`reindex` fills a generation as it is created from the one that is"
					+ " live, which `{{name}}` does not create. Create a generation"
					+ " like `books@2`, or use the reindex action"
			);

	private final Indexes indexes;
	private final AuthContext auth;
	private final IndexerOwnership ownership;
	private final ReindexJobs reindexJobs;
	private final SearchSettings searchSettings;

	public IndexResource(
		Indexes indexes,
		AuthContext auth,
		IndexerOwnership ownership,
		ReindexJobs reindexJobs,
		SearchSettings searchSettings
	) {
		this.indexes = indexes;
		this.auth = auth;
		this.ownership = ownership;
		this.reindexJobs = reindexJobs;
		this.searchSettings = searchSettings;
	}

	/**
	 * List the indexes available on this node that the caller has permissions
	 * for.
	 *
	 * <p><p>Index listings omit indexes on which the key has no permissions
	 * rather than refusing the listing.
	 *
	 * @return
	 */
	@GET
	@RequiresPermission(value = Permission.INDEXES_READ, anyIndex = true)
	@Operation(
		operationId = "listIndexes",
		summary = "List indexes",
		description = """
			Lists the indexes the deployment holds, with their generations and \
			the live generation each answers for.

			Index listings omit indexes on which the key has no permissions \
			rather than refusing the listing."""
	)
	@APIResponse(
		responseCode = "200",
		description = "The indexes the key can see, ordered by name.",
		content = @Content(
			schema = @Schema(implementation = IndexListResponse.class),
			examples = @ExampleObject(name = "indexes", value = IndexListResponse.EXAMPLE)
		)
	)
	public IndexListResponse list() {
		var principal = auth.principal();
		var found = indexes.getRegistered()
			.select(index -> principal.allows(Permission.INDEXES_READ, index.name()))
			.collect(index -> new IndexListResponse.IndexSummary(
				index.name(),
				index.live(),
				toGenerations(index)
			))
			.toSortedListBy(IndexListResponse.IndexSummary::name);

		return new IndexListResponse(found);
	}

	/**
	 * Returns an index, its definition, and its current status.
	 *
	 * @param name
	 * @return
	 */
	@GET
	@Path("/{name}")
	@RequiresPermission(Permission.INDEXES_READ)
	@Operation(
		operationId = "getIndex",
		summary = "Get an index",
		description = """
			Returns the index resource: its definition as stored, the \
			generation described in the response, every generation it holds, \
			and the status the answering node observes.

			The definition version is returned in the `ETag` header. Pass this \
			value in the `If-Match` header on `PUT` requests to prevent \
			overwriting concurrent updates."""
	)
	@APIResponse(
		responseCode = "200",
		description = """
			The index, with its version in the `ETag` header. Presets are \
			stored expanded; the response returns the expanded chain rather \
			than the preset name.""",
		content = @Content(
			schema = @Schema(implementation = IndexInfo.class),
			examples = @ExampleObject(name = "index", value = IndexInfo.EXAMPLE)
		)
	)
	@APIResponse(
		responseCode = "404",
		description = """
			No index or generation has this name, or the key has no grant \
			covering it.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = """
			The node cannot describe the index. Send the request to a node \
			running a version that supports it.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "503",
		description = """
			The request raced the index being closed. Retrying the request \
			reopens the index.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@ReturnsError(
		value = "index:not_found",
		status = 404,
		when = "No index or generation has this name, or the key holds no grant covering it."
	)
	@ReturnsError(
		value = "index:definition:unrepresentable",
		status = 409,
		when = "The stored definition holds settings this API version cannot describe."
	)
	@ReturnsError(
		value = "index:field:unrepresentable_type",
		status = 409,
		when = "The stored definition holds a field of a type this API version cannot describe. The `name` argument names the field."
	)
	@ReturnsError(
		value = "index:unsupported",
		status = 409,
		when = "The index needs engine features this node does not have."
	)
	@ReturnsError(
		value = "index:no_live_generation",
		status = 409,
		when = "The index has no live generation. Promote one and send the request again."
	)
	@ReturnsError(
		value = "index:closed",
		status = 503,
		when = "The request raced the index being closed. Sending it again reopens the index."
	)
	public Response get(
		@Parameter(
			description = """
				The index, which means the generation it answers for, or one \
				generation by name such as `books@2`.""",
			example = "books"
		)
		@PathParam("name") String name
	) {
		var index = indexes.getOrThrow(name);
		return toResponse(Response.ok(), index).build();
	}

	/**
	 * Creates an index, adds a generation, or replaces an existing index
	 * definition.
	 *
	 * <p><p>The target of the request depends on the name format. {@code books}
	 * creates the index with an initial generation, or replaces the definition
	 * of the live generation; {@code books@2} adds that generation to an
	 * existing index, or replaces its definition. A newly created generation
	 * contains no documents and is not live; the index continues serving from
	 * the previous live generation until {@code actions/promote} is called.
	 *
	 * <p><p>When the target generation already holds documents, a request is
	 * refused if the new definition changes how documents are indexed, such as
	 * enabling a usage on an existing field, changing an analyzer chain, or
	 * editing a synonym set. Such changes require creating and promoting a new
	 * generation.
	 *
	 * @param name
	 *   the index, or one generation of it
	 * @param ifMatch
	 *   expected version of the definition, as returned in the {@code ETag}
	 *   header of a previous request. If the stored version does not match, the
	 *   request fails instead of overwriting concurrent changes. {@code *} asks
	 *   only that the index exists, and either form is refused with {@code 404}
	 *   while it does not
	 * @param reindex
	 *   promotion mode ({@code auto} or {@code manual}) to start a reindex job
	 *   populating the new generation from the live generation. Only valid when
	 *   creating a generation, and {@code auto} needs {@code indexes.promote} as
	 *   well because the job it starts promotes what it filled
	 * @param allowStaleDocuments
	 *   {@code true} to store a definition without reindexing existing
	 *   documents. Existing documents continue to serve queries as indexed
	 *   until they are reindexed
	 * @param definition
	 * @return
	 * @throws UnrepresentableStateException
	 *   if the index already has a definition holding settings this version of
	 *   the API can not describe, which replacing it would drop
	 * @throws IndexDefinitionIncompatibleException
	 *   if the generation holds documents the definition would not reach, and
	 *   {@code allowStaleDocuments} was not given
	 */
	@PUT
	@Path("/{name}")
	@Consumes(MediaType.APPLICATION_JSON)
	@RequiresPermission(Permission.INDEXES_WRITE)
	@ServedBy(value = ServedBy.Node.INDEXER, creates = true)
	@Operation(
		operationId = "putIndex",
		summary = "Create or replace an index definition",
		description = """
			Sends a definition in full, replacing any previous definition. Any \
			setting the body omits is removed. Repeating the request produces \
			the same outcome.

			The target of the request depends on the name format. `books` \
			creates the index with an initial generation named `1`, or updates \
			the definition of the live generation; `books@2` creates that \
			generation under an existing index, or updates its definition. A \
			newly created generation contains no documents and is not live; \
			the index continues serving from the previous live generation \
			until `actions/promote` is called, and `PUT books@2` on an index \
			that does not exist returns `404`.

			When the target generation already holds documents, a request is \
			refused with `409` and `index:definition:incompatible` if the new \
			definition changes how documents are indexed, such as enabling a \
			usage on an existing field, changing an analyzer chain, editing a \
			synonym set, or changing `type`, `primaryKey`, or `multiple`. The \
			response includes one detail item per difference, each with the \
			`path` of the field that caused it. Adding or removing a field, \
			disabling a usage, and changing `stored`, `source`, `metadata`, \
			`ranking`, or search-time settings are accepted.

			Requests run on the node that writes the index; a request received \
			by another node is forwarded there."""
	)
	@APIResponse(
		responseCode = "200",
		description = """
			An existing definition was replaced. The new version is in the \
			`ETag` header.""",
		content = @Content(
			schema = @Schema(implementation = IndexInfo.class),
			examples = @ExampleObject(name = "index", value = IndexInfo.EXAMPLE)
		)
	)
	@APIResponse(
		responseCode = "201",
		description = """
			The index or generation was created. The version is in the `ETag` \
			header and the location in `Location`.""",
		content = @Content(
			schema = @Schema(implementation = IndexInfo.class),
			examples = @ExampleObject(name = "index", value = IndexInfo.EXAMPLE)
		)
	)
	@APIResponse(
		responseCode = "400",
		description = """
			The definition failed validation - the response details each \
			problem - or the request asks for a reindex it cannot run.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@ReturnsError(
		value = "index:reindex_needs_new_generation",
		status = 400,
		when = "`reindex` was given on a request that creates no generation."
	)
	@ReturnsError(
		value = "request:missing_body",
		status = 400,
		when = "The request carries no definition."
	)
	@ReturnsError(
		value = "request:value_required",
		status = 400,
		when = "A property of the definition that needs a value is `null`."
	)
	@ReturnsError(
		value = "index:field:analyzer:invalid",
		status = 400,
		when = "The analyzer of a field is not exactly one of a preset, a custom chain and a named chain."
	)
	@ReturnsError(
		value = "index:field:analyzer:invalid_component",
		status = 400,
		when = "A component of a custom analysis chain is not exactly one kind."
	)
	@ReturnsError(
		value = "index:field:analyzer:decompound_on_given_chain",
		status = 400,
		when = "A field sets `decompound` beside a custom or named chain. A given chain says itself whether it splits, through a `decompound` component."
	)
	@ReturnsError(
		value = "index:field:locales:not_declared",
		status = 400,
		when = "A field names a locale the index does not declare in its `locales`."
	)
	@ReturnsError(
		value = "index:field:locales:list_with_declaration",
		status = 400,
		when = "A field lists its own `locales` while the index declares them. Narrow with `only` instead."
	)
	@ReturnsError(
		value = "index:field:locales:only_without_declaration",
		status = 400,
		when = "A field narrows with `only` while the index declares no `locales` to narrow."
	)
	@ReturnsError(
		value = "index:field:locales:default_not_in_only",
		status = 400,
		when = "A field narrows to locales that leave out the locale it defaults to."
	)
	@ReturnsError(
		value = "index:locales:default_locale_required",
		status = 400,
		when = "The `locales` of the index names no `defaultLocale`, which every field takes as its own."
	)
	@ReturnsError(
		value = "index:field:role:not_valid_for_type",
		status = 400,
		when = "A field has a role that no field of its type can answer for."
	)
	@ReturnsError(
		value = "index:field:role:not_valid_in_object",
		status = 400,
		when = "A field inside an object field has a role that cannot be used there."
	)
	@ReturnsError(
		value = "index:ranking:signal:invalid_shape",
		status = 400,
		when = "A ranking signal is not exactly one of `saturation`, `decay` and `linear`."
	)
	@ReturnsError(
		value = "index:resources:analyzers:named",
		status = 400,
		when = "An analysis chain in `resources` is itself `named`. The resources are where names are defined."
	)
	@ReturnsError(
		value = "index:resources:synonyms:invalid_rule",
		status = 400,
		when = "A rule of a synonym set in `resources` is not exactly one kind - equivalent words, or a one-way mapping."
	)
	@ReturnsError(
		value = "index:resources:synonyms:one_sided",
		status = 400,
		when = "A one-way synonym mapping carries no word on one of its sides."
	)
	@ReturnsError(
		value = "index:resources:synonyms:too_few_words",
		status = 400,
		when = "A rule of equivalent synonyms carries fewer than two words."
	)
	@ReturnsError(
		value = "index:resources:synonyms:blank_word",
		status = 400,
		when = "A synonym is blank."
	)
	@ReturnsError(
		value = "index:field:invalid_name",
		status = 400,
		when = "A field name holds something other than letters, numbers, underscores and wildcards. To hold fields under a dotted path, declare an `object` field."
	)
	@ReturnsError(
		value = "index:field:missing_type",
		status = 400,
		when = "A field declares no type."
	)
	@ReturnsError(
		value = "index:field:unsupported_type",
		status = 400,
		when = "A field has a type this version of the engine cannot index."
	)
	@ReturnsError(
		value = "index:field:invalid_name:primary_key_wildcard",
		status = 400,
		when = "A field name with a wildcard is marked as the primary key."
	)
	@ReturnsError(
		value = "index:field:invalid_primary_key_multiple",
		status = 400,
		when = "The primary key field is also `multiple`."
	)
	@ReturnsError(
		value = "index:field:invalid_primary_key_type",
		status = 400,
		when = "The primary key field has a type that cannot be a primary key."
	)
	@ReturnsError(
		value = "index:schema:multiple_primary_keys",
		status = 400,
		when = "More than one field is marked as the primary key."
	)
	@ReturnsError(
		value = "index:schema:primary_key_locale_specific",
		status = 400,
		when = "The primary key field is locale specific."
	)
	@ReturnsError(
		value = "index:schema:primary_key_not_required",
		status = 400,
		when = "The primary key field is marked as not required."
	)
	@ReturnsError(
		value = "index:schema:unsupported_features",
		status = 400,
		when = "The definition needs engine features this version does not have. The `features` argument names them."
	)
	@ReturnsError(
		value = "index:field:invalid_required",
		status = 400,
		when = "A field name with a wildcard is marked as required."
	)
	@ReturnsError(
		value = "index:field:invalid_sortable",
		status = 400,
		when = "A field is both `sortable` and `multiple`."
	)
	@ReturnsError(
		value = "index:field:sorting_not_supported",
		status = 400,
		when = "A field is `sortable` and its type cannot be sorted on."
	)
	@ReturnsError(
		value = "index:field:faceting_not_supported",
		status = 400,
		when = "A field is faceted and its type cannot be counted per value."
	)
	@ReturnsError(
		value = "index:field:signal_not_supported",
		status = 400,
		when = "A field is a signal and its type cannot be refreshed in place."
	)
	@ReturnsError(
		value = "index:field:signal:usage_conflict",
		status = 400,
		when = "A signal field is also declared for a usage that would go stale on every refresh."
	)
	@ReturnsError(
		value = "index:field:signal:wildcard",
		status = 400,
		when = "A field name with a wildcard is a signal. A signal is refreshed by the name it was declared under."
	)
	@ReturnsError(
		value = "index:field:locales:unsupported_locale",
		status = 400,
		when = "A field names a locale this version of the engine does not support."
	)
	@ReturnsError(
		value = "index:field:locales:fallback_without_index",
		status = 400,
		when = "A field takes part in locale fallback and the index declares none."
	)
	@ReturnsError(
		value = "index:locale_fallback:no_locale_fields",
		status = 400,
		when = "The index falls back between locales and no field of it is locale specific."
	)
	@ReturnsError(
		value = "index:locale_fallback:duplicate_locale",
		status = 400,
		when = "A locale is fallen back to more than once."
	)
	@ReturnsError(
		value = "index:locale_fallback:locale_not_held",
		status = 400,
		when = "A locale is fallen back to that no field of the index holds values in."
	)
	@ReturnsError(
		value = "index:locale_fallback:unsupported_locale",
		status = 400,
		when = "A locale is fallen back to that this version of the engine does not support."
	)
	@ReturnsError(
		value = "index:field:analyzer:ambiguous",
		status = 400,
		when = "A usage carries an analysis chain and also names one in `resources`. Give at most one of the two."
	)
	@ReturnsError(
		value = "index:field:analyzer:unknown_ref",
		status = 400,
		when = "A usage names an analysis chain that `resources` does not define."
	)
	@ReturnsError(
		value = "index:field:analyzer:unknown_stopwords",
		status = 400,
		when = "An analysis chain names a stopword list that `resources` does not define."
	)
	@ReturnsError(
		value = "index:field:analyzer:unknown_synonyms",
		status = 400,
		when = "An analysis chain names a synonym set that `resources` does not define."
	)
	@ReturnsError(
		value = "index:field:analyzer:unsupported_locale",
		status = 400,
		when = "An analysis chain names a locale this version of the engine does not support."
	)
	@ReturnsError(
		value = "index:field:analyzer:unsupported_decompounding",
		status = 400,
		when = "An analysis chain splits compounds by a locale this version of the engine has no decompounding data for."
	)
	@ReturnsError(
		value = "index:field:analyzer:invalid_grams",
		status = 400,
		when = "An n-gram component asks for sizes below one, or a shortest longer than its longest."
	)
	@ReturnsError(
		value = "index:field:analyzer:invalid_pattern",
		status = 400,
		when = "A pattern replacement component carries something that is not a valid regular expression."
	)
	@ReturnsError(
		value = "index:field:matching:invalid_weight",
		status = 400,
		when = "The weight of matching is not above zero."
	)
	@ReturnsError(
		value = "index:field:matching:invalid_typo_min_length",
		status = 400,
		when = "The shortest word that may hold a typo is below one character."
	)
	@ReturnsError(
		value = "index:field:matching:invalid_typo_order",
		status = 400,
		when = "A word is long enough for two typos before it is long enough for one."
	)
	@ReturnsError(
		value = "index:field:matching:invalid_typo_prefix",
		status = 400,
		when = "The prefix matched exactly under typo tolerance is below zero."
	)
	@ReturnsError(
		value = "index:field:autocomplete:invalid_weight",
		status = 400,
		when = "The weight of autocomplete is not above zero."
	)
	@ReturnsError(
		value = "index:field:exact:invalid_boost",
		status = 400,
		when = "The boost of a whole-value match is not above zero."
	)
	@ReturnsError(
		value = "index:field:hierarchy:invalid_separator",
		status = 400,
		when = "The separator between the levels of a path is empty. Leave it out for `/`."
	)
	@ReturnsError(
		value = "index:field:sort:collation_not_supported",
		status = 400,
		when = "A timestamp field declares a collation, which means nothing when sorting one."
	)
	@ReturnsError(
		value = "index:field:number:invalid_unit",
		status = 400,
		when = "The `unit` of a number field is not text."
	)
	@ReturnsError(
		value = "index:field:number:invalid_bound",
		status = 400,
		when = "A validation bound of a number field is not a finite number."
	)
	@ReturnsError(
		value = "index:field:number:invalid_bounds",
		status = 400,
		when = "The `min` of a number field is above its `max`."
	)
	@ReturnsError(
		value = "index:field:vector:missing_dimensions",
		status = 400,
		when = "A vector field declares no dimensions."
	)
	@ReturnsError(
		value = "index:field:vector:invalid_dimensions",
		status = 400,
		when = "The dimensions of a vector field are outside 1 to the maximum the engine indexes."
	)
	@ReturnsError(
		value = "index:field:vector:invalid_hnsw_m",
		status = 400,
		when = "The HNSW neighbour count `m` is outside the range the engine builds."
	)
	@ReturnsError(
		value = "index:field:vector:invalid_hnsw_ef_construction",
		status = 400,
		when = "The HNSW `ef_construction` is outside the range the engine builds."
	)
	@ReturnsError(
		value = "index:field:vector:multiple_not_supported",
		status = 400,
		when = "A vector field is `multiple`. A vector field holds one vector per document."
	)
	@ReturnsError(
		value = "index:field:vector:locales_not_supported",
		status = 400,
		when = "A vector field is locale specific."
	)
	@ReturnsError(
		value = "index:field:vector:filter_not_supported",
		status = 400,
		when = "A vector field is declared for `filter`. Search a vector field with a `knn` clause."
	)
	@ReturnsError(
		value = "index:field:object:no_fields",
		status = 400,
		when = "An object field declares no fields."
	)
	@ReturnsError(
		value = "index:field:object:usage_not_supported",
		status = 400,
		when = "An object field is declared for a usage it holds no value of its own to answer."
	)
	@ReturnsError(
		value = "index:field:object:inner_usage_not_supported",
		status = 400,
		when = "A field inside an object is declared for a usage that is not supported there."
	)
	@ReturnsError(
		value = "index:field:object:mode_required",
		status = 400,
		when = "A list of objects declares no `mode`. Use `nested` when a search asks that conditions hold inside one value, `flattened` when the values are only structure."
	)
	@ReturnsError(
		value = "index:field:object:mode_without_multiple",
		status = 400,
		when = "A single object declares a `mode`, which applies only together with `multiple`."
	)
	@ReturnsError(
		value = "index:field:object:nested_in_nested",
		status = 400,
		when = "A nested list of objects sits below another nested list. Keep the inner list `flattened`, or lift it out."
	)
	@ReturnsError(
		value = "index:field:object:flattened_sort",
		status = 400,
		when = "A field inside a flattened list of objects is declared for `sort`."
	)
	@ReturnsError(
		value = "index:field:object:flattened_stored",
		status = 400,
		when = "A field inside a flattened list of objects is declared for `stored`."
	)
	@ReturnsError(
		value = "index:field:object:key_without_multiple",
		status = 400,
		when = "A single object declares a `key`, which applies only together with `multiple`."
	)
	@ReturnsError(
		value = "index:field:object:key_not_found",
		status = 400,
		when = "The `key` of an object field names a field the object does not hold."
	)
	@ReturnsError(
		value = "index:field:object:key_not_valid",
		status = 400,
		when = "The `key` of an object field names a field that cannot say which value is which."
	)
	@ReturnsError(
		value = "index:ranking:unknown_field",
		status = 400,
		when = "A tie-breaker names a field the definition does not declare."
	)
	@ReturnsError(
		value = "index:ranking:wildcard_field",
		status = 400,
		when = "A tie-breaker names fields with a wildcard. A tie-breaker orders by one field."
	)
	@ReturnsError(
		value = "index:ranking:field_not_sortable",
		status = 400,
		when = "A tie-breaker names a field that is not defined for sorting."
	)
	@ReturnsError(
		value = "index:ranking:duplicate_field",
		status = 400,
		when = "Two tie-breakers name the same field."
	)
	@ReturnsError(
		value = "index:ranking:signal:unknown_field",
		status = 400,
		when = "A ranking signal names a field the definition does not declare."
	)
	@ReturnsError(
		value = "index:ranking:signal:wildcard_field",
		status = 400,
		when = "A ranking signal names fields with a wildcard. A signal reads one field."
	)
	@ReturnsError(
		value = "index:ranking:signal:field_not_sortable",
		status = 400,
		when = "A ranking signal names a field that is not defined for sorting, so it holds no value to read."
	)
	@ReturnsError(
		value = "index:ranking:signal:shape_not_set",
		status = 400,
		when = "A ranking signal does not say how the value it reads counts."
	)
	@ReturnsError(
		value = "index:ranking:signal:shape_not_supported",
		status = 400,
		when = "A ranking signal reads its field with a shape the type of the field holds nothing for."
	)
	@ReturnsError(
		value = "index:ranking:signal:invalid_pivot",
		status = 400,
		when = "The `pivot` of a saturation signal is not a number above zero."
	)
	@ReturnsError(
		value = "index:ranking:signal:invalid_half_life",
		status = 400,
		when = "The `halfLife` of a decay signal is not longer than nothing."
	)
	@ReturnsError(
		value = "index:ranking:signal:invalid_ceiling",
		status = 400,
		when = "The `ceiling` of a linear signal is not a number above zero."
	)
	@ReturnsError(
		value = "index:ranking:signal:invalid_weight",
		status = 400,
		when = "The `weight` of a ranking signal is below zero."
	)
	@ReturnsError(
		value = "index:not_found",
		status = 404,
		when = "The name belongs to no index, or the key holds no grant covering it."
	)
	@ReturnsError(
		value = "index:field:unrepresentable_type",
		status = 409,
		when = "The stored definition holds a field of a type this API version cannot describe, which a `PUT` would discard. The `name` argument names the field."
	)
	@ReturnsError(
		value = "index:no_live_generation",
		status = 409,
		when = "The index has no live generation. Promote one and send the request again."
	)
	@ReturnsError(
		value = "index:definition:incompatible",
		status = 409,
		when = "The definition conflicts with documents already stored in the generation. Write the change to a new generation."
	)
	@ReturnsError(
		value = "index:definition:unrepresentable",
		status = 409,
		when = "The stored definition holds settings this API version cannot describe."
	)
	@ReturnsError(
		value = "index:unsupported",
		status = 409,
		when = "The index needs engine features this node does not have."
	)
	@ReturnsError(
		value = "reindex:in_progress",
		status = 409,
		when = "A reindex job is already running for the index."
	)
	@ReturnsError(
		value = "index:generation:storage_held",
		status = 409,
		when = "Storage holds a generation under the new name that nothing deleted. Repair the registry, or remove its objects."
	)
	@ReturnsError(
		value = "indexer:unavailable",
		status = 409,
		when = "No node is available to write the index. Send the request again once one is."
	)
	@ReturnsError(
		value = "index:registry:conflict",
		status = 409,
		when = "The registry kept being written by other nodes. Send the request again."
	)
	@ReturnsError(
		value = "index:version-mismatch",
		status = 412,
		when = "The `If-Match` version is not the one the stored definition is at. Read the index again and rebuild the change."
	)
	@ReturnsError(
		value = "indexer:unreachable",
		status = 502,
		when = "The request was forwarded to the index writer and the writer did not answer. Send it again."
	)
	@ReturnsError(
		value = "index:closed",
		status = 503,
		when = "The request raced the index being closed. Sending it again reopens the index."
	)
	@APIResponse(
		responseCode = "404",
		description = """
			`If-Match` was sent for an index that does not exist, `books@2` \
			named an index that does not exist, or the key has no grant \
			covering the name.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = """
			The definition conflicts with documents stored in the generation \
			(`index:definition:incompatible`), the stored definition contains \
			settings this API version cannot represent \
			(`index:definition:unrepresentable`), the index requires engine \
			features this node does not have (`index:unsupported`), a reindex \
			job is already running (`reindex:in_progress`), storage holds a \
			generation under the new name that nothing deleted \
			(`index:generation:storage_held`), no node is available to write \
			the index (`indexer:unavailable`), or the registry write failed.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "412",
		description = """
			The `If-Match` version does not match the stored definition. \
			Re-read the index and rebuild the change against the new version.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "502",
		description = "The index writer did not respond to the forwarded request.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "503",
		description = """
			The request raced the index being closed. Retrying the request \
			reopens the index.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	public Response put(
		@Parameter(
			description = """
				The index, which creates it or updates the live generation, or \
				one generation by name such as `books@2`.""",
			example = "books"
		)
		@PathParam("name") String name,
		@Parameter(
			description = """
				The expected definition version, as returned in a previous \
				`ETag` header. `*` asks only that the index exists. Several \
				versions may be given, separated by commas, and the header is \
				satisfied while the stored version is one of them; versions are \
				compared exactly, so a weak tag (`W/"..."`) matches none. An \
				index that does not exist answers `404`, and a version that no \
				longer matches answers `412` instead of overwriting \
				intermediate changes.""",
			example = "\"9f2c1a0b3d4e5f60\""
		)
		@HeaderParam("If-Match") String ifMatch,
		@Parameter(
			description = """
				Starts a reindex job filling the generation being created from \
				the live one, the way the reindex action would. One-shot: it \
				is not stored in the definition, and it is refused on a \
				request that creates no generation. `auto` also needs \
				`indexes.promote`, because the job it starts promotes the \
				generation it filled; `manual` needs only `indexes.write`.""",
			schema = @Schema(enumeration = {"auto", "manual"})
		)
		@QueryParam("reindex") String reindex,
		@Parameter(
			description = """
				Forces the update without reindexing existing documents. \
				Existing documents continue to serve queries as indexed until \
				they are reindexed. Has no effect on an empty generation.""",
			schema = @Schema(type = SchemaType.BOOLEAN, defaultValue = "false")
		)
		@QueryParam("allowStaleDocuments") @DefaultValue("false") boolean allowStaleDocuments,
		@Context UriInfo uriInfo,
		@RequestBody(content = @Content(
			schema = @Schema(implementation = IndexDefinition.class),
			examples = @ExampleObject(
				name = "definition",
				summary = "A primary key and four searchable fields",
				value = IndexDefinition.EXAMPLE
			)
		))
		IndexDefinition definition
	) {
		if(definition == null) {
			throw new ValidationException(MISSING_BODY.toMessage(ObjectLocation.root()));
		}

		var stored = IndexDefinitionMapper.toStored(definition);
		var requested = IndexName.parse(name);
		var expected = IfMatch.of(ifMatch);

		// A value the job would refuse is refused before anything is created
		if(reindex != null) {
			/*
			 * A job left on automatic promotion promotes what it filled, which
			 * is what `indexes.promote` is about. The filter checked
			 * `indexes.write`, so the second permission is checked here, the
			 * same way the reindex action checks it.
			 */
			if(
				!ReindexJobs.parsePromote(reindex)
					&& !auth.principal().allows(Permission.INDEXES_PROMOTE, name)
			) {
				throw new ForbiddenException(Permission.INDEXES_PROMOTE);
			}
		}

		var existing = indexes.get(name);

		if(existing.isEmpty()) {
			/*
			 * There is nothing to be told about a conflict with, so no
			 * precondition can be satisfied - `*` included, which asks that the
			 * index exists rather than that it is at some version.
			 */
			if(expected.isConditional()) {
				throw new IndexNotFoundException(name);
			}

			if(reindex != null && !requested.isPinned()) {
				/*
				 * Creating the index itself leaves nothing to fill the first
				 * generation from - the flag belongs to a generation created
				 * next to a live one.
				 */
				throw new ValidationException(
					REINDEX_NEEDS_NEW_GENERATION.toMessage(ObjectLocation.root(), "name", name)
				);
			}

			if(reindex != null) {
				/*
				 * Asked before the generation exists: a generation left behind
				 * by a refused job is refused the flag on every repeat of the
				 * same request, so the caller could not send it again.
				 */
				reindexJobs.checkStartable(name, stored);
			}

			var created = requested.isPinned()
				? indexes.createGeneration(name, stored)
				: indexes.create(name, stored);

			if(reindex != null) {
				/*
				 * A one-shot instruction rather than part of the definition:
				 * the same job the reindex action starts, reading from the
				 * live generation. A node dying between the create and here
				 * loses the flag, and the client calls the action instead.
				 */
				try {
					reindexJobs.start(name, null, reindex);
				} catch(RuntimeException e) {
					rollBack(name);
					throw e;
				}
			}

			return toResponse(Response.created(uriInfo.getAbsolutePath()), created).build();
		}

		if(reindex != null) {
			/*
			 * The flag fills what is being created, and this request created
			 * nothing. Refused rather than ignored, so a repeated create does
			 * not quietly stop meaning "and fill it".
			 */
			throw new ValidationException(
				REINDEX_NEEDS_NEW_GENERATION.toMessage(ObjectLocation.root(), "name", name)
			);
		}

		var index = existing.get();

		/*
		 * A definition replaces the previous one whole, so anything in the
		 * stored one this version has no model for would go without the caller
		 * ever seeing it.
		 */
		IndexDefinitionMapper.checkRepresentable(index.getDefinition());

		/*
		 * The tag that matched is what the update is made conditional on, so a
		 * definition replaced between this read and the write is reported
		 * rather than overwritten. `*` names no version, so it is satisfied by
		 * the index being here at all.
		 */
		var version = index.getDefinitionVersion();
		if(!expected.matches(version)) {
			throw new IndexVersionMismatchException(name, expected.describe(), version);
		}

		try {
			index.updateDefinition(
				stored,
				expected.namesVersions() ? version : null,
				allowStaleDocuments
			);
		} catch(IOException e) {
			throw new IndexException(IO_ERROR, e, "index", name);
		}

		return toResponse(Response.ok(), index).build();
	}

	/**
	 * Deletes an index and all of its generations, or a single generation.
	 *
	 * <p><p>Removing an index or generation removes it from the shared registry
	 * across the deployment; other nodes remove their local copies during their
	 * next registry read. What remote storage holds is marked and removed by a
	 * sweep after a grace period, during which a registry repair can restore
	 * it. Deleting the live generation is refused until another generation is
	 * promoted.
	 *
	 * <p><p>Served by the node writing the index, so the writer closes its
	 * copy before the registry changes and pushes nothing after.
	 *
	 * @param name
	 *   the index, or one generation of it
	 * @return
	 */
	@DELETE
	@Path("/{name}")
	@RequiresPermission(Permission.INDEXES_DELETE)
	@ServedBy(ServedBy.Node.INDEXER)
	@Operation(
		operationId = "deleteIndex",
		summary = "Delete an index or a generation",
		description = """
			Deleting `books` deletes the index and all of its generations; \
			deleting `books@2` deletes only that generation. Deleting the live \
			generation fails with `index:generation:is_live` until another \
			generation is promoted.

			Deleting an index or generation removes it from the shared \
			registry across the deployment; other nodes remove their local \
			copies during their next registry read. What remote storage \
			holds - the generations and, for an index, its search settings - \
			is marked as deleted and removed by a background sweep once the \
			mark is older than `EXOFIND_INDEXES_REMOVAL_GRACE`. Until then a \
			registry repair with `restore` brings the index or generation \
			back. An index or generation created again under the same name \
			starts empty, whether or not the sweep has run.

			Served by the node writing the index and forwarded there when \
			another node receives it."""
	)
	@APIResponse(
		responseCode = "204",
		description = "The index or generation was removed."
	)
	@APIResponse(
		responseCode = "404",
		description = """
			No index or generation has this name, or the key has no grant \
			covering it.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = "The index or generation cannot be removed right now.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "502",
		description = "The index writer did not respond to the forwarded request.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@ReturnsError(
		value = "index:not_found",
		status = 404,
		when = "No index or generation has this name, or the key holds no grant covering it."
	)
	@ReturnsError(
		value = "index:generation:is_live",
		status = 409,
		when = "The generation is the live one. Promote another generation first."
	)
	@ReturnsError(
		value = "indexer:unavailable",
		status = 409,
		when = "No node is available to write the index. Send the request again once one is."
	)
	@ReturnsError(
		value = "index:registry:conflict",
		status = 409,
		when = "The registry kept being written by other nodes. Send the request again."
	)
	@ReturnsError(
		value = "indexer:unreachable",
		status = 502,
		when = "The request was forwarded to the index writer and the writer did not answer. Send it again."
	)
	public Response delete(
		@Parameter(
			description = """
				The index name, which deletes the index and all of its \
				generations, or a specific generation by name such as \
				`books@2`.""",
			example = "books"
		)
		@PathParam("name") String name
	) {
		try {
			indexes.delete(name);
		} catch(IOException e) {
			throw new IndexException(IO_ERROR, e, "index", name);
		}

		return Response.noContent().build();
	}

	/**
	 * Configures an index to serve from the specified generation.
	 *
	 * <p>Requests using the bare index name read the promoted generation
	 * immediately on the receiving node and within the refresh interval on all
	 * other nodes. To roll back a deployment, promote the previous generation.
	 *
	 * <p>A generation being filled by a reindex job is promoted through the
	 * job. Promoting a job in the ready phase drains remaining changes and
	 * completes promotion; promoting before the job is ready is refused.
	 *
	 * @param name
	 *   the generation to promote, as {@code index@generation}
	 * @return
	 */
	@POST
	@Path("/{name}/actions/promote")
	@RequiresPermission(Permission.INDEXES_PROMOTE)
	@ServedBy(ServedBy.Node.INDEXER)
	@Operation(
		operationId = "promoteGeneration",
		summary = "Promote a generation",
		description = """
			Configures the index to serve from the specified generation. The \
			change takes effect immediately on the receiving node and within \
			`EXOFIND_INDEXES_REFRESH_INTERVAL` on all other nodes. To roll \
			back a deployment, promote the previous generation.

			The request path must specify a generation name; calling `promote` \
			without a generation returns `index:generation:name_required`. \
			Promoting the target of a `ready` reindex job finishes the job, \
			while promoting before the job is ready is refused with \
			`reindex:target_busy`."""
	)
	@APIResponse(
		responseCode = "200",
		description = "The index now answers from this generation.",
		content = @Content(
			schema = @Schema(implementation = IndexInfo.class),
			examples = @ExampleObject(name = "index", value = IndexInfo.EXAMPLE)
		)
	)
	@APIResponse(
		responseCode = "400",
		description = "The path names no generation.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "404",
		description = """
			No index or generation has this name, or the key has no grant \
			covering it.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = "The generation cannot be promoted right now.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@ReturnsError(
		value = "index:generation:name_required",
		status = 400,
		when = "The path names an index without a generation. Name one as `index@generation`."
	)
	@ReturnsError(
		value = "index:not_found",
		status = 404,
		when = "No index or generation has this name, or the key holds no grant covering it."
	)
	@ReturnsError(
		value = "reindex:target_busy",
		status = 409,
		when = "A reindex job is still filling this generation. Promote it once the job is ready."
	)
	@ReturnsError(
		value = "index:generation:live_moved",
		status = 409,
		when = "Another generation was promoted while the reindex job that filled this one was running. The job moves to `failed`; start a new job that reads from the generation the `live` argument names."
	)
	@ReturnsError(
		value = "index:field:unrepresentable_type",
		status = 409,
		when = "The stored definition holds a field of a type this API version cannot describe. The `name` argument names the field."
	)
	@ReturnsError(
		value = "index:no_live_generation",
		status = 409,
		when = "The index has no live generation. Promote one and send the request again."
	)
	@ReturnsError(
		value = "indexer:unavailable",
		status = 409,
		when = "No node is available to write the index. Send the request again once one is."
	)
	@ReturnsError(
		value = "index:registry:conflict",
		status = 409,
		when = "The registry kept being written by other nodes. Send the request again."
	)
	@ReturnsError(
		value = "indexer:unreachable",
		status = 502,
		when = "The request was forwarded to the index writer and the writer did not answer. Send it again."
	)
	@APIResponse(
		responseCode = "502",
		description = "The index writer did not respond to the forwarded request.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	public Response promote(
		@Parameter(
			description = "The generation to promote, as `index@generation`.",
			example = "books@2"
		)
		@PathParam("name") String name
	) {
		if(!reindexJobs.promoteThroughJob(name)) {
			indexes.promote(name);
		}

		return toResponse(Response.ok(), indexes.getOrThrow(name)).build();
	}

	/**
	 * Pushes pending changes (documents and definition) to storage and returns
	 * the resulting status.
	 *
	 * @param name
	 * @return
	 */
	@POST
	@Path("/{name}/actions/commit")
	@RequiresPermission(Permission.INDEXES_COMMIT)
	@ServedBy(ServedBy.Node.INDEXER)
	@Operation(
		operationId = "commitIndex",
		summary = "Commit pending changes",
		description = """
			Pushes pending changes (documents and definition) to storage, \
			making them searchable. The index writer commits automatically \
			based on indexing volume or elapsed time. Use this endpoint to \
			commit immediately, such as after loading a dataset.

			Acts on the generation specified in the request path, or the live \
			generation if omitted. Runs on the node that writes the index."""
	)
	@APIResponse(
		responseCode = "200",
		description = "The resulting status of the index.",
		content = @Content(
			schema = @Schema(implementation = IndexStatus.class),
			examples = @ExampleObject(name = "status", value = IndexStatus.EXAMPLE)
		)
	)
	@APIResponse(
		responseCode = "404",
		description = """
			No index or generation has this name, or the key has no grant \
			covering it.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = "The index cannot be committed right now.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "502",
		description = "The index writer did not respond to the forwarded request.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "503",
		description = """
			The request raced the index being closed. Retrying the request \
			reopens the index.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@ReturnsError(
		value = "index:not_found",
		status = 404,
		when = "No index or generation has this name, or the key holds no grant covering it."
	)
	@ReturnsError(
		value = "indexer:unavailable",
		status = 409,
		when = "No node is available to write the index. Send the request again once one is."
	)
	@ReturnsError(
		value = "index:readonly",
		status = 409,
		when = "The node lost the writer role while the request ran. Send the request again to reach the new writer."
	)
	@ReturnsError(
		value = "index:no_live_generation",
		status = 409,
		when = "The index has no live generation. Promote one and send the request again."
	)
	@ReturnsError(
		value = "indexer:unreachable",
		status = 502,
		when = "The request was forwarded to the index writer and the writer did not answer. Send it again."
	)
	@ReturnsError(
		value = "index:closed",
		status = 503,
		when = "The request raced the index being closed. Sending it again reopens the index."
	)
	public IndexStatus commit(
		@Parameter(
			description = """
				The index name, which commits the live generation, or a \
				specific generation by name such as `books@2`.""",
			example = "books"
		)
		@PathParam("name") String name
	) {
		var index = indexes.getOrThrow(name);

		try {
			index.commit();
		} catch(IOException e) {
			throw new IndexException(IO_ERROR, e, "index", name);
		}

		return toStatus(index);
	}

	/**
	 * Fetches the latest remote state of an index immediately instead of
	 * waiting for the refresh interval.
	 *
	 * <p>A pull updates the local copy on the node serving the request and is
	 * never forwarded.
	 *
	 * @param name
	 * @return
	 */
	@POST
	@Path("/{name}/actions/pull")
	@RequiresPermission(Permission.INDEXES_PULL)
	@ServedBy(ServedBy.Node.ANY_NODE)
	@Operation(
		operationId = "pullIndex",
		summary = "Pull the latest state",
		description = """
			Fetches the latest remote state immediately instead of waiting for \
			`EXOFIND_INDEXES_REFRESH_INTERVAL`, and returns the resulting \
			status.

			A pull updates the local copy on the receiving node and is never \
			forwarded."""
	)
	@APIResponse(
		responseCode = "200",
		description = "The resulting status of the index on this node.",
		content = @Content(
			schema = @Schema(implementation = IndexStatus.class),
			examples = @ExampleObject(name = "status", value = IndexStatus.EXAMPLE)
		)
	)
	@APIResponse(
		responseCode = "404",
		description = """
			No index or generation has this name, or the key has no grant \
			covering it.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = "The index cannot be pulled right now.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "503",
		description = """
			The request raced the index being closed. Retrying the request \
			reopens the index.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@ReturnsError(
		value = "index:not_found",
		status = 404,
		when = "No index or generation has this name, or the key holds no grant covering it."
	)
	@ReturnsError(
		value = "index:no_live_generation",
		status = 409,
		when = "The index has no live generation. Promote one and send the request again."
	)
	@ReturnsError(
		value = "index:closed",
		status = 503,
		when = "The request raced the index being closed. Sending it again reopens the index."
	)
	public IndexStatus pull(
		@Parameter(
			description = """
				The index name, which pulls the live generation, or a specific \
				generation by name such as `books@2`.""",
			example = "books"
		)
		@PathParam("name") String name
	) {
		var index = indexes.getOrThrow(name);
		index.pull();
		return toStatus(index);
	}

	/**
	 * Starts a reindex job to populate a generation from another generation of
	 * the same index.
	 *
	 * <p>The rest of the reindex endpoints are {@link ReindexResource}, which
	 * answers for the job record. Starting is here because it is an action on
	 * the generation the job fills, and a path under the one this class is
	 * served at is answered by this class alone - a resource class matching the
	 * start of a path is the one asked for the whole of it.
	 *
	 * <p>The target generation must be specified by name, must be empty, and
	 * must not be live. The source generation defaults to the live generation.
	 *
	 * <p>The job promotes the target generation once caught up unless
	 * configured with {@code "promote": "manual"}. When manual, the job pauses
	 * in the ready phase until {@code actions/promote} on the target completes
	 * it.
	 *
	 * <p>A job that promotes changes what the index answers for, so it needs
	 * {@code indexes.promote} on the target as well as {@code indexes.reindex}.
	 * A request asking for manual promotion needs only {@code indexes.reindex},
	 * and the caller presents a key holding {@code indexes.promote} to
	 * {@code actions/promote} when the job is ready.
	 *
	 * @param name
	 *   the generation to fill, as {@code index@generation}
	 * @param body
	 *   configuration specifying the source generation and promotion mode, or
	 *   omitted for defaults
	 * @return
	 */
	@POST
	@Path("/{name}/actions/reindex")
	@Consumes(MediaType.APPLICATION_JSON)
	@RequiresPermission(Permission.INDEXES_REINDEX)
	@ServedBy(ServedBy.Node.INDEXER)
	@Tags(refs = "Reindexes")
	@Operation(
		operationId = "startReindex",
		summary = "Start a reindex job",
		description = """
			Starts a reindex job that populates a new generation by copying \
			documents from an existing generation of the same index inside the \
			engine. The request returns immediately with the job record; the \
			job runs in the background on the node holding the index.

			The target must specify a generation by name, must already exist, \
			must be empty, and must not be the live generation. The source \
			generation must have a primary key and keep document sources, and \
			the primary keys of source and target must share a field name and \
			type. If the target does not meet these requirements, the server \
			returns `400`.

			The job automatically promotes the target generation once it \
			catches up with changes, unless the request specifies `"promote": \
			"manual"`. With manual promotion, the job pauses in the `ready` \
			phase and keeps the target caught up until `actions/promote` on \
			the target finishes the job.

			Because promoting changes what the index answers for, a request \
			that leaves promotion automatic also needs `indexes.promote` on \
			the target and is refused with `403` without it. A request \
			specifying `"promote": "manual"` needs only `indexes.reindex`.

			An index can run at most one reindex job at a time. A finished \
			job's record remains readable until a new job replaces it. Read \
			the record with `GET /v1alpha1/admin/reindexes/{name}`."""
	)
	@APIResponse(
		responseCode = "202",
		description = "A reindex job was started and runs asynchronously.",
		content = @Content(
			schema = @Schema(implementation = ReindexInfo.class),
			examples = @ExampleObject(name = "job", value = ReindexInfo.EXAMPLE_STARTED)
		)
	)
	@APIResponse(
		responseCode = "400",
		description = """
			The target does not specify a generation by name, does not exist, \
			is not empty, is the live generation, or the source and target \
			primary keys do not match.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "404",
		description = """
			The specified index or generation does not exist, or the caller \
			key lacks permissions on the index.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = "The job could not be started.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@ReturnsError(
		value = "reindex:target_generation_required",
		status = 400,
		when = "The target names an index without a generation. Name one as `index@generation`."
	)
	@ReturnsError(
		value = "reindex:target_is_live",
		status = 400,
		when = "The target is the live generation. Fill another generation and promote it."
	)
	@ReturnsError(
		value = "reindex:target_not_empty",
		status = 400,
		when = "The target generation already holds documents."
	)
	@ReturnsError(
		value = "reindex:source_is_target",
		status = 400,
		when = "The source and the target are the same generation."
	)
	@ReturnsError(
		value = "reindex:source_other_index",
		status = 400,
		when = "The source belongs to another index."
	)
	@ReturnsError(
		value = "reindex:primary_key_mismatch",
		status = 400,
		when = "The source and the target declare different primary keys."
	)
	@ReturnsError(
		value = "reindex:promote_unknown",
		status = 400,
		when = "`promote` is neither `auto` nor `manual`."
	)
	@ReturnsError(
		value = "index:invalid_name",
		status = 400,
		when = "The path or `from` holds a name that is not a valid index or generation name."
	)
	@ReturnsError(
		value = "index:no_primary_key",
		status = 400,
		when = "The source or the target declares no primary key, so documents cannot be matched up between them."
	)
	@ReturnsError(
		value = "index:source:not_kept",
		status = 400,
		when = "The source generation keeps no copy of the documents to read them back from. A reindex reads the stored copies."
	)
	@ReturnsError(
		value = "index:not_found",
		status = 404,
		when = "No index or generation has this name, or the key holds no grant covering it."
	)
	@ReturnsError(
		value = "index:no_live_generation",
		status = 409,
		when = "The index has no live generation to read from. Promote one, or name the source with `from`."
	)
	@ReturnsError(
		value = "reindex:in_progress",
		status = 409,
		when = "A reindex job is already running for the index. Wait for it, or cancel it."
	)
	@ReturnsError(
		value = "reindex:target_busy",
		status = 409,
		when = "Another job holds the target generation."
	)
	@ReturnsError(
		value = "reindex:io_error",
		status = 409,
		when = "The record of the reindex could not be written. Send the request again once the storage responds."
	)
	@ReturnsError(
		value = "indexer:unavailable",
		status = 409,
		when = "No node is available to write the index. Send the request again once one is."
	)
	@ReturnsError(
		value = "indexer:unreachable",
		status = 502,
		when = "The request was forwarded to the index writer and the writer did not answer. Send it again."
	)
	@APIResponse(
		responseCode = "502",
		description = """
			The request was forwarded to the index writer and the writer did \
			not respond.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	public Response reindex(
		@Parameter(
			description = """
				The generation to fill, as `index@generation`. It must already \
				exist, be empty, and not be the live generation.""",
			example = "products@2"
		)
		@PathParam("name") String name,
		@RequestBody(
			required = false,
			content = @Content(
				schema = @Schema(implementation = ReindexRequest.class),
				examples = @ExampleObject(name = "job", value = ReindexRequest.EXAMPLE)
			)
		)
		ReindexRequest body
	) {
		var promote = body == null ? null : body.promote();

		/*
		 * A job left on automatic promotion calls the promote itself, so the
		 * caller is reaching `indexes.promote` through `indexes.reindex`. The
		 * filter checked the one the endpoint declares; the second is checked
		 * here, against the same name, before anything is started. Parsed first
		 * so that a value the job would refuse is still answered as a mistake in
		 * the request rather than as a refusal.
		 */
		if(
			!ReindexJobs.parsePromote(promote)
				&& !auth.principal().allows(Permission.INDEXES_PROMOTE, name)
		) {
			throw new ForbiddenException(Permission.INDEXES_PROMOTE);
		}

		var job = reindexJobs.start(name, body == null ? null : body.from(), promote);

		return Response.status(Response.Status.ACCEPTED)
			.entity(ReindexInfo.of(job))
			.build();
	}

	/**
	 * Delete the generation a request created when the reindex it asked for
	 * could not be started after all. The generation holds no documents and
	 * nothing answers from it, so taking it away leaves the deployment as the
	 * request found it and the caller can send the request again.
	 *
	 * <p>Failing to take it away is logged. The refusal the caller gets is the
	 * one the reindex answered with either way.
	 */
	private void rollBack(String name) {
		try {
			indexes.delete(name);
		} catch(IOException | RuntimeException e) {
			logger.atWarn()
				.addKeyValue("index", name)
				.setCause(e)
				.log(
					"A reindex was refused and the generation created for it could not"
						+ " be deleted, so the same request is refused as a repeat; "
						+ e.getMessage()
				);
		}
	}

	/**
	 * Build a response describing one generation of an index, tagged with the
	 * version of its definition.
	 *
	 * @param builder
	 * @param index
	 * @return
	 */
	private Response.ResponseBuilder toResponse(
		Response.ResponseBuilder builder,
		Index index
	) {
		var version = index.getDefinitionVersion();
		var name = IndexName.parse(index.getId());
		var registered = indexes.getRegistered(name.index()).orElse(null);

		return builder
			.tag(new EntityTag(version))
			.entity(
				new IndexInfo(
					name.index(),
					name.generation(),
					registered != null && name.generation().equals(registered.live()),
					version,
					IndexDefinitionMapper.toApi(index.getDefinition()),
					toStatus(index),
					registered == null ? List.of() : toGenerations(registered)
				)
			);
	}

	/**
	 * List the generations of an index, saying which one it answers from.
	 */
	private static List<GenerationSummary> toGenerations(RegisteredIndex index) {
		return index.generations()
			.collect(generation -> new GenerationSummary(
				generation.name(),
				generation.name().equals(index.live()),
				generation.createdAt() == null ? null : generation.createdAt().toString()
			))
			.toList();
	}

	private IndexStatus toStatus(Index index) {
		var createdMajor = index.getLuceneCreatedMajor();

		/*
		 * Settings this node has set aside are part of how the index answers
		 * right now, so the names of what stops them are status rather than
		 * something only the settings endpoint knows.
		 */
		var settingsUnsupported = searchSettings
			.get(IndexName.parse(index.getId()).index())
			.map(SearchSettings.Snapshot::unsupportedFeatures)
			.filter(features -> features.notEmpty())
			.map(features -> features.toList())
			.orElse(null);

		return new IndexStatus(
			index.getState(),
			index.isReadOnly(),
			indexerOf(IndexName.parse(index.getId()).index()),
			index.getLuceneCompatibility(),
			createdMajor.isPresent() ? createdMajor.getAsInt() : null,
			settingsUnsupported
		);
	}

	/**
	 * The node currently writing the index, or {@code null} when no node
	 * holds it, when who does could not be read, or when there is no shared
	 * state to name one in - a node storing locally, where {@code readOnly}
	 * already answers.
	 */
	private IndexerInfo indexerOf(String index) {
		return ownership.overview()
			.flatMap(overview -> overview.claims()
				.stream()
				.filter(claim -> claim.index().equals(index))
				.findFirst()
			)
			.map(claim -> new IndexerInfo(claim.node(), claim.address().orElse(null)))
			.orElse(null);
	}
}
