package se.l4.exofind.engine.api.v1alpha1.admin;

import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
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
import se.l4.exofind.engine.api.errors.ReturnsError;
import se.l4.exofind.engine.api.routing.ServedBy;
import se.l4.exofind.engine.api.v1alpha1.admin.model.CreatedKey;
import se.l4.exofind.engine.api.v1alpha1.admin.model.KeyDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.KeyInfo;
import se.l4.exofind.engine.api.v1alpha1.admin.model.KeyListResponse;
import se.l4.exofind.engine.auth.ForbiddenException;
import se.l4.exofind.engine.auth.Grant;
import se.l4.exofind.engine.auth.KeyNotFoundException;
import se.l4.exofind.engine.auth.Keys;
import se.l4.exofind.engine.auth.Permission;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.errors.ValidationException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.QueryParam;
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
 * <p>What a key allows cannot be changed. To change permissions, create a
 * replacement key, migrate clients to the new key, and revoke the old key. The
 * credential itself is replaceable on its own through {@code actions/rotate},
 * which keeps the ID and the grants.
 *
 * <p>A caller creates, rotates and revokes only keys that hold no more than it
 * does. Without that, {@code keys.write} alone could create an {@code admin}
 * key over every index, or take over the credential of one.
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
	private static final ErrorType MISSING_BODY = ErrorType.withCode("request:body_required")
		.withStatus(400)
		.withMessage("A key definition is required");

	private final Keys keys;
	private final AuthContext auth;

	public KeyResource(Keys keys, AuthContext auth) {
		this.keys = keys;
		this.auth = auth;
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

			`prefix` keeps the keys whose ID starts with it. `limit` caps the \
			answer, and a listing cut short names the last ID in `next`; pass \
			it as `after` to read on. See [Listings](https://exofind.dev/reference/admin-api/#listings).

			Served by whichever node receives the request."""
	)
	@APIResponse(
		responseCode = "200",
		description = "The deployment keys and this node's key configuration.",
		content = @Content(
			schema = @Schema(implementation = KeyListResponse.class),
			examples = @ExampleObject(name = "keys", value = KeyListResponse.EXAMPLE)
		)
	)
	@APIResponse(
		responseCode = "400",
		description = "The `limit` parameter is out of range.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@ReturnsError(
		value = "request:limit_out_of_range",
		status = 400,
		when = "The `limit` parameter is not a whole number from 1 to 1000."
	)
	@APIResponse(
		responseCode = "409",
		description = "Key storage is unavailable on this node, or could not be reached.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@ReturnsError(
		value = "storage:unavailable",
		status = 409,
		when = "The node is not configured with key storage."
	)
	@ReturnsError(
		value = "storage:io_error",
		status = 409,
		when = "Key storage answered with an error. Send the request again."
	)
	public KeyListResponse list(
		@Parameter(
			description = "Keeps only the keys whose ID starts with this text."
		)
		@QueryParam("prefix") String prefix,
		@Parameter(
			description = """
				The ID to continue after, as the `next` field of the \
				previous response gave it. The named key is not \
				included.""",
			example = "4ff6b760264c1918"
		)
		@QueryParam("after") String after,
		@Parameter(
			description = """
				Most keys to answer. Without it the whole listing is \
				answered. When more remain, the response carries the last \
				ID in `next`.""",
			schema = @Schema(
				type = SchemaType.INTEGER,
				minimum = "1",
				maximum = "1000"
			)
		)
		@QueryParam("limit") String limit
	) {
		var page = Listing.of(
			keys.list().collect(KeyDefinitionMapper::toApi),
			KeyInfo::id,
			prefix,
			after,
			limit
		);

		return new KeyListResponse(
			page.entries(),
			keys.hasRootKey(),
			keys.anonymousKeyId().orElse(null),
			page.next()
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

			The caller can only create a key that holds no more than the \
			caller holds. Every permission of the new key must be held by the \
			caller on every index the patterns of the new key match. A key \
			that holds more is refused with `auth:forbidden`, which names the \
			first permission the caller does not hold. The root key can \
			create any key.

			A key created on one node works on all nodes immediately because \
			nodes look up unseen keys without delay. Served by whichever node \
			receives the request."""
	)
	@APIResponse(
		responseCode = "201",
		description = """
			The key was created. The `credential` in this response is the only \
			copy returned.""",
		content = @Content(
			schema = @Schema(implementation = CreatedKey.class),
			examples = @ExampleObject(name = "created", value = CreatedKey.EXAMPLE)
		)
	)
	@APIResponse(
		responseCode = "400",
		description = """
			The request body is missing, or the key definition failed \
			validation. All validation errors are reported.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = """
			The key could not be stored. The stored keys are unchanged.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@ReturnsError(
		value = "request:body_required",
		status = 400,
		when = "The request carries no body."
	)
	@ReturnsError(
		value = "auth:key:role_unknown",
		status = 400,
		when = "The definition names a role this version does not have."
	)
	@ReturnsError(
		value = "auth:key:permission_unknown",
		status = 400,
		when = "The definition names a permission this version does not have."
	)
	@ReturnsError(
		value = "auth:key:grants_required",
		status = 400,
		when = "The definition holds no grant, so the key could do nothing."
	)
	@ReturnsError(
		value = "auth:key:permissions_required",
		status = 400,
		when = "A grant names neither a role nor a list of permissions."
	)
	@ReturnsError(
		value = "auth:key:indexes_required",
		status = 400,
		when = "A grant holds a permission that is about one index and does not say which indexes it covers."
	)
	@ReturnsError(
		value = "auth:key:index_pattern_invalid",
		status = 400,
		when = "An entry of `indexes` is neither an index name nor a prefix followed by `*`, or no index or generation name can match it."
	)
	@ReturnsError(
		value = "auth:key:indexes_unsupported",
		status = 400,
		when = "A grant names `indexes` and holds no permission that is about one index, so the patterns would narrow nothing."
	)
	@ReturnsError(
		value = "auth:key:expiry_invalid",
		status = 400,
		when = "`expiresAt` is not an ISO 8601 timestamp, or is later than the latest expiry a key can have."
	)
	@ReturnsError(
		value = "auth:key:expiry_in_past",
		status = 400,
		when = "`expiresAt` has already passed, so the key would be lapsed from the moment it was created."
	)
	@ReturnsError(
		value = "storage:unavailable",
		status = 409,
		when = "The node is not configured with key storage."
	)
	@ReturnsError(
		value = "storage:io_error",
		status = 409,
		when = "Key storage answered with an error. Send the request again."
	)
	@ReturnsError(
		value = "storage:conflict",
		status = 409,
		when = "Other nodes kept changing the stored keys. The stored keys are unchanged; send the request again."
	)
	public Response create(
		@RequestBody(content = @Content(
			schema = @Schema(implementation = KeyDefinition.class),
			examples = @ExampleObject(
				name = "key",
				summary = "A reader of one index, expiring at a date",
				value = KeyDefinition.EXAMPLE
			)
		))
		KeyDefinition definition
	) {
		if(definition == null) {
			throw new ValidationException(MISSING_BODY.toMessage(ObjectLocation.root()));
		}

		var parsed = KeyDefinitionMapper.toEngine(definition);
		requireHeld(parsed.grants());

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
	 * <p>Two keys are refused with {@code 409}, because the answering node would
	 * not start again without them: the last key granted {@code keys.write} when
	 * the node has no root key, and the key named by
	 * {@code EXOFIND_AUTH_ANONYMOUS_KEY}.
	 *
	 * <p>A key that holds more than the caller is refused with {@code 403}, so
	 * a narrow key cannot revoke the keys that manage it.
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

			What a key allows cannot be changed. To change permissions, create \
			a replacement key, migrate clients to the new key, and revoke the \
			old key. To replace only the credential, keeping everything the \
			key allows, use `actions/rotate`. The root key is not stored in \
			key storage and cannot be revoked through the API.

			The answering node refuses to revoke two keys, because it would \
			not start again without them: the last key granted `keys.write` \
			when the node has no root key, and the key named by \
			`EXOFIND_AUTH_ANONYMOUS_KEY`. Create a replacement, or point the \
			configuration elsewhere, and send the request again.

			The caller can only revoke a key that holds no more than the \
			caller holds, the same rule as for creating a key. A key that \
			holds more is refused with `auth:forbidden`.

			Served by whichever node receives the request."""
	)
	@APIResponse(responseCode = "204", description = "The key was revoked.")
	@APIResponse(
		responseCode = "404",
		description = "No key has this ID.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = """
			The key could not be revoked. The stored keys are unchanged.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@ReturnsError(
		value = "auth:key:not_found",
		status = 404,
		when = "No key is stored under this ID."
	)
	@ReturnsError(
		value = "auth:key:last_administrator",
		status = 409,
		when = "The key is the last one granted `keys.write` and the node has no root key, so revoking it would leave nobody able to create another."
	)
	@ReturnsError(
		value = "auth:key:in_use_as_anonymous",
		status = 409,
		when = "`EXOFIND_AUTH_ANONYMOUS_KEY` names this key on the answering node, so revoking it would stop that node from starting."
	)
	@ReturnsError(
		value = "storage:unavailable",
		status = 409,
		when = "The node is not configured with key storage."
	)
	@ReturnsError(
		value = "storage:io_error",
		status = 409,
		when = "Key storage answered with an error. Send the request again."
	)
	@ReturnsError(
		value = "storage:conflict",
		status = 409,
		when = "Other nodes kept changing the stored keys. The stored keys are unchanged; send the request again."
	)
	public Response delete(
		@Parameter(
			description = "ID of the key to revoke.",
			example = "4ff6b760264c1918"
		)
		@PathParam("id") String id
	) {
		requireHeld(id);
		keys.delete(id);
		return Response.noContent().build();
	}

	/**
	 * Replaces the credential of a key, keeping what the key allows.
	 *
	 * <p>The ID, description, grants, creation time and expiry are all kept, so
	 * nothing that names the key has to be updated. Only the secret changes,
	 * which is what stops the previous credential from working.
	 *
	 * <p>A key that holds more than the caller is refused with {@code 403}.
	 * Otherwise a caller would get a working credential for a wider key.
	 *
	 * @param id
	 * @return
	 */
	@POST
	@Path("/{id}/actions/rotate")
	@RequiresPermission(Permission.KEYS_WRITE)
	@ServedBy(ServedBy.Node.ANY_NODE)
	@Operation(
		operationId = "rotateKey",
		summary = "Rotate the credential of an API key",
		description = """
			Replaces the credential of a key and returns the new one. The key \
			ID, description, grants, `createdAt` and `expiresAt` are all kept, \
			so a rotation is how a credential is replaced without rebuilding \
			the grants and without anything that records the key ID going \
			stale.

			The previous credential stops working on the answering node \
			immediately and across all other nodes within \
			`EXOFIND_AUTH_REFRESH_INTERVAL`, as nodes accept cached keys until \
			their next storage read. That interval is the window in which \
			clients can move to the new credential.

			As when a key is created, the full secret credential is returned \
			only in this response because credentials are stored only as \
			hashes. A lost credential cannot be recovered; rotate the key \
			again. Server logs record the key `id`, never the credential \
			value.

			The caller can only rotate a key that holds no more than the \
			caller holds, the same rule as for creating a key. Otherwise the \
			caller would get a working credential for a wider key. A key that \
			holds more is refused with `auth:forbidden`.

			Served by whichever node receives the request."""
	)
	@APIResponse(
		responseCode = "200",
		description = """
			The credential was replaced. The `credential` in this response is \
			the only copy returned.""",
		content = @Content(
			schema = @Schema(implementation = CreatedKey.class),
			examples = @ExampleObject(name = "rotated", value = CreatedKey.EXAMPLE)
		)
	)
	@APIResponse(
		responseCode = "404",
		description = "No key has this ID.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = """
			The credential could not be replaced. The stored keys are \
			unchanged and the previous credential still works.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@ReturnsError(
		value = "auth:key:not_found",
		status = 404,
		when = "No key is stored under this ID."
	)
	@ReturnsError(
		value = "storage:unavailable",
		status = 409,
		when = "The node is not configured with key storage."
	)
	@ReturnsError(
		value = "storage:io_error",
		status = 409,
		when = "Key storage answered with an error. Send the request again."
	)
	@ReturnsError(
		value = "storage:conflict",
		status = 409,
		when = "Other nodes kept changing the stored keys. The stored keys are unchanged; send the request again."
	)
	public CreatedKey rotate(
		@Parameter(
			description = "ID of the key whose credential is replaced.",
			example = "4ff6b760264c1918"
		)
		@PathParam("id") String id
	) {
		requireHeld(id);
		var rotated = keys.rotate(id);

		return new CreatedKey(
			rotated.credential(),
			KeyDefinitionMapper.toApi(rotated.key())
		);
	}

	/**
	 * Refuse the request if a stored key holds more than the caller.
	 *
	 * <p>The grants of a key never change and an ID is never used again, so
	 * the grants read here are the grants of the key the change acts on.
	 *
	 * @throws KeyNotFoundException
	 *   if no key is stored under the ID
	 */
	private void requireHeld(String id) {
		var key = keys.list().detect(candidate -> candidate.id().equals(id));
		if(key == null) {
			throw new KeyNotFoundException(id);
		}

		requireHeld(key.grants());
	}

	/**
	 * Refuse the request if the grants hold more than the caller.
	 */
	private void requireHeld(ListIterable<Grant> grants) {
		auth.principal().firstNotHeld(grants).ifPresent(permission -> {
			throw new ForbiddenException(permission);
		});
	}
}
