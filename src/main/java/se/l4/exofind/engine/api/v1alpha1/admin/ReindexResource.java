package se.l4.exofind.engine.api.v1alpha1.admin;

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

import se.l4.exofind.engine.api.ExofindApi;
import se.l4.exofind.engine.api.auth.AuthContext;
import se.l4.exofind.engine.api.auth.RequiresPermission;
import se.l4.exofind.engine.api.errors.ErrorResponse;
import se.l4.exofind.engine.api.errors.ReturnsError;
import se.l4.exofind.engine.api.routing.ServedBy;
import se.l4.exofind.engine.api.v1alpha1.admin.model.ReindexInfo;
import se.l4.exofind.engine.api.v1alpha1.admin.model.ReindexListResponse;
import se.l4.exofind.engine.api.v1alpha1.admin.model.ReindexRequest;
import se.l4.exofind.engine.auth.Permission;
import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.reindex.ReindexJobs;
import se.l4.exofind.engine.reindex.ReindexNotFoundException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Populates a new generation by copying documents from an existing generation
 * of the same index inside the engine.
 *
 * <p>Reindexing runs as an asynchronous background job on the node holding the
 * index. An index runs at most one reindex job at a time; a finished job record
 * remains readable until a new job replaces it.
 *
 * <p>Status requests are served from durable job records by any node. Starting
 * and cancelling requests run on the node holding the index.
 */
@Tag(
	name = "Reindexes",
	description = """
		Populates a new generation by copying documents from an existing \
		generation inside the engine.""",
	externalDocs = @ExternalDocumentation(
		description = "Reindex reference",
		url = "https://exofind.dev/reference/admin-api/#reindex"
	)
)
@SecurityRequirement(name = ExofindApi.API_KEY)
@Path("/v1alpha1/admin")
@Produces(MediaType.APPLICATION_JSON)
public class ReindexResource {
	private final ReindexJobs reindexes;
	private final AuthContext auth;

	public ReindexResource(ReindexJobs reindexes, AuthContext auth) {
		this.reindexes = reindexes;
		this.auth = auth;
	}

	/**
	 * Starts a reindex job to populate a generation from another generation of
	 * the same index.
	 *
	 * <p><p>The target generation must be specified by name, must be empty, and
	 * must not be live. The source generation defaults to the live generation.
	 *
	 * <p><p>The job promotes the target generation once caught up unless
	 * configured with {@code "promote": "manual"}. When manual, the job pauses
	 * in the ready phase until {@code actions/promote} on the target completes
	 * it.
	 *
	 * @param name
	 *   the generation to fill, as {@code index@generation}
	 * @param body
	 *   configuration specifying the source generation and promotion mode, or
	 *   omitted for defaults
	 * @return
	 */
	@POST
	@Path("/indexes/{name}/actions/reindex")
	@Consumes(MediaType.APPLICATION_JSON)
	@RequiresPermission(Permission.INDEXES_REINDEX)
	@ServedBy(ServedBy.Node.INDEXER)
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

			An index can run at most one reindex job at a time. A finished \
			job's record remains readable until a new job replaces it."""
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
		value = "index:not-found",
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
		var job = reindexes.start(
			name,
			body == null ? null : body.from(),
			body == null ? null : body.promote()
		);

