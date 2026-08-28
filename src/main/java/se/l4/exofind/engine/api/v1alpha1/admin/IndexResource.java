package se.l4.exofind.engine.api.v1alpha1.admin;

import java.io.IOException;
import java.util.List;

import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
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
 * Administration of the indexes on this node - creating them, changing what
 * they contain and synchronizing them with the remote.
 *
 * Definitions are handled as desired state: a definition is sent in full and
 * replaces the previous one, so the same request can be repeated without
 * changing the outcome. The version of a definition is returned as an
 * {@code ETag} and can be sent back as {@code If-Match} to be told that
 * someone else has changed the index instead of overwriting their change.
 *
 * An index holds generations, and a definition belongs to a generation rather
 * than to the index. Every endpoint here takes either the index - which means
 * the generation it currently answers from - or one generation by name, written
 * {@code books@2}. A definition that the documents already indexed were not
 * indexed under is rolled out by creating a generation, filling it and
 * promoting it, rather than by changing an index in place: the name callers use
 * never changes, so nothing they hold has to be updated when it happens.
 *
 * Everything here that changes an index - its definition, its generations,
 * which one it answers from - runs on the indexer, and a request that reaches
 * another node is passed along to it. The registry itself could be written
 * from anywhere, being one object replaced conditionally, but routing every
 * change about a name through the node that writes the name keeps "one writer
 * per index" true for all of it rather than only for the documents.
 *
 * Indexing and searching documents are not part of this API.
 */
