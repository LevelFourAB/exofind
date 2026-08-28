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
import se.l4.exofind.engine.api.auth.RequiresPermission;
import se.l4.exofind.engine.api.errors.ErrorResponse;
import se.l4.exofind.engine.api.routing.ServedBy;
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
 * replaced conditionally on the version it was read at, and a key is about the
 * deployment rather than about any index - so these requests are served by
 * whichever node receives them and are never passed to the indexer.
 *
 * <p>A key is created and revoked, never edited. Changing what something is
 * allowed to do means creating the key it should have, moving whatever uses it
 * over, and revoking the old one, which leaves a moment where both work rather
 * than a moment where neither does.
 */
@Tag(
	name = "API keys",
	description = "Creates, lists and revokes the API keys of the deployment.",
	externalDocs = @ExternalDocumentation(
		description = "Authentication reference",
		url = "https://levelfourab.github.io/exofind/reference/auth/"
	)
)
@SecurityRequirement(name = ExofindApi.API_KEY)
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
	@Operation(
		operationId = "listKeys",
		summary = "List API keys",
		description = """
			Lists the deployment keys, which are shared across all nodes, \
			along with how the answering node is configured to use them.

			Credentials are stored only as hashes, so they are not part of a \
			listing and cannot be recovered from one. A lost credential must \
			be replaced.

			Served by whichever node receives the request. Requires the \
			`keys.read` permission."""
	)
	@APIResponse(
		responseCode = "200",
		description = "The deployment keys and this node's key configuration.",
		content = @Content(schema = @Schema(implementation = KeyListResponse.class))
	)
	@APIResponse(
		responseCode = "401",
		description = "The request carries no credential this node accepts.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The API key does not have the `keys.read` permission.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = """
			Key storage is unavailable on this node \
			(`auth:keys:unavailable`) or could not be reached \
			(`auth:keys:io_error`).""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
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
	@ServedBy(ServedBy.Node.ANY_NODE)
	@Operation(
		operationId = "createKey",
		summary = "Create an API key",
		description = """
			Creates a key and answers with the generated credential and the \
			key's metadata. The full credential is returned only in this \
			response and nowhere else, as only a hash of it is stored - a lost \
			credential cannot be recovered and must be replaced. Server logs \
			record the key `id`, never the credential.

			Roles are expanded into their permissions when the key is created, \
			and only the resulting permissions are stored, so a key does not \
			change if a role's definition changes in a later version.

			A key created here works on every node at once, since a node looks \
			up an unseen key immediately. Served by whichever node receives \
			the request. Requires the `keys.write` permission."""
	)
	@APIResponse(
		responseCode = "201",
		description = """
			The key was created. The `credential` in this response is the only \
			copy there will be.""",
		content = @Content(schema = @Schema(implementation = CreatedKey.class))
	)
	@APIResponse(
		responseCode = "400",
		description = """
			The body is missing, or names an unknown role, permission or index \
			pattern, or carries an invalid `expiresAt` (`auth:key:*`). All \
			validation errors are reported.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "401",
		description = "The request carries no credential this node accepts.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The API key does not have the `keys.write` permission.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = """
			Key storage is unavailable on this node \
			(`auth:keys:unavailable`), could not be reached \
			(`auth:keys:io_error`), or another node changed the keys while \
			this change was being stored (`auth:keys:conflict`). The stored \
			keys are unchanged.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
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
	@ServedBy(ServedBy.Node.ANY_NODE)
	@Operation(
		operationId = "revokeKey",
		summary = "Revoke an API key",
		description = """
			Revokes a key. It stops working on the answering node at once and \
			on every other node within that node's \
			`EXOFIND_AUTH_REFRESH_INTERVAL`, since a node accepts a cached key \
			until its next storage read.

			Keys are immutable: to change what something is allowed to do, \
			create the key it should have, move whatever uses it over, and \
			revoke the old one - which leaves a moment where both work rather \
			than one where neither does. The root key is not stored in key \
			storage and cannot be revoked through the API.

			Served by whichever node receives the request. Requires the \
			`keys.write` permission."""
	)
	@APIResponse(responseCode = "204", description = "The key was revoked.")
	@APIResponse(
		responseCode = "401",
		description = "The request carries no credential this node accepts.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The API key does not have the `keys.write` permission.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "404",
		description = "No key has this ID (`auth:key:not_found`).",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = """
			Key storage is unavailable on this node, could not be reached, or \
			another node changed the keys while this change was being stored. \
			The stored keys are unchanged.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	public Response delete(
		@Parameter(
			description = "ID of the key to revoke.",
			example = "4ff6b760264c1918"
		)
		@PathParam("id") String id
	) {
		keys.delete(id);
		return Response.noContent().build();
	}
}
