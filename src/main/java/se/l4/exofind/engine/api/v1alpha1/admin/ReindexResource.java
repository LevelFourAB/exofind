package se.l4.exofind.engine.api.v1alpha1.admin;

import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
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
 * Filling a new generation by reindexing from the one it replaces, without
 * the documents leaving the engine.
 *
 * A reindex is a job rather than a request: starting one answers at once, the
 * work runs on the node writing the index, and where it stands is read back
 * from a record any node answers the same way - status is served wherever it
 * lands. An index has at most one job at a time, and a finished job stays
 * readable until the next one replaces it.
 *
 * Starting and cancelling run on the indexer, like every change to an index -
 * the job reads and writes both generations, which only their writer holds
 * open for writing.
 */
@Tag(
	name = "Reindexes",
	description = "Fills a new generation from an existing one, inside the engine.",
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
	 * Fill a generation from another generation of its index. The target has
	 * to be a generation by name, empty, and not the one the index answers
	 * for; the source defaults to the one that is.
	 *
	 * <p>The job promotes the target itself once it has caught up, unless the
	 * request says {@code "promote": "manual"} - the job then stops in the
	 * ready phase and {@code actions/promote} on the target finishes it.
	 *
	 * @param name
	 *   the generation to fill, as {@code index@generation}
	 * @param body
	 *   what to read from and who promotes, or nothing for the defaults
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
			Starts a job that fills the target generation by copying documents \
			from another generation of the same index inside the engine, \
			without the documents leaving it. The request answers at once with \
			the job record; the work runs in the background on the node \
			holding the index.

			The target must name a generation, already exist, be empty, and \
			not be the live one. The source must have a primary key and keep \
			document sources, and the primary keys of source and target must \
			share a field name and type. Anything else answers `400`.

			The job promotes the target itself once it has caught up, unless \
			the body says `"promote": "manual"` - the job then stops in the \
			`ready` phase, keeping the target caught up, until \
			`actions/promote` on the target finishes it.

			An index runs at most one job at a time, and a finished job's \
			record stays readable until a new job replaces it. Requires the \
			`indexes.reindex` permission."""
	)
	@APIResponse(
		responseCode = "202",
		description = "The job was started and runs in the background.",
		content = @Content(schema = @Schema(implementation = ReindexInfo.class))
	)
	@APIResponse(
		responseCode = "400",
		description = """
			The target does not name a generation, does not exist, is not \
			empty, is the live generation, or the source and target primary \
			keys do not match.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "401",
		description = "The request carries no credential this node accepts.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The API key does not have the `indexes.reindex` permission.",
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
			A job is already running for this index (`reindex:in_progress`), \
			the target is locked by an existing job (`reindex:target_busy`), \
			or no node is available to write the index.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "502",
		description = "The index writer did not respond to the forwarded request.",
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
	 * Get where the reindex of an index stands, finished ones included.
	 *
	 * <p>Answered from the job's record wherever the request lands, so every
	 * node gives the same checkpoint-granular answer.
	 *
	 * @param name
	 *   the index, or one generation of it - the job belongs to the index
	 *   either way
	 * @return
	 */
	@GET
	@Path("/indexes/{name}/actions/reindex")
	@RequiresPermission(Permission.INDEXES_READ)
	@Operation(
		operationId = "getReindex",
		summary = "Get reindex job status",
		description = """
			Returns where the reindex of an index stands, finished jobs \
			included. Answered from the job's durable record wherever the \
			request lands, so every node gives the same answer.

			If no job exists for the index, answers `404` with \
			`reindex:not_found`. Requires the `indexes.read` permission."""
	)
	@APIResponse(
		responseCode = "200",
		description = "The job record.",
		content = @Content(schema = @Schema(implementation = ReindexInfo.class))
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
			No job exists for this index (`reindex:not_found`), no index has \
			this name, or the key has no grant covering it.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	public ReindexInfo status(
		@Parameter(
			description = """
				The index, or one generation of it - the job belongs to the \
				index either way.""",
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
	 * Stop the reindex of an index. Tracking on the source ends and the
	 * partially filled target is left as it is, for a normal generation
	 * delete. Cancelling a job that already finished changes nothing.
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
			partially filled target generation is left in place, to be removed \
			with `DELETE /v1alpha1/admin/indexes/{target}` like any other \
			generation. Cancelling a job that already finished changes \
			nothing.

			Runs on the node that writes the index. Requires the \
			`indexes.reindex` permission."""
	)
	@APIResponse(
		responseCode = "200",
		description = "The job record as it stands after cancelling.",
		content = @Content(schema = @Schema(implementation = ReindexInfo.class))
	)
	@APIResponse(
		responseCode = "401",
		description = "The request carries no credential this node accepts.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The API key does not have the `indexes.reindex` permission.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "404",
		description = """
			No job exists for this index (`reindex:not_found`), no index has \
			this name, or the key has no grant covering it.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = "No node is available to write the index.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "502",
		description = "The index writer did not respond to the forwarded request.",
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
	 * List every reindex there is a record of, across the deployment,
	 * finished ones included.
	 *
	 * <p>A job on an index no grant of the caller's key covers is left out
	 * rather than refused, the way the index listing leaves such indexes out.
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
			Lists every reindex job the deployment has a record of, finished \
			ones included, ordered by index name. Served from the durable job \
			records, so any node answers the same way.

			A job on an index no grant of the calling key covers is left out \
			rather than refused, the way the index listing leaves such indexes \
			out. Requires the `indexes.read` permission."""
	)
	@APIResponse(
		responseCode = "200",
		description = "The job records the key can see, ordered by index name.",
		content = @Content(schema = @Schema(implementation = ReindexListResponse.class))
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
	public ReindexListResponse list() {
		var principal = auth.principal();

		var found = reindexes.list()
			.select(job -> principal.allows(Permission.INDEXES_READ, job.index()))
			.collect(ReindexInfo::of)
			.toSortedListBy(ReindexInfo::index);

		return new ReindexListResponse(found);
	}
}
