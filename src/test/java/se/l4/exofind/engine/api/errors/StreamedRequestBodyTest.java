package se.l4.exofind.engine.api.errors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Isolated;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;

/**
 * That a newline delimited request is bounded by the size stated for a streamed
 * body, and that the size stated for a body held in memory does not bound it.
 *
 * <p>The cases here send their body with chunked transfer encoding, which
 * states no {@code Content-Length}. That is how a client streams a dataset it
 * has not counted, and it is the case {@link RefusedRequestRoute} cannot
 * answer, because there is no length to read before the body arrives. What
 * bounds such a body is {@link RequestBodyLimitFilter}, counting the bytes.
 *
 * <p>The node runs with a small size for a body held in memory and no size at
 * all for a streamed one, which is how the settings ship. A stream far past the
 * first size is therefore indexed in full.
 */
@Isolated
@QuarkusTest
@TestProfile(StreamedRequestBodyTest.Node.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class StreamedRequestBodyTest {
	/** How many documents the streamed cases send. */
	private static final int DOCUMENTS = 2000;

	/**
	 * A node on its own disk that accepts a body of 2K in memory and a streamed
	 * body of any size.
	 */
	public static class Node implements QuarkusTestProfile {
		@Override
		public Map<String, String> getConfigOverrides() {
			Path directory;
			try {
				directory = Files.createTempDirectory("exofind-streamed-body-test");
			} catch(IOException e) {
				throw new IllegalStateException("Could not make a directory to run in", e);
			}

			return Map.of(
				"exofind.storage.mode", "local",
				"exofind.storage.local.directory", directory.toString(),
				"exofind.api.max-body-size", "2K"
			);
		}
	}

	/** An index for the cases to write to. */
	@Test
	@Order(1)
	void testTheIndexTheRestIsWrittenToIsDefined() {
		given()
			.contentType(ContentType.JSON)
			.body("""
				{
					"fields": {
						"id": { "type": "string", "primaryKey": true, "required": true },
						"name": { "type": "string", "matching": {} }
					}
				}
				""")
			.when().put("/v1alpha1/admin/indexes/hats")
			.then().statusCode(201);
	}

	/**
	 * A stream far past the size a body held in memory may have. Nothing bounds
	 * a streamed body unless an operator says so, so every document is indexed.
	 */
	@Test
	@Order(2)
	void testAStreamPastTheSizeOfABufferedBodyIsIndexed() throws Exception {
		var body = documents(DOCUMENTS);
		assertThat("the body is past the size a buffered body may have", body.length > 2048, is(true));

		var response = stream("/v1alpha1/indexes/hats/documents", "application/x-ndjson", body);

		assertThat(response.statusCode(), is(200));
		assertThat(json(response).get("indexed").asInt(), is(DOCUMENTS));
	}

	/**
	 * A body held in memory, sent the same way. It states no length either, so
	 * the bytes are what it is refused by - with the body every other failure
	 * carries, which is what the framework's own refusal leaves out.
	 */
	@Test
	@Order(3)
	void testABufferedBodyPastItsSizeIsRefusedWithACode() throws Exception {
		var body = ("{ \"query\": \"" + "x".repeat(4000) + "\" }")
			.getBytes(StandardCharsets.UTF_8);

		var response = stream("/v1alpha1/indexes/hats/search", "application/json", body);
		var answer = json(response);

		assertThat(response.statusCode(), is(413));
		assertThat(answer.get("code").asText(), is("request:body_too_large"));
		assertThat(answer.get("errors").get(0).get("arguments").get("limit").asText(), is("2048"));
	}

	/** The documents of a streamed request, as the bytes of the body. */
	static byte[] documents(int count) {
		var body = new StringBuilder();

		for(var i = 0; i < count; i++) {
			body.append("{\"id\": \"").append(i)
				.append("\", \"name\": \"Sun hat ").append(i)
				.append("\"}\n");
		}

		return body.toString().getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Send a body without stating its length, the way a client streams a
	 * dataset it has not counted. RestAssured states a length for every body it
	 * sends, so the request is made here instead.
	 */
	static HttpResponse<String> stream(
		String path,
		String contentType,
		byte[] body
	) throws Exception {
		var request = HttpRequest.newBuilder(URI.create(
				"http://localhost:" + RestAssured.port + path
			))
			.header("Content-Type", contentType)
			.POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
			.build();

		try(var client = HttpClient.newHttpClient()) {
			return client.send(request, HttpResponse.BodyHandlers.ofString());
		}
	}

	/** The answer as JSON, so a test reads the fields of the error body. */
	static JsonNode json(HttpResponse<String> response) throws IOException {
		return new ObjectMapper().readTree(response.body());
	}
}
