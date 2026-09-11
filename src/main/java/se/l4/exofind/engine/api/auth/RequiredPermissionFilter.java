package se.l4.exofind.engine.api.auth;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.responses.APIResponses;

import se.l4.exofind.engine.api.ApiEndpoints;
import se.l4.exofind.engine.auth.Permission;
import se.l4.exofind.engine.auth.Role;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;

/**
 * Writes what an endpoint requires into the OpenAPI document, from the
 * {@link RequiresPermission} the endpoint is served under.
 *
 * <p>The annotation is what the request filter reads, so it is the only thing
 * that decides who reaches an endpoint. An endpoint that stated the same fact
 * in its own description would hold a second copy of it, and nothing compares
 * a description against what a request is actually checked for. Writing the
 * document from the annotation is what keeps the two from disagreeing.
 *
 * <p>Each operation is given four extensions and two sentences:
 *
 * <ul>
 *   <li>{@code x-required-permission}, the permission name as it is stored in a
 *     key
 *   <li>{@code x-permission-scope}, one of {@code index}, {@code any-index} and
 *     {@code deployment}
 *   <li>{@code x-permission-roles}, the roles that include the permission
 *   <li>{@code x-permission-anonymous}, whether a node may serve the endpoint
 *     to a request that carries no credential
 *   <li>a closing paragraph of the description, which is what a generated
 *     client carries as a doc comment
 *   <li>the {@code 401} and {@code 403} answers in full, which every endpoint
 *     gives alike and therefore declares nowhere
 * </ul>
 *
 * <p>The closing paragraph is written last and always starts with
 * {@code Requires the}. The website reads the extensions and drops that
 * paragraph, because it draws the same fact beside the endpoint instead;
 * {@code website/src/openapi/spec.mjs} is the other half of that agreement and
 * {@code RequiredPermissionFilterTest} holds both ends to the shape.
 *
 * <p>The filter runs while the document is written, which is at build time, and
 * it walks the compiled endpoints through {@link ApiEndpoints}. An operation it
 * finds no endpoint for fails the build rather than publishing an endpoint that
 * says nothing about what reaching it takes.
 */
public class RequiredPermissionFilter implements OASFilter {
	/** The permission an endpoint requires, named as it is stored in a key. */
	public static final String PERMISSION = "x-required-permission";

	/** What the permission is checked against: an index, or the deployment. */
	public static final String SCOPE = "x-permission-scope";

	/** The roles that include the permission. */
	public static final String ROLES = "x-permission-roles";

	/** Whether a request carrying no credential may reach the endpoint. */
	public static final String ANONYMOUS = "x-permission-anonymous";

	/** How the closing paragraph of every description starts. */
	public static final String OPENING = "Requires the ";

	/** The answer to a request carrying no credential this node accepts. */
	private static final String UNAUTHENTICATED = "401";

	/** The answer to a credential that holds no such permission. */
	private static final String FORBIDDEN = "403";

	/** The one media type the API answers with. */
	private static final String JSON = "application/json";

	/** The body of a refusal, which every other failure answers with too. */
	private static final String ERROR_RESPONSE = "#/components/schemas/ErrorResponse";

	@Override
	public void filterOpenAPI(OpenAPI api) {
		var required = byEndpoint();
		var paths = api.getPaths();

		if(paths == null || paths.getPathItems() == null) {
			throw new IllegalStateException("The document declares no paths, so nothing was written");
		}

		for(var path : paths.getPathItems().entrySet()) {
			var operations = path.getValue().getOperations();
			if(operations == null) continue;

			for(var operation : operations.entrySet()) {
				var key = key(operation.getKey().name(), path.getKey());
				var permission = required.get(key);

				if(permission == null) {
					throw new IllegalStateException(
						"No compiled endpoint was found for " + key + ", so what it requires"
							+ " could not be written into the document"
					);
				}

				document(operation.getValue(), permission, key);
			}
		}
	}

