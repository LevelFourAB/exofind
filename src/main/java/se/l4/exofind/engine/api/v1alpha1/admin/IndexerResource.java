package se.l4.exofind.engine.api.v1alpha1.admin;

import se.l4.exofind.engine.api.auth.AuthContext;
import se.l4.exofind.engine.api.auth.RequiresPermission;
import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexerListResponse;
import se.l4.exofind.engine.auth.Permission;
import se.l4.exofind.engine.index.state.IndexerLeadershipUnreadableException;
import se.l4.exofind.engine.index.state.IndexerOwnership;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Which node writes which index, for the operator asking what the logs and
 * the storage otherwise hold alone.
 *
 * <p>Any node answers, from its own read of the state the deployment shares -
 * a node that only searches included. The answer therefore lags reality by a
 * few seconds, the same way write forwarding does: a claim that just moved may
 * still name the old node for a moment.
 *
 * <p>A node storing locally answers with nothing in either list. It is the
 * only node there is and writes everything, which {@code readOnly} on each
 * index already says.
 */
@Path("/v1alpha1/admin/indexers")
@Produces(MediaType.APPLICATION_JSON)
public class IndexerResource {
	private final IndexerOwnership ownership;
	private final AuthContext auth;

	public IndexerResource(IndexerOwnership ownership, AuthContext auth) {
		this.ownership = ownership;
		this.auth = auth;
	}

	/**
	 * List the nodes competing to write indexes and, per index some node
	 * writes, which node that is.
	 *
	 * <p>A claim on an index no grant of the caller's key covers is left out,
	 * the same way listing the indexes leaves the index out - this listing is
	 * not a way around what a key can see. The candidates name no index and
	 * are listed whole.
	 *
	 * @return
	 */
	@GET
	@RequiresPermission(value = Permission.INDEXES_READ, anyIndex = true)
	public IndexerListResponse list() {
		var overview = ownership.overview()
			.orElseThrow(IndexerLeadershipUnreadableException::new);

		var principal = auth.principal();

		var candidates = overview.candidates()
			.stream()
			.map(candidate -> new IndexerListResponse.Candidate(
				candidate.node(),
				candidate.address().orElse(null),
				candidate.expiresAt().toString()
			))
			.toList();

		var claims = overview.claims()
			.stream()
			.filter(claim -> principal.allows(Permission.INDEXES_READ, claim.index()))
			.map(claim -> new IndexerListResponse.Claim(
				claim.index(),
				claim.node(),
				claim.address().orElse(null),
				claim.expiresAt().toString()
			))
			.toList();

		return new IndexerListResponse(candidates, claims);
	}
}
