package se.l4.exofind.engine.api.v1alpha1.admin;

import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
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
 * Provides indexer candidate nodes and active writer claims across the
 * deployment.
 *
 * <p><p>Any node can serve these requests from its local view of shared
 * deployment state, including search-only nodes. Responses can lag actual state
 * by a few seconds.
 *
 * <p><p>On nodes using local storage, both lists are empty; {@code readOnly} on
 * each index indicates whether the node can modify it.
 */
@Tag(
	name = "Indexers",
	description = "Which node writes which index.",
	externalDocs = @ExternalDocumentation(
		description = "Indexers reference",
		url = "https://exofind.dev/reference/admin-api/#indexers"
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
	 * Lists candidate nodes competing to write indexes and active writer claims
	 * for each index.
	 *
	 * <p><p>Claims on indexes the caller lacks permissions on are omitted,
	 * matching index listings. Candidates name no index and are listed in full.
	 *
	 * @return
	 */
	@GET
	@RequiresPermission(value = Permission.INDEXES_READ, anyIndex = true)
	@Operation(
		operationId = "listIndexers",
		summary = "List indexer candidates and claims",
		description = """
			Lists candidate nodes competing to write indexes and the active \
			writer claim for each index.

			Any node can serve this request from its local view of shared \
			deployment state, including search-only nodes. The response can \
			lag actual state by a few seconds. Indexes without an active claim \
			are omitted until a write assigns a writer.

			If a credential lacks permissions for an index, that index is \
			omitted from the claims list. On nodes using local storage, both \
			lists are empty.

			Requires the `indexes.read` permission."""
	)
	@APIResponse(
		responseCode = "200",
		description = "The candidate nodes and the claims the key can see.",
		content = @Content(
			schema = @Schema(implementation = IndexerListResponse.class),
			examples = @ExampleObject(name = "indexers", value = IndexerListResponse.EXAMPLE)
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
		responseCode = "503",
		description = """
			Indexer leadership assignments could not be read from shared \
			storage (`indexer:leadership_unreadable`). Retrying the request is \
			expected to work once storage responds.""",
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