	/**
	 * Write what one operation requires.
	 */
	private static void document(Operation operation, RequiresPermission required, String key) {
		var permission = required.value();

		operation.addExtension(PERMISSION, permission.id());
		operation.addExtension(SCOPE, scopeOf(required));
		operation.addExtension(ROLES, rolesWith(permission));
		operation.addExtension(ANONYMOUS, permission.isAnonymousAllowed());

		var description = operation.getDescription();
		operation.setDescription(
			description == null || description.isBlank()
				? sentence(required)
				: description.stripTrailing() + "\n\n" + sentence(required)
		);

		refusals(operation, permission, key);
	}

	/**
	 * Write the two answers a caller gets when they may not make the request.
	 *
	 * <p>Every endpoint answers both the same way. What the refusal says is the
	 * permission, and what the unauthenticated answer says holds for the whole
	 * API, so an endpoint declares neither. An endpoint that declares one holds
	 * a second copy of what is written here, and the build says so rather than
	 * replacing it without a word.
	 */
	private static void refusals(Operation operation, Permission permission, String key) {
		var responses = operation.getResponses();

		if(responses == null) {
			responses = OASFactory.createAPIResponses();
			operation.setResponses(responses);
		}

		add(responses, key, UNAUTHENTICATED, """
			The request carries no credential this node accepts. Absent, malformed, \
			unknown and lapsed keys are all answered alike, so a refusal cannot be \
			used to find out which keys exist. The response carries \
			`WWW-Authenticate: Bearer`.""");

		add(
			responses,
			key,
			FORBIDDEN,
			"The API key does not have the `" + permission.id() + "` permission."
		);

		inStatusOrder(responses);
	}

	/** Add one answer, or report the endpoint that states it for itself. */
	private static void add(
		APIResponses responses,
		String key,
		String status,
		String description
	) {
		if(responses.getAPIResponse(status) != null) {
			throw new IllegalStateException(
				key + " declares its own " + status + " answer. That answer is written"
					+ " from @RequiresPermission, so remove the @APIResponse"
			);
		}

		responses.addAPIResponse(
			status,
			OASFactory.createAPIResponse()
				.description(description)
				.content(OASFactory.createContent().addMediaType(
					JSON,
					OASFactory.createMediaType().schema(OASFactory.createSchema().ref(ERROR_RESPONSE))
				))
		);
	}

	/**
	 * Put the answers back in status order.
	 *
	 * <p>The one written here is added last and belongs between the answers an
	 * endpoint declares, so that the document reads in the order a caller meets
	 * the statuses in.
	 */
	private static void inStatusOrder(APIResponses responses) {
		var listed = new ArrayList<>(responses.getAPIResponses().entrySet());
		listed.sort(Comparator.comparingInt(entry -> {
			try {
				return Integer.parseInt(entry.getKey());
			} catch(NumberFormatException e) {
				// `default`, which is the answer to everything else and reads last
				return Integer.MAX_VALUE;
			}
		}));

		for(var status : listed) {
			responses.removeAPIResponse(status.getKey());
		}

		for(var status : listed) {
			responses.addAPIResponse(status.getKey(), status.getValue());
		}
	}

	/**
	 * The closing paragraph of a description.
	 *
	 * <p>Written as the fact a caller acts on: the permission to ask for, where
	 * it is checked, and the roles that already carry it.
	 */
	private static String sentence(RequiresPermission required) {
		var permission = required.value();
		var text = new StringBuilder(OPENING)
			.append('`').append(permission.id()).append("` permission")
			.append(whereChecked(required))
			.append(". ")
			.append(roleSentence(permission));

		if(permission.isAnonymousAllowed()) {
			text.append(
				" A node that sets an anonymous key serves this endpoint to requests that"
					+ " carry no credential."
			);
		}

		return text.toString();
	}

	/** What the permission is checked against, as it reads in the sentence. */
	private static String whereChecked(RequiresPermission required) {
		if(required.value().scope() == Permission.Scope.DEPLOYMENT) {
			return ", which is not about one index";
		}

		return required.anyIndex()
			? " on at least one index"
			: " on the index the path names";
	}

