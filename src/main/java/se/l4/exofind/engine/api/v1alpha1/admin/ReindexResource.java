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
			job's record remains readable until a new job replaces it. \
			Requires the `indexes.reindex` permission."""
	)
	@APIResponse(
		responseCode = "202",
		description = "A reindex job was started and runs asynchronously.",
		content = @Content(
			schema = @Schema(implementation = ReindexInfo.class),
			examples = @ExampleObject(name = "job", value = ReindexInfo.EXAMPLE)
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
		responseCode = "401",
		description = "The request carries no credential accepted by this node.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The credential does not have the `indexes.reindex` permission.",
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
		description = """
			A reindex job is already in progress (`reindex:in_progress`), the \
			target generation is busy being reindexed (`reindex:target_busy`), \
			or no node is available to write the index.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
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
		@RequestBody(content = @Content(
			schema = @Schema(implementation = ReindexRequest.class),
			examples = @ExampleObject(name = "job", value = ReindexRequest.EXAMPLE)
		))
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
			error code `reindex:not_found`. Requires the `indexes.read` \
			permission."""
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
		responseCode = "401",
		description = "The request carries no credential accepted by this node.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The credential does not have the `indexes.read` permission.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "404",
		description = """
			No job exists for this index (`reindex:not_found`), the index does \
			not exist, or the caller key lacks permissions on the index.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
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

			Runs on the node holding the index. Requires the `indexes.reindex` \
			permission."""
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
		responseCode = "401",
		description = "The request carries no credential accepted by this node.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The credential does not have the `indexes.reindex` permission.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "404",
		description = """
			No job exists for this index (`reindex:not_found`), the index does \
			not exist, or the caller key lacks permissions on the index.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = "No node is available to write the index.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
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
			than refused. Requires the `indexes.read` permission."""
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
		responseCode = "401",
		description = "The request carries no credential accepted by this node.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The credential does not have the `indexes.read` permission.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
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
