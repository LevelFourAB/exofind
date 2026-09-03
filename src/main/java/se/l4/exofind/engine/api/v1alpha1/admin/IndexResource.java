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

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.api.ExofindApi;
import se.l4.exofind.engine.api.auth.AuthContext;
import se.l4.exofind.engine.api.auth.RequiresPermission;
import se.l4.exofind.engine.api.errors.ErrorResponse;
import se.l4.exofind.engine.api.errors.UnrepresentableStateException;
import se.l4.exofind.engine.api.routing.ServedBy;
import se.l4.exofind.engine.api.v1alpha1.admin.model.GenerationSummary;
import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexInfo;
import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexListResponse;
import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexStatus;
import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexerInfo;
import se.l4.exofind.engine.auth.Permission;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.IndexDefinitionIncompatibleException;
import se.l4.exofind.engine.index.IndexException;
import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.IndexNotFoundException;
import se.l4.exofind.engine.index.registry.RegisteredIndex;
import se.l4.exofind.engine.index.settings.SearchSettings;
import se.l4.exofind.engine.index.state.IndexerOwnership;
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
			rather than refusing the listing.

			Requires the `indexes.read` permission."""
	)
	@APIResponse(
		responseCode = "200",
		description = "The indexes the key can see, ordered by name.",
		content = @Content(
			schema = @Schema(implementation = IndexListResponse.class),
			examples = @ExampleObject(name = "indexes", value = IndexListResponse.EXAMPLE)
		)
	)
	@APIResponse(
		responseCode = "401",
		description = "The request carries no credential this node accepts.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The API key does not have the `indexes.read` permission.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
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
			overwriting concurrent updates.

			Requires the `indexes.read` permission."""
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
		responseCode = "401",
		description = "The request carries no credential this node accepts.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The API key does not have the `indexes.read` permission.",
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
		description = """
			The stored definition holds settings this API version cannot \
			represent (`index:definition:unrepresentable`), or the index needs \
			engine features this node does not have (`index:unsupported`). \
			Send the request to a node running a version that supports it.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "503",
		description = """
			The request raced the index being closed. Retrying the request \
			reopens the index.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
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
	 *   request fails instead of overwriting concurrent changes
	 * @param reindex
	 *   promotion mode ({@code auto} or {@code manual}) to start a reindex job
	 *   populating the new generation from the live generation. Only valid when
	 *   creating a generation
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
			by another node is forwarded there. Requires the `indexes.write` \
			permission."""
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
			problem - or `reindex` was given on a request that creates no \
			generation (`index:reindex_needs_new_generation`).""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "401",
		description = "The request carries no credential this node accepts.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The API key does not have the `indexes.write` permission.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
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
				`ETag` header. `*` matches any existing version. If the \
				version no longer matches, the server returns `412` instead of \
				overwriting intermediate changes.""",
			example = "\"9f2c1a0b3d4e5f60\""
		)
		@HeaderParam("If-Match") String ifMatch,
		@Parameter(
			description = """
				Starts a reindex job filling the generation being created from \
				the live one, the way the reindex action would. One-shot: it \
				is not stored in the definition, and it is refused on a \
				request that creates no generation.""",
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

		// A value the job would refuse is refused before anything is created
		if(reindex != null) {
			ReindexJobs.parsePromote(reindex);
		}

		var existing = indexes.get(name);

		if(existing.isEmpty()) {
			/*
			 * There is nothing to be told about a conflict with, so an
			 * expected version can not be satisfied.
			 */
			if(expectedVersion(ifMatch) != null) {
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
				reindexJobs.start(name, null, reindex);
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

		try {
			index.updateDefinition(stored, expectedVersion(ifMatch), allowStaleDocuments);
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
			another node receives it. Requires the `indexes.delete` \
			permission."""
	)
	@APIResponse(
		responseCode = "204",
		description = "The index or generation was removed."
	)
	@APIResponse(
		responseCode = "401",
		description = "The request carries no credential this node accepts.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The API key does not have the `indexes.delete` permission.",
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
		description = """
			The generation is the live one (`index:generation:is_live`), no \
			node is available to write the index, or the registry write \
			failed.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "502",
		description = "The index writer did not respond to the forwarded request.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
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
			`reindex:target_busy`.

			Requires the `indexes.promote` permission."""
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
		description = """
			The path names no generation \
			(`index:generation:name_required`).""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "401",
		description = "The request carries no credential this node accepts.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The API key does not have the `indexes.promote` permission.",
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
		description = """
			A reindex job is still filling this generation \
			(`reindex:target_busy`), no node is available to write the index, \
			or the registry write failed.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
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
			generation if omitted. Runs on the node that writes the index. \
			Requires the `indexes.commit` permission."""
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
		responseCode = "401",
		description = "The request carries no credential this node accepts.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The API key does not have the `indexes.commit` permission.",
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
		description = """
			No node is available to write the index, or this node lost the \
			writer role while serving the request (`index:readonly`).""",
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
			forwarded.

			Requires the `indexes.pull` permission."""
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
		responseCode = "401",
		description = "The request carries no credential this node accepts.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The API key does not have the `indexes.pull` permission.",
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
		responseCode = "503",
		description = """
			The request raced the index being closed. Retrying the request \
			reopens the index.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
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
	 * Read the version an {@code If-Match} header asks for. {@code *} matches
	 * any version, which is the same as not checking at all here as the index
	 * is known to exist by the time the version is used.
	 *
	 * @param ifMatch
	 * @return
	 *   the version, or {@code null} when no particular version is expected
	 */
	private static String expectedVersion(String ifMatch) {
		if(ifMatch == null) {
			return null;
		}

		var value = ifMatch.trim();
		if(value.isEmpty() || value.equals("*")) {
			return null;
		}

		if(value.startsWith("W/")) {
			value = value.substring(2);
		}

		if(value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
			value = value.substring(1, value.length() - 1);
		}

		return value;
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