	/** Which roles a caller can be given instead of the permission itself. */
	private static String roleSentence(Permission permission) {
		var roles = rolesWith(permission);
		var names = roles.stream().map(role -> "`" + role + "`").toList();

		var listed = names.size() == 1
			? names.get(0)
			: String.join(", ", names.subList(0, names.size() - 1))
				+ " and " + names.get(names.size() - 1);

		return names.size() == 1
			? "The " + listed + " role includes it."
			: "The " + listed + " roles include it.";
	}

	/** The roles that expand to a permission, in the order they are declared. */
	private static List<String> rolesWith(Permission permission) {
		var listed = new ArrayList<String>();

		for(var role : Role.values()) {
			if(role.permissions().contains(permission)) {
				listed.add(role.id());
			}
		}

		return listed;
	}

	/** The scope as the document states it. */
	private static String scopeOf(RequiresPermission required) {
		if(required.value().scope() == Permission.Scope.DEPLOYMENT) {
			return "deployment";
		}

		return required.anyIndex() ? "any-index" : "index";
	}

	/**
	 * What every compiled endpoint requires, by the method and path it is
	 * served at.
	 *
	 * <p>Several resource methods can be one operation in the document, which is
	 * how an endpoint accepting a second media type is declared. They are served
	 * under one claim, so declaring two is a mistake the build reports rather
	 * than a choice the document could carry.
	 */
	static Map<String, RequiresPermission> byEndpoint() {
		var required = new HashMap<String, RequiresPermission>();

		List<Method> endpoints;
		try {
			endpoints = ApiEndpoints.endpoints();
		} catch(Exception e) {
			throw new IllegalStateException(
				"The compiled endpoints could not be walked, so what they require could"
					+ " not be written into the document",
				e
			);
		}

		for(var endpoint : endpoints) {
			var permission = endpoint.getAnnotation(RequiresPermission.class);
			if(permission == null) {
				// Refused by the request filter, and reported by AuthCoverageTest
				continue;
			}

			var key = key(methodOf(endpoint), pathOf(endpoint));
			var seen = required.putIfAbsent(key, permission);

			if(
				seen != null
					&& (seen.value() != permission.value() || seen.anyIndex() != permission.anyIndex())
			) {
				throw new IllegalStateException(
					key + " is served by resource methods requiring different permissions: "
						+ seen.value().id() + " and " + permission.value().id()
				);
			}
		}

		return required;
	}

	/** The HTTP method an endpoint is served under. */
	private static String methodOf(Method endpoint) {
		if(endpoint.getAnnotation(GET.class) != null) return "GET";
		if(endpoint.getAnnotation(POST.class) != null) return "POST";
		if(endpoint.getAnnotation(PUT.class) != null) return "PUT";
		if(endpoint.getAnnotation(DELETE.class) != null) return "DELETE";
		if(endpoint.getAnnotation(PATCH.class) != null) return "PATCH";
		if(endpoint.getAnnotation(HEAD.class) != null) return "HEAD";
		if(endpoint.getAnnotation(OPTIONS.class) != null) return "OPTIONS";

		throw new IllegalStateException(
			ApiEndpoints.describe(endpoint) + " carries no HTTP method"
		);
	}

	/** The path an endpoint is served at, as the document spells it. */
	private static String pathOf(Method endpoint) {
		var type = endpoint.getDeclaringClass().getAnnotation(jakarta.ws.rs.Path.class);
		var method = endpoint.getAnnotation(jakarta.ws.rs.Path.class);

		var path = (type == null ? "" : type.value())
			+ "/" + (method == null ? "" : method.value());

		var cleaned = path.replaceAll("/+", "/");

		if(cleaned.length() > 1 && cleaned.endsWith("/")) {
			cleaned = cleaned.substring(0, cleaned.length() - 1);
		}

		return cleaned.startsWith("/") ? cleaned : "/" + cleaned;
	}

	/** How an endpoint and an operation of the document are matched up. */
	private static String key(String method, String path) {
		return method + " " + path;
	}
}