@Tag(
	name = "Indexes",
	description = "Defines, reads and deletes indexes and their generations.",
	externalDocs = @ExternalDocumentation(
		description = "Admin API reference",
		url = "https://levelfourab.github.io/exofind/reference/admin-api/"
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
	 * List the indexes available on this node that the caller has been granted
	 * something on.
	 *
	 * <p>An index no grant of the caller's key covers is left out rather than
	 * refused, the same way asking for it directly answers that there is no such
	 * index - a listing is not a way around what a key can see.
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
			which one each answers for.

			An index no grant of the calling key covers is left out rather \
			than refused, the same way asking for it directly answers that \
			there is no such index - a listing is not a way around what a key \
			can see.

			Requires the `indexes.read` permission."""
	)
	@APIResponse(
		responseCode = "200",
		description = "The indexes the key can see, ordered by name.",
		content = @Content(schema = @Schema(implementation = IndexListResponse.class))
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
	 * Get an index, its definition and its current status.
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
			generation the name answers for, every generation it holds, and \
			the status the answering node observes.

			The definition's version is returned in the `ETag` header. Send it \
			back as `If-Match` on a `PUT` to be told that someone else changed \
			the index rather than overwriting their change.

			Requires the `indexes.read` permission."""
	)
	@APIResponse(
		responseCode = "200",
		description = """
			The index, with its version in the `ETag` header. Presets are \
			stored expanded, so the definition comes back as the expanded \
			chain rather than the preset name.""",
		content = @Content(schema = @Schema(implementation = IndexInfo.class))
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
		description = "The request raced the index being closed. Send it again.",
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
	 * Create an index, add a generation to one, or replace the definition of
	 * something that is already there.
	 *
	 * <p>Which of those happens is decided by the name. {@code books} creates
	 * the index with a first generation, or replaces the definition of the
	 * generation it answers from; {@code books@2} adds that generation to an
	 * index that already exists, or replaces its definition. A generation
	 * created here holds no documents and the index goes on answering from the
	 * one it had, until {@code actions/promote} says otherwise.
	 *
	 * <p>Replacing the definition of a generation that holds documents is
	 * refused where the change would reach none of them - a usage turned on for
	 * a field, a different analysis chain, an edited synonym set. Such a change
	 * is rolled out through a generation instead, which is what keeps a search
	 * from quietly answering with less than it should.
	 *
	 * @param name
	 *   the index, or one generation of it
	 * @param ifMatch
	 *   version the definition is expected to have, as returned by the
	 *   {@code ETag} of a previous request. When given and the index has since
	 *   been changed the request fails instead of overwriting that change
	 * @param reindex
	 *   {@code auto} or {@code manual} to also fill the generation being
	 *   created from the live one, the way the reindex action would - sugar
	 *   over the action, for creating a generation only if the reindex is
	 *   wanted. Only meaningful when a generation is created
	 * @param allowStaleDocuments
	 *   {@code true} to store a definition the documents already indexed were
	 *   not indexed under, for the case where they are about to be sent again
	 *   anyway. They go on answering the way they were indexed until they are
	 *   sent
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
			Sends a definition in full, replacing any previous one, so \
			repeating the request produces the same outcome. Any setting the \
			body omits is removed.

			What the request does is decided by the name. `books` creates the \
			index with an initial generation named `1`, or updates the \
			definition of the live generation; `books@2` creates that \
			generation under an existing index, or updates its definition. A \
			newly created generation holds no documents and is not live - the \
			index keeps answering from the generation it had until \
			`actions/promote` says otherwise, and `PUT books@2` on an index \
			that does not exist answers `404`.

			On a generation that already holds documents, a change that would \
			reach none of them is refused with `409` and \
			`index:definition:incompatible` - a usage enabled on an existing \
			field, a changed analysis chain, an edited synonym set, a changed \
			`type`, `primaryKey` or `multiple`. The response carries one \
			detail per difference, each with the `path` of the field that \
			caused it. Adding or removing a field, disabling a usage, and \
			changing `stored`, `source`, `metadata`, `ranking` or the \
			search-time settings are all accepted.

			Runs on the node that writes the index; a request that reaches \
			another node is forwarded there. Requires the `indexes.write` \
			permission."""
	)
	@APIResponse(
		responseCode = "200",
		description = """
			An existing definition was replaced. The new version is in the \
			`ETag` header.""",
		content = @Content(schema = @Schema(implementation = IndexInfo.class))
	)
	@APIResponse(
		responseCode = "201",
		description = """
			The index or generation was created. The version is in the `ETag` \
			header and the location in `Location`.""",
		content = @Content(schema = @Schema(implementation = IndexInfo.class))
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
			The definition conflicts with the documents the generation holds \
			(`index:definition:incompatible`), the stored definition holds \
			settings this API version cannot represent \
			(`index:definition:unrepresentable`), the index needs engine \
			features this node does not have (`index:unsupported`), a reindex \
			job is already running (`reindex:in_progress`), no node is \
			available to write the index (`indexer:unavailable`), or the \
			registry write failed.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "412",
		description = """
			The `If-Match` version does not match the stored definition. Read \
			the index again and rebuild the change on the version that comes \
			back.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "502",
		description = "The index writer did not respond to the forwarded request.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "503",
		description = "The request raced the index being closed. Send it again.",
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
				Version the definition is expected to be at, as returned by a \
				previous response's `ETag`. `*` matches any existing version. \
				A version that no longer matches answers `412` instead of \
				overwriting the change that moved it.""",
			example = "\"9f2c1a0b3d4e5f60\""
		)
		@HeaderParam("If-Match") String ifMatch,
		@Parameter(
			description = """
				Also start a reindex job filling the generation being created \
				from the live one, the way the reindex action would. One-shot: \
				it is not stored in the definition, and it is refused on a \
				request that creates no generation.""",
			schema = @Schema(enumeration = {"auto", "manual"})
		)
		@QueryParam("reindex") String reindex,
		@Parameter(
			description = """
				Store a definition the documents already indexed were not \
				indexed under, for the case where they are about to be sent \
				again anyway. They go on answering the way they were indexed \
				until they are. No effect on an empty generation.""",
			schema = @Schema(type = SchemaType.BOOLEAN, defaultValue = "false")
		)
		@QueryParam("allowStaleDocuments") @DefaultValue("false") boolean allowStaleDocuments,
		@Context UriInfo uriInfo,
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
	 * Delete an index and every generation of it, or one generation on its own.
	 *
	 * <p>The index is taken out of the registry, so it is gone for the whole
	 * deployment rather than only for this node - every node removes its own
	 * copy when it next reads the registry. What the remote holds under it is
	 * not removed. The generation an index answers from is refused, so an index
	 * is never left answering for nothing.
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
			Deleting `books` removes the index and every generation of it; \
			deleting `books@2` removes only that generation. Deleting the live \
			generation is refused with `index:generation:is_live` until \
			another one is promoted, so an index is never left answering for \
			nothing.

			The entry is removed from the registry the deployment shares, so \
			it is gone everywhere rather than only on this node - other nodes \
			drop their local copies at their next registry read. What remote \
			storage holds under it is not removed, so an index created again \
			under the same name picks its old search settings back up.

			Requires the `indexes.delete` permission."""
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
				The index, which removes every generation of it, or one \
				generation by name such as `books@2`.""",
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
	 * Make an index answer from one of its generations, which is how a rebuilt
	 * index is rolled out.
	 *
	 * <p>Callers using the index by name read the promoted generation from here
	 * on - on this node at once, and on every other node within its refresh
	 * interval. Nothing they hold changes, so this is also how a rollout is
	 * undone: promote the generation that was answering before.
	 *
	 * <p>A generation a reindex is filling is promoted through the job: one
	 * that says it is ready is drained and promoted complete, one that has
	 * not caught up yet is refused - promoting a partial copy is what the job
	 * exists to prevent.
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
			Makes the index answer from the named generation, which is how a \
			rebuilt index is rolled out. Callers using the bare index name \
			read the promoted generation from here on - on the answering node \
			at once, and on every other node within \
			`EXOFIND_INDEXES_REFRESH_INTERVAL`. Nothing the callers hold \
			changes, so \
			this is also how a rollout is undone: promote the generation that \
			was answering before.

			The path must name a generation; calling it on a bare index name \
			returns `index:generation:name_required`. A generation being \
			filled by a reindex job is promoted through the job - one in the \
			`ready` phase is drained and promoted complete, one that has not \
			caught up is refused with `reindex:target_busy`.

			Requires the `indexes.promote` permission."""
	)
	@APIResponse(
		responseCode = "200",
		description = "The index now answers from this generation.",
		content = @Content(schema = @Schema(implementation = IndexInfo.class))
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
	 * Commit pending changes to an index and push them to the remote.
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
			Commits whatever documents and definition changes are waiting and \
			pushes them to remote storage, making them searchable. The writer \
			commits on its own once enough has been indexed or enough time has \
			passed, so this is for committing at once - loading a dataset is \
			many indexing requests and one commit at the end, rather than a \
			commit per batch.

			Acts on the generation the path names, or the live one when it \
			names none. Runs on the node that writes the index. Requires the \
			`indexes.commit` permission."""
	)
	@APIResponse(
		responseCode = "200",
		description = "The resulting status of the index.",
		content = @Content(schema = @Schema(implementation = IndexStatus.class))
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
		description = "The request raced the index being closed. Send it again.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	public IndexStatus commit(
		@Parameter(
			description = """
				The index, which commits the generation it answers for, or one \
				generation by name such as `books@2`.""",
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
	 * Pull the latest state of an index from the remote.
	 *
	 * <p>A pull updates the copy on the node serving it, so it runs wherever
	 * it lands - sending it to the indexer would refresh the one node that is
	 * already current.
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
			Fetches the latest remote state at once instead of waiting for \
			`EXOFIND_INDEXES_REFRESH_INTERVAL`, and answers with the resulting \
			status.

			A pull updates the copy held by the node serving it, so it runs \
			wherever it lands and is never forwarded - sending it to the index \
			writer would only refresh the one node that is already current.

			Requires the `indexes.pull` permission."""
	)
	@APIResponse(
		responseCode = "200",
		description = "The resulting status of the index on this node.",
		content = @Content(schema = @Schema(implementation = IndexStatus.class))
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
		description = "The request raced the index being closed. Send it again.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	public IndexStatus pull(
		@Parameter(
			description = """
				The index, which pulls the generation it answers for, or one \
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