		return Response.status(Response.Status.ACCEPTED)
			.entity(ReindexInfo.of(job))
			.build();
	}

	/**
	 * Returns the status of a reindex job on an index, including finished jobs.
	 *
	 * <p>Served from the durable job record, so any node returns the same
	 * response.
	 *
	 * @param name
	 *   the index, or one generation of it; the job belongs to the index either
	 *   way
	 * @return
	 */
	@GET
	@Path("/indexes/{name}/actions/reindex")
	@RequiresPermission(Permission.INDEXES_READ)
	@Operation(
		operationId = "getReindex",
		summary = "Get reindex job status",
		description = """
			Returns the status of a reindex job on an index, including \
			finished jobs. Served from the durable job record, so any node can \
			serve the request and returns the same response.

			If no job exists for the index, the server returns `404` with the \
			error code `reindex:not_found`."""
	)
	@APIResponse(
		responseCode = "200",
		description = "The job record.",
		content = @Content(
			schema = @Schema(implementation = ReindexInfo.class),
			examples = @ExampleObject(name = "job", value = ReindexInfo.EXAMPLE)
		)
	)
	@APIResponse(
		responseCode = "404",
		description = """
			No job exists for this index, the index does not exist, or the \
			caller key lacks permissions on the index.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = "The job record could not be read.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@ReturnsError(
		value = "reindex:not_found",
		status = 404,
		when = "The index has no reindex job."
	)
	@ReturnsError(
		value = "index:not-found",
		status = 404,
		when = "No index or generation has this name, or the key holds no grant covering it."
	)
	@ReturnsError(
		value = "reindex:io_error",
		status = 409,
		when = "The record of the reindex could not be read. Send the request again once the storage responds."
	)
	public ReindexInfo status(
		@Parameter(
			description = """
				The index, or one generation of it. The job belongs to the \
				index in either case.""",
			example = "products"
		)
		@PathParam("name") String name
	) {
		var index = IndexName.parse(name).index();

		return reindexes.get(index)
			.map(ReindexInfo::of)
			.orElseThrow(() -> new ReindexNotFoundException(index));
	}

	/**
	 * Cancels an in-progress reindex job.
	 *
	 * <p>Leaves the partially populated target generation in place. Cancelling
	 * a finished job changes nothing.
	 *
	 * @param name
	 *   the index, or one generation of it
	 * @return
	 */
	@POST
	@Path("/indexes/{name}/actions/reindex/cancel")
	@RequiresPermission(Permission.INDEXES_REINDEX)
	@ServedBy(ServedBy.Node.INDEXER)
	@Operation(
		operationId = "cancelReindex",
		summary = "Cancel a reindex job",
		description = """
			Stops an in-progress job. Tracking on the source ends and the \
			partially populated target generation is left in place, to be \
			removed with `DELETE /v1alpha1/admin/indexes/{target}`. Cancelling \
			a finished job changes nothing.

			Runs on the node holding the index."""
	)
	@APIResponse(
		responseCode = "200",
		description = "The job record as it stands after cancellation.",
		content = @Content(
			schema = @Schema(implementation = ReindexInfo.class),
			examples = @ExampleObject(name = "job", value = ReindexInfo.EXAMPLE)
		)
	)
	@APIResponse(
		responseCode = "404",
		description = """
			No job exists for this index, the index does not exist, or the \
			caller key lacks permissions on the index.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = "No node is available to write the index.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@ReturnsError(
		value = "reindex:not_found",
		status = 404,
		when = "The index has no reindex job."
	)
	@ReturnsError(
		value = "index:not-found",
		status = 404,
		when = "No index or generation has this name, or the key holds no grant covering it."
	)
	@ReturnsError(
		value = "indexer:unavailable",
		status = 409,
		when = "No node is available to write the index. Send the request again once one is."
	)
	@ReturnsError(
		value = "index:no_live_generation",
		status = 409,
		when = "The index has no live generation. Promote one and send the request again."
	)
	@ReturnsError(
		value = "reindex:io_error",
		status = 409,
		when = "The record of the reindex could not be written. Send the request again once the storage responds."
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
	public ReindexInfo cancel(
		@Parameter(
			description = "The index, or one generation of it.",
			example = "products"
		)
		@PathParam("name") String name
	) {
		return ReindexInfo.of(reindexes.cancel(IndexName.parse(name).index()));
	}

	/**
	 * Lists every reindex job across the deployment, including finished jobs.
	 *
	 * <p>Jobs on indexes where the caller lacks permissions are omitted.
	 *
	 * @return
	 */
	@GET
	@Path("/reindexes")
	@RequiresPermission(value = Permission.INDEXES_READ, anyIndex = true)
	@Operation(
		operationId = "listReindexes",
		summary = "List reindex jobs",
		description = """
			Lists every reindex job across the deployment, including finished \
			jobs, ordered by index name. Served from durable job records, so \
			any node can serve the request and returns the same response.

			Jobs on indexes where the key lacks permissions are omitted rather \
			than refused."""
	)
	@APIResponse(
		responseCode = "200",
		description = "The job records visible to the key, ordered by index name.",
		content = @Content(
			schema = @Schema(implementation = ReindexListResponse.class),
			examples = @ExampleObject(name = "jobs", value = ReindexListResponse.EXAMPLE)
		)
	)
	@APIResponse(
		responseCode = "409",
		description = "The job records could not be read.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@ReturnsError(
		value = "reindex:io_error",
		status = 409,
		when = "The records of the reindexes could not be read. Send the request again once the storage responds."
	)
	public ReindexListResponse list() {
		var principal = auth.principal();

		var found = reindexes.list()
			.select(job -> principal.allows(Permission.INDEXES_READ, job.index()))
			.collect(ReindexInfo::of)
			.toSortedListBy(ReindexInfo::index);

		return new ReindexListResponse(found);
	}
}
