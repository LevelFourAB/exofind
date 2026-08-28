package se.l4.exofind.engine.api.v1alpha1.admin;

import se.l4.exofind.engine.api.auth.AuthContext;
import se.l4.exofind.engine.api.auth.RequiresPermission;
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
	public Response reindex(@PathParam("name") String name, ReindexRequest body) {
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
	public ReindexInfo status(@PathParam("name") String name) {
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
	public ReindexInfo cancel(@PathParam("name") String name) {
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
	public ReindexListResponse list() {
		var principal = auth.principal();

		var found = reindexes.list()
			.select(job -> principal.allows(Permission.INDEXES_READ, job.index()))
			.collect(ReindexInfo::of)
			.toSortedListBy(ReindexInfo::index);

		return new ReindexListResponse(found);
	}
}
