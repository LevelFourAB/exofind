package se.l4.exofind.engine.api.errors;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;

import se.l4.exofind.engine.api.ApiEndpoints;

/**
 * Writes the error codes an endpoint answers with into the OpenAPI document,
 * from the {@link ReturnsError} annotations the endpoint carries.
 *
 * <p>A code is what a client branches on, so it belongs in the document as a
 * value rather than in a sentence of a description. Each answer that carries
 * codes is given one extension, {@code x-error-codes}, holding an object per
 * code with:
 *
 * <ul>
 *   <li>{@code code}, as the {@code code} field of the error response spells it
 *   <li>{@code when}, the one sentence that says what produces it
 * </ul>
 *
 * <p>The website reads that extension and draws a row per code under the
 * answer; {@code website/src/openapi/spec.mjs} is the other half of that
 * agreement. Generated clients carry the codes as well, because the filter also
 * closes the description of the answer with them - a client generator reads
 * descriptions and ignores extensions it does not know.
 *
 * <p>The filter runs while the document is written, which is at build time, and
 * it walks the compiled endpoints through {@link ApiEndpoints}. A code declared
 * for a status the endpoint does not answer with fails the build, because the
 * document would otherwise carry a code under no answer.
 */
public class ErrorCodeFilter implements OASFilter {
	/** The codes one answer carries, each with the condition that returns it. */
	public static final String CODES = "x-error-codes";

	/** The key naming the code inside one entry of the extension. */
	public static final String CODE = "code";

	/** The key holding what makes an endpoint answer with the code. */
	public static final String WHEN = "when";

	/** How the closing paragraph of an answer's description starts. */
	public static final String OPENING = "Error codes: ";

	@Override
	public void filterOpenAPI(OpenAPI api) {
		var declared = byEndpoint();
		var paths = api.getPaths();

		if(paths == null || paths.getPathItems() == null) {
			throw new IllegalStateException("The document declares no paths, so nothing was written");
		}

		for(var path : paths.getPathItems().entrySet()) {
			var operations = path.getValue().getOperations();
			if(operations == null) continue;

			for(var operation : operations.entrySet()) {
				var key = ApiEndpoints.key(operation.getKey().name(), path.getKey());
				var codes = declared.get(key);

				if(codes == null || codes.isEmpty()) continue;

				document(operation.getValue(), codes, key);
			}
		}
	}

	/**
	 * Write the codes of one operation onto the answers that carry them.
	 */
	private static void document(
		Operation operation,
		List<ReturnsError> codes,
		String key
	) {
		var responses = operation.getResponses();

		for(var status : byStatus(codes).entrySet()) {
			var response = responses == null
				? null
				: responses.getAPIResponse(String.valueOf(status.getKey()));

			if(response == null) {
				throw new IllegalStateException(
					key + " declares the error code "
						+ status.getValue().get(0).value() + " for status " + status.getKey()
						+ ", which the endpoint does not answer with. Add an @APIResponse for"
						+ " the status, or correct the code"
				);
			}

			var listed = new ArrayList<Map<String, String>>();
			for(var code : status.getValue()) {
				/*
				 * Ordered rather than a Map.of, whose iteration order changes
				 * between runs. The document is checked in under
				 * `website/public/`, so an unordered entry would rewrite every
				 * code each time it is refreshed.
				 */
				var entry = new LinkedHashMap<String, String>();
				entry.put(CODE, code.value());
				entry.put(WHEN, code.when());

				listed.add(entry);
			}

			response.addExtension(CODES, listed);

			var description = response.getDescription();
			response.setDescription(
				description == null || description.isBlank()
					? sentence(status.getValue())
					: description.stripTrailing() + "\n\n" + sentence(status.getValue())
			);
		}
	}

	/**
	 * The codes of one answer as the closing paragraph of its description.
	 *
	 * <p>Written for a reader that sees the description alone, which is what a
	 * generated client carries. The website drops the paragraph and draws the
	 * codes as rows instead.
	 */
	private static String sentence(List<ReturnsError> codes) {
		var text = new StringBuilder(OPENING);

		for(var at = 0; at < codes.size(); at++) {
			if(at > 0) text.append(' ');

			text.append('`').append(codes.get(at).value()).append("` - ")
				.append(codes.get(at).when());
		}

		return text.toString();
	}

	/** The codes of one endpoint grouped by the status they are answered with. */
	private static Map<Integer, List<ReturnsError>> byStatus(List<ReturnsError> codes) {
		var grouped = new LinkedHashMap<Integer, List<ReturnsError>>();

		for(var code : codes) {
			grouped.computeIfAbsent(code.status(), status -> new ArrayList<>()).add(code);
		}

		return grouped;
	}

	/**
	 * Every code the compiled endpoints declare, by the method and path they are
	 * served at.
	 *
	 * <p>Several resource methods can be one operation in the document, which is
	 * how an endpoint accepting a second media type is declared. What such an
	 * endpoint answers with is one set, so the codes of both methods are read.
	 */
	static Map<String, List<ReturnsError>> byEndpoint() {
		var declared = new HashMap<String, List<ReturnsError>>();

		List<Method> endpoints;
		try {
			endpoints = ApiEndpoints.endpoints();
		} catch(Exception e) {
			throw new IllegalStateException(
				"The compiled endpoints could not be walked, so the error codes they answer"
					+ " with could not be written into the document",
				e
			);
		}

		for(var endpoint : endpoints) {
			var codes = endpoint.getAnnotationsByType(ReturnsError.class);
			if(codes.length == 0) continue;

			var listed = declared.computeIfAbsent(
				ApiEndpoints.key(endpoint),
				key -> new ArrayList<>()
			);

			for(var code : codes) {
				if(listed.stream().noneMatch(seen -> seen.value().equals(code.value()))) {
					listed.add(code);
				}
			}
		}

		return declared;
	}
}
