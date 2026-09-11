package se.l4.exofind.engine.api.errors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.api.ApiEndpoints;

/**
 * That the document states which error codes an endpoint answers with.
 *
 * <p>The filter runs while the document is written, which is while the project
 * is packaged rather than while the tests run, so the tests that check what it
 * writes build a document of their own and run the filter over that.
 *
 * <p>The rest read the annotations themselves. A code is written by a caller
 * into a branch, so a code that no part of the engine returns is worse than a
 * code left undocumented: the branch is never taken and nothing says so.
 */
public class ErrorCodeFilterTest {
	/** How the engine declares a code, which is the only place one is defined. */
	private static final Pattern DECLARED = Pattern.compile("withCode\\(\"([^\"]+)\"\\)");

	@Test
	void everyCodeAnEndpointNamesIsOneTheEngineReturns() throws Exception {
		var declared = declaredCodes();
		var unknown = new ArrayList<String>();

		for(var endpoint : ApiEndpoints.endpoints()) {
			for(var code : endpoint.getAnnotationsByType(ReturnsError.class)) {
				if(!declared.contains(code.value())) {
					unknown.add(ApiEndpoints.describe(endpoint) + " names " + code.value());
				}
			}
		}

		assertThat(unknown, is(empty()));
	}

	@Test
	void anEndpointNamesEachCodeOnce() throws Exception {
		var twice = new ArrayList<String>();

		for(var endpoint : ApiEndpoints.endpoints()) {
			var seen = new HashSet<String>();

			for(var code : endpoint.getAnnotationsByType(ReturnsError.class)) {
				if(!seen.add(code.value())) {
					twice.add(ApiEndpoints.describe(endpoint) + " names " + code.value() + " twice");
				}
			}
		}

		assertThat(twice, is(empty()));
	}

	@Test
	void everyCodeSaysWhatProducesIt() throws Exception {
		// The sentence is what the page shows beside the code, and what a
		// caller reads to decide what to do about it
		var wrong = new ArrayList<String>();

		for(var endpoint : ApiEndpoints.endpoints()) {
			for(var code : endpoint.getAnnotationsByType(ReturnsError.class)) {
				if(code.when().isBlank() || !code.when().endsWith(".")) {
					wrong.add(ApiEndpoints.describe(endpoint) + ": " + code.value());
				}
			}
		}

		assertThat(wrong, is(empty()));
	}

	@Test
	void theCodesAreWrittenOntoTheAnswerThatCarriesThem() {
		var endpoint = anEndpointWithCodes();
		var code = endpoint.getValue().get(0);

		var api = documentAnswering(endpoint);
		new ErrorCodeFilter().filterOpenAPI(api);

		var answer = api.getPaths()
			.getPathItem(pathOf(endpoint.getKey()))
			.getOperations().values().iterator().next()
			.getResponses().getAPIResponse(String.valueOf(code.status()));

		@SuppressWarnings("unchecked")
		var written = (List<Map<String, String>>) answer.getExtensions()
			.get(ErrorCodeFilter.CODES);

		assertThat(written, is(not(empty())));
		assertThat(written.get(0), hasEntry(ErrorCodeFilter.CODE, code.value()));
		assertThat(written.get(0), hasEntry(ErrorCodeFilter.WHEN, code.when()));
	}

	@Test
	void theCodesAreTheLastParagraphOfTheAnswer() {
		// `website/src/openapi/spec.mjs` drops that paragraph, because the site
		// draws the same codes as rows under the answer instead
		var endpoint = anEndpointWithCodes();
		var code = endpoint.getValue().get(0);

		var api = documentAnswering(endpoint);
		new ErrorCodeFilter().filterOpenAPI(api);

		var description = api.getPaths()
			.getPathItem(pathOf(endpoint.getKey()))
			.getOperations().values().iterator().next()
			.getResponses().getAPIResponse(String.valueOf(code.status()))
			.getDescription();

		var paragraphs = description.split("\n\n");

		assertThat(
			List.of(paragraphs[0], paragraphs[paragraphs.length - 1].split("`")[0]),
			contains("It did not work.", ErrorCodeFilter.OPENING)
		);
	}

	@Test
	void aCodeForAnAnswerTheEndpointDoesNotStateIsReported() {
		var endpoint = anEndpointWithCodes();
		var api = documentWith(endpoint.getKey(), List.of("418"));

		assertThrows(
			IllegalStateException.class,
			() -> new ErrorCodeFilter().filterOpenAPI(api)
		);
	}

	@Test
	void theEndpointsAreActuallyBeingLookedAt() {
		assertThat(ErrorCodeFilter.byEndpoint().entrySet(), is(not(empty())));
	}

	/** One endpoint that declares codes, whichever the walk answers with first. */
	private static Map.Entry<String, List<ReturnsError>> anEndpointWithCodes() {
		return ErrorCodeFilter.byEndpoint().entrySet().stream()
			.filter(endpoint -> !endpoint.getValue().isEmpty())
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("No endpoint declares an error code"));
	}

	/** A document holding one operation, answering with every status its codes name. */
	private static OpenAPI documentAnswering(Map.Entry<String, List<ReturnsError>> endpoint) {
		var statuses = endpoint.getValue().stream()
			.map(code -> String.valueOf(code.status()))
			.distinct()
			.toList();

		return documentWith(endpoint.getKey(), statuses);
	}

	/** A document holding one operation, answering with the given statuses. */
	private static OpenAPI documentWith(String key, List<String> statuses) {
		var api = OASFactory.createOpenAPI().paths(OASFactory.createPaths());

		var responses = OASFactory.createAPIResponses();
		for(var status : statuses) {
			responses.addAPIResponse(
				status,
				OASFactory.createAPIResponse().description("It did not work.")
			);
		}

		var operation = OASFactory.createOperation()
			.description("What it does.")
			.responses(responses);

		var path = OASFactory.createPathItem();
		path.setOperation(methodOf(key), operation);
		api.getPaths().addPathItem(pathOf(key), path);

		return api;
	}

	/** Every code the engine declares, read from the sources that declare them. */
	private static Set<String> declaredCodes() throws Exception {
		var codes = new HashSet<String>();

		try(Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
			for(var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
				var matcher = DECLARED.matcher(Files.readString(file));

				while(matcher.find()) {
					codes.add(matcher.group(1));
				}
			}
		}

		if(codes.isEmpty()) {
			throw new IllegalStateException(
				"No error codes were found in the sources, so nothing was checked"
			);
		}

		return codes;
	}

	/** The path part of a key, which is what the document is keyed by. */
	private static String pathOf(String key) {
		return key.split(" ")[1];
	}

	/** The method part of a key, as the document names it. */
	private static PathItem.HttpMethod methodOf(String key) {
		return PathItem.HttpMethod.valueOf(key.split(" ")[0]);
	}
}
