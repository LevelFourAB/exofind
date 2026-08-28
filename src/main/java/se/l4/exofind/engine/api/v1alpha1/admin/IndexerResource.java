package se.l4.exofind.engine.api.v1alpha1.admin;

import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import se.l4.exofind.engine.api.ExofindApi;
import se.l4.exofind.engine.api.auth.AuthContext;
import se.l4.exofind.engine.api.auth.RequiresPermission;
import se.l4.exofind.engine.api.errors.ErrorResponse;
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
@Tag(
	name = "Indexers",
	description = "Which node writes which index.",
	externalDocs = @ExternalDocumentation(
		description = "Indexers reference",
		url = "https://levelfourab.github.io/exofind/reference/admin-api/#indexers"
	)
)
@SecurityRequirement(name = ExofindApi.API_KEY)
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
	@Operation(
		operationId = "listIndexers",
		summary = "List indexer candidates and claims",
		description = """
			Lists the candidate nodes competing to write indexes, and the \
			active writer claim for each index some node writes.

			Any node answers, including a search-only one, from its own read \
			of the state the deployment shares - so the answer can lag reality \
			by a few seconds, and a claim that just moved may still name the \
			old node for a moment. An index with no active claim is left out \
			until a write assigns it a writer.

			A claim on an index no grant of the calling key covers is left out, \
			the way the index listing leaves such indexes out; candidates name \
			no index and are listed whole. On a node using local storage both \
			lists are empty - it is the only node there is and writes \
			everything.

			Requires the `indexes.read` permission."""
	)
	@APIResponse(
		responseCode = "200",
		description = "The candidate nodes and the claims the key can see.",
		content = @Content(schema = @Schema(implementation = IndexerListResponse.class))
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
		responseCode = "503",
		description = """
			Leadership assignments could not be read from shared storage \
			(`indexer:leadership_unreadable`). Send the request again once \
			storage responds.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
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
