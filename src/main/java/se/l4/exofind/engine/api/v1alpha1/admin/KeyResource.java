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
 * Manages deployment API keys.
 *
 * <p>Keys are stored in shared deployment storage. A key created on one node
 * works on all nodes, and revoking a key takes effect across all nodes without
 * redeployment. Revocation propagates within the duration configured by
 * {@code EXOFIND_AUTH_REFRESH_INTERVAL}.
 *
 * <p>Key management does not depend on a specific node. Requests are handled
 * directly by the node that receives them and are not forwarded to the indexer.
 *
 * <p>Keys are immutable. To change permissions, create a replacement key,
 * migrate clients to the new key, and revoke the old key.
 */
@Tag(
	name = "API keys",
	description = "Creates, lists, and revokes the API keys of the deployment.",
	externalDocs = @ExternalDocumentation(
		description = "Authentication reference",
		url = "https://exofind.dev/reference/auth/"
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
	 * Lists deployment keys and the key configuration of the answering node.
	 *
	 * <p>Key credentials are stored only as hashes and cannot be recovered from
	 * listings.
	 *
	 * @return
	 */
	@GET
	@RequiresPermission(Permission.KEYS_READ)
	@Operation(
		operationId = "listKeys",
		summary = "List API keys",
		description = """
			Lists deployment keys shared across all nodes, along with the \
			local key configuration of the answering node.

			Key credentials are stored only as hashes and cannot be recovered \
			from listings. A lost credential must be replaced.

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
	 * Creates an API key and returns the generated credential and metadata.
	 *
	 * <p>The secret credential is returned only in this response because
	 * credentials are stored only as hashes.
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
			Creates an API key and returns the generated credential string and \
			key metadata. The full secret credential is returned only in this \
			response because credentials are stored only as hashes. A lost \
			credential cannot be recovered and must be replaced. Server logs \
			record the key `id`, never the credential value.

			When a key is created, roles are expanded into their constituent \
			permissions. Only the resulting permissions are stored in the key. \
			Existing keys do not change permissions if role definitions change \
			in later software versions.

			A key created on one node works on all nodes immediately because \
			nodes look up unseen keys without delay. Served by whichever node \
			receives the request. Requires the `keys.write` permission."""
	)
	@APIResponse(
		responseCode = "201",
		description = """
			The key was created. The `credential` in this response is the only \
			copy returned.""",
		content = @Content(schema = @Schema(implementation = CreatedKey.class))
	)
	@APIResponse(
		responseCode = "400",
		description = """
			The request body is missing, specifies an unknown role, \
			permission, or index pattern, or contains an invalid `expiresAt` \
			timestamp (`auth:key:*`). All validation errors are reported.""",
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
			Key storage is unavailable on this node (`auth:keys:unavailable`), \
			could not be reached (`auth:keys:io_error`), or concurrent updates \
			from other nodes conflicted with this request \
			(`auth:keys:conflict`). The stored keys are unchanged.""",
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
	 * Revokes an API key.
	 *
	 * <p>Revocation takes effect on this node immediately and on all other
	 * nodes within their configured refresh interval.
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
			Revokes an API key. Revocation takes effect on the answering node \
			immediately and across all other nodes within \
			`EXOFIND_AUTH_REFRESH_INTERVAL`, as nodes accept cached keys until \
			their next storage read.

			Keys are immutable. To change permissions, create a replacement \
			key, migrate clients to the new key, and revoke the old key. The \
			root key is not stored in key storage and cannot be revoked \
			through the API.

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
			concurrent updates from other nodes conflicted with this request. \
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
