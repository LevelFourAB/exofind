package se.l4.exofind.engine.api.auth;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.api.ApiEndpoints;

/**
 * That the document states what reaching an endpoint takes.
 *
 * <p>The filter runs while the document is written, which is while the project
 * is packaged rather than while the tests run, so these build a document of
 * their own out of the compiled endpoints and run the filter over that. What
 * they check is what the filter writes into it.
 */
public class RequiredPermissionFilterTest {
	@Test
	void everyEndpointSaysWhatItRequires() {
		var api = documentOfEveryEndpoint();
		new RequiredPermissionFilter().filterOpenAPI(api);

		var missing = new ArrayList<String>();

		forEachOperation(api, (key, operation) -> {
			var extensions = operation.getExtensions();

			for(var name : List.of(
				RequiredPermissionFilter.PERMISSION,
				RequiredPermissionFilter.SCOPE,
				RequiredPermissionFilter.ROLES,
				RequiredPermissionFilter.ANONYMOUS
			)) {
				if(extensions == null || extensions.get(name) == null) {
					missing.add(key + " has no " + name);
				}
			}
		});

		assertThat(missing, is(empty()));
	}

	@Test
	void theRequirementIsTheLastParagraphOfTheDescription() {
		// `website/src/openapi/spec.mjs` drops that paragraph, because the site
		// draws the same fact beside the endpoint instead
		var api = documentOfEveryEndpoint();
		new RequiredPermissionFilter().filterOpenAPI(api);

		var wrong = new ArrayList<String>();

		forEachOperation(api, (key, operation) -> {
			var paragraphs = operation.getDescription().split("\n\n");
			var last = paragraphs[paragraphs.length - 1];

			if(!last.startsWith(RequiredPermissionFilter.OPENING)) {
				missingOpening(wrong, key, last);
			}
		});

		assertThat(wrong, is(empty()));
	}

	@Test
	void everyEndpointIsRefusedTheSameWay() {
		var api = documentOfEveryEndpoint();
		new RequiredPermissionFilter().filterOpenAPI(api);

		var wrong = new ArrayList<String>();

		forEachOperation(api, (key, operation) -> {
			for(var status : List.of("401", "403")) {
				var answer = operation.getResponses().getAPIResponse(status);

				if(answer == null || answer.getDescription() == null) {
					wrong.add(key + " states no " + status + " answer");
					continue;
				}

				if(answer.getContent() == null || answer.getContent().getMediaType("application/json") == null) {
					wrong.add(key + " answers " + status + " with no body");
				}
			}
		});

		assertThat(wrong, is(empty()));
	}

	@Test
	void theAnswersAreInStatusOrder() {
		var api = OASFactory.createOpenAPI().paths(OASFactory.createPaths());
		var key = RequiredPermissionFilter.byEndpoint().keySet().iterator().next();
		add(api, key, OASFactory.createOperation()
			.description("What it does.")
			.responses(OASFactory.createAPIResponses()
				.addAPIResponse("200", OASFactory.createAPIResponse().description("It was done."))
				.addAPIResponse("404", OASFactory.createAPIResponse().description("It is not there."))
			)
		);

		new RequiredPermissionFilter().filterOpenAPI(api);

		var statuses = new ArrayList<String>();
		forEachOperation(api, (named, operation) ->
			statuses.addAll(operation.getResponses().getAPIResponses().keySet())
		);

		assertThat(statuses, contains("200", "401", "403", "404"));
	}

	@Test
	void anEndpointThatStatesItsOwnRefusalIsReported() {
		var api = OASFactory.createOpenAPI().paths(OASFactory.createPaths());
		var key = RequiredPermissionFilter.byEndpoint().keySet().iterator().next();
		add(api, key, OASFactory.createOperation()
			.description("What it does.")
			.responses(OASFactory.createAPIResponses().addAPIResponse(
				"403",
				OASFactory.createAPIResponse().description("Written by hand.")
			))
		);

		var thrown = assertThrows(
			IllegalStateException.class,
			() -> new RequiredPermissionFilter().filterOpenAPI(api)
		);

		assertThat(thrown.getMessage(), endsWith("remove the @APIResponse"));
	}

	@Test
	void anOperationWithNoEndpointIsReported() {
		var api = OASFactory.createOpenAPI().paths(OASFactory.createPaths());
		add(api, "GET /v1alpha1/nowhere", OASFactory.createOperation().description("What it does."));

		assertThrows(
			IllegalStateException.class,
			() -> new RequiredPermissionFilter().filterOpenAPI(api)
		);
	}

	@Test
	void noEndpointStatesTheAnswersToBeingRefused() throws Exception {
		// They are the same on every endpoint and are written from the
		// permission, so an endpoint that states one carries a second copy
		var stated = new ArrayList<String>();

		for(var endpoint : ApiEndpoints.endpoints()) {
			for(var answer : endpoint.getAnnotationsByType(APIResponse.class)) {
				if("401".equals(answer.responseCode()) || "403".equals(answer.responseCode())) {
					stated.add(ApiEndpoints.describe(endpoint) + " states " + answer.responseCode());
				}
			}
		}

		assertThat(stated, is(empty()));
	}

	@Test
	void theEndpointsAreActuallyBeingLookedAt() {
		assertThat(RequiredPermissionFilter.byEndpoint().entrySet(), is(not(empty())));
	}

	/** A document holding one bare operation for every compiled endpoint. */
	private static OpenAPI documentOfEveryEndpoint() {
		var api = OASFactory.createOpenAPI().paths(OASFactory.createPaths());

		for(var key : RequiredPermissionFilter.byEndpoint().keySet()) {
			add(api, key, OASFactory.createOperation().description("What it does."));
		}

		return api;
	}

	/** Put an operation at the method and path a key names. */
	private static void add(
		OpenAPI api,
		String key,
		org.eclipse.microprofile.openapi.models.Operation operation
	) {
		var parts = key.split(" ");
		var path = api.getPaths().getPathItem(parts[1]);

		if(path == null) {
			path = OASFactory.createPathItem();
			api.getPaths().addPathItem(parts[1], path);
		}

		path.setOperation(PathItem.HttpMethod.valueOf(parts[0]), operation);
	}

	/** Walk every operation of a document with the key that names it. */
	private static void forEachOperation(OpenAPI api, Named named) {
		for(var path : api.getPaths().getPathItems().entrySet()) {
			for(var operation : path.getValue().getOperations().entrySet()) {
				assertThat(operation.getValue(), is(notNullValue()));
				named.accept(operation.getKey().name() + " " + path.getKey(), operation.getValue());
			}
		}
	}

	private interface Named {
		void accept(String key, org.eclipse.microprofile.openapi.models.Operation operation);
	}

	private static void missingOpening(List<String> wrong, String key, String last) {
		wrong.add(key + " closes with `" + last + "` rather than what it requires");
	}
}
