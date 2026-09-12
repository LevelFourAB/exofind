package se.l4.exofind.engine.api.v1alpha1.admin;

import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
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
import se.l4.exofind.engine.auth.Permission;
import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.reindex.ReindexJobs;
import se.l4.exofind.engine.reindex.ReindexNotFoundException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The record of a reindex job, which populates a new generation by copying
 * documents from an existing generation of the same index inside the engine.
 *
 * <p>Reindexing runs as an asynchronous background job on the node holding the
 * index. An index runs at most one reindex job at a time; a finished job record
 * remains readable until a new job replaces it.
 *
 * <p>Status requests are served from durable job records by any node.
 * Cancelling runs on the node holding the index.
 *
 * <p>A job belongs to an index, and the name in these paths is that index, or
 * one generation of it. Starting a job is an action on the generation the job
 * fills rather than on the job, so it is {@link IndexResource} that answers it,
 * under {@code /indexes/{name}/actions/reindex}. A path under the one a
 * resource class is served at is answered by that class alone, which is the
 * other reason the start lives there.
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
@Path("/v1alpha1/admin/reindexes")
@Produces(MediaType.APPLICATION_JSON)
public class ReindexResource {
	private final ReindexJobs reindexes;
	private final AuthContext auth;

	public ReindexResource(ReindexJobs reindexes, AuthContext auth) {
		this.reindexes = reindexes;
		this.auth = auth;
	}

	/**
	 * Lists every reindex job across the deployment, including finished jobs.
	 *
	 * <p>Jobs on indexes where the caller lacks permissions are omitted.
	 *
	 * @return
	 */
	@GET
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
	@Path("/{name}")
	@RequiresPermission(Permission.INDEXES_READ)
	@Operation(
		operationId = "getReindex",
		summary = "Get reindex job status",
		description = """
			Returns the status of a reindex job on an index, including \
			finished jobs. Served from the durable job record, so any node can \
			serve the request and returns the same response.

			The name is the index the job belongs to, or one generation of \
			that index. If no job exists for the index, the server returns \
			`404` with the error code `reindex:not_found`."""
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
		value = "index:not_found",
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
	 * <p>Leaves the partially populated target generation in place, and leaves
	 * the job record readable, which is why cancelling is an action rather than
	 * a removal of the job. Cancelling a finished job changes nothing.
	 *
	 * @param name
	 *   the index, or one generation of it
	 * @return
	 */
	@POST
	@Path("/{name}/actions/cancel")
	@RequiresPermission(Permission.INDEXES_REINDEX)
	@ServedBy(ServedBy.Node.INDEXER)
	@Operation(
		operationId = "cancelReindex",
		summary = "Cancel a reindex job",
		description = """
			Stops an in-progress job. Tracking on the source ends and the \
			partially populated target generation is left in place, to be \
			removed with `DELETE /v1alpha1/admin/indexes/{target}`. The job \
			record stays readable and reports the `cancelled` phase. \
			Cancelling a finished job changes nothing.

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
}
