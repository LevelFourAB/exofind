package se.l4.exofind.engine.api.v1alpha1.admin;

import se.l4.exofind.engine.api.auth.RequiresPermission;
import se.l4.exofind.engine.api.v1alpha1.admin.model.CreatedKey;
import se.l4.exofind.engine.api.v1alpha1.admin.model.KeyDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.KeyListResponse;
import se.l4.exofind.engine.auth.Keys;
import se.l4.exofind.engine.auth.Permission;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.errors.ValidationException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The API keys of the deployment.
 *
 * <p>Keys live in the object storage rather than in the configuration of a
 * node, so a key created here works on every node and revoking one takes effect
 * everywhere without redeploying anything. How long that takes is
 * {@code EXOFIND_AUTH_REFRESH_INTERVAL} on each node.
 *
 * <p>Managing keys does not need the indexer role - the store is one object
 * replaced conditionally on the version it was read at - so these requests are
 * served by whichever node receives them and never redirect.
 *
 * <p>A key is created and revoked, never edited. Changing what something is
 * allowed to do means creating the key it should have, moving whatever uses it
 * over, and revoking the old one, which leaves a moment where both work rather
 * than a moment where neither does.
 */
@Path("/v1alpha1/admin/keys")
@Produces(MediaType.APPLICATION_JSON)
public class KeyResource {
	private static final ErrorType MISSING_BODY = ErrorType.withCode("request:missing_body")
		.withMessage("A key definition is required");

	private final Keys keys;

	public KeyResource(Keys keys) {
		this.keys = keys;
	}

	/**
	 * List the keys, and how the node answering is configured to use them.
	 *
	 * <p>Credentials are not part of a listing and cannot be recovered from
	 * one.
	 *
	 * @return
	 */
	@GET
	@RequiresPermission(Permission.KEYS_READ)
	public KeyListResponse list() {
		return new KeyListResponse(
			keys.list().collect(KeyDefinitionMapper::toApi).toList(),
			keys.hasRootKey(),
			keys.anonymousKeyId().orElse(null)
		);
	}

	/**
	 * Create a key.
	 *
	 * <p>The credential is in the response and nowhere else, as only a hash of
	 * it is stored.
	 *
	 * @param definition
	 * @return
	 */
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	@RequiresPermission(Permission.KEYS_WRITE)
	public Response create(KeyDefinition definition) {
		if(definition == null) {
			throw new ValidationException(MISSING_BODY.toMessage(ObjectLocation.root()));
		}

		var parsed = KeyDefinitionMapper.toEngine(definition);
		var created = keys.create(parsed.description(), parsed.grants(), parsed.expiresAt());

		return Response.status(Response.Status.CREATED)
			.entity(
				new CreatedKey(
					created.credential(),
					KeyDefinitionMapper.toApi(created.key())
				)
			)
			.build();
	}

	/**
	 * Revoke a key.
	 *
	 * <p>It stops working on this node at once and on every other node within
	 * that node's refresh interval.
	 *
	 * @param id
	 * @return
	 */
	@DELETE
	@Path("/{id}")
	@RequiresPermission(Permission.KEYS_WRITE)
	public Response delete(@PathParam("id") String id) {
		keys.delete(id);
		return Response.noContent().build();
	}
}
