package se.l4.exofind.engine.api.errors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.api.ApiEndpoints;

/**
 * That an endpoint names every error code it can answer with.
 *
 * <p>{@code ErrorCodeFilterTest} checks the other direction, that a code an
 * endpoint names is one the engine returns. Together they keep the OpenAPI
 * document, and the pages the site draws from it, listing every code a client
 * can branch on.
 *
 * <p>What an endpoint can answer with is read from the compiled classes by
 * {@link ApiErrorCodes}, which walks the resource and the mappers it hands the
 * request to. A code the engine returns from further in is named by hand on the
 * endpoints that return it, and is not checked here - the doc comment of
 * {@code ApiErrorCodes} says why.
 */
public class ErrorCodeCoverageTest {
	/**
	 * The codes no endpoint has to name, each because it says nothing a caller
	 * can act on:
	 *
	 * <ul>
	 *   <li>{@code validation} is the envelope of a failed request, and the
	 *   codes a caller branches on are the ones inside its {@code errors}
	 *   <li>{@code index:io_error} is answered with a 500, the same as any
	 *   other failure of the node itself
	 * </ul>
	 */
	private static final Set<String> UNIVERSAL = Set.of("validation", "index:io_error");

	@Test
	void everyEndpointNamesTheCodesItCanAnswerWith() throws Exception {
		var named = named();
		var missing = new ArrayList<String>();

		for(var operation : ApiErrorCodes.byOperation().entrySet()) {
			var declared = named.getOrDefault(operation.getKey(), Set.of());

			for(var code : operation.getValue()) {
				if(!UNIVERSAL.contains(code) && !declared.contains(code)) {
					missing.add(operation.getKey() + " can answer with " + code);
				}
			}
		}

		assertThat(missing, is(empty()));
	}

	@Test
	void theEndpointsAreActuallyBeingWalked() throws Exception {
		// A walk that found nothing would let every endpoint through
		var found = new TreeSet<String>();
		ApiErrorCodes.byOperation().values().forEach(found::addAll);

		assertThat(found, is(not(empty())));
	}

	/** The codes each operation of the API names, by the key it is served at. */
	private static Map<String, Set<String>> named() throws Exception {
		var named = new HashMap<String, Set<String>>();

		for(var endpoint : ApiEndpoints.endpoints()) {
			var codes = named.computeIfAbsent(
				ApiEndpoints.key(endpoint),
				key -> new HashSet<>()
			);

			for(var code : endpoint.getAnnotationsByType(ReturnsError.class)) {
				codes.add(code.value());
			}
		}

		return named;
	}
}
