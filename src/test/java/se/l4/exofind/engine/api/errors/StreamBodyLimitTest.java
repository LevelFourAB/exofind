package se.l4.exofind.engine.api.errors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Isolated;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;

/**
 * That a streamed request past the size an operator states is refused, and that
 * the refusal says how much of the batch the index took.
 *
 * <p>A streamed request is acted on as it is read, so a refusal part way
 * through leaves documents indexed. The answer carries {@code processed}, the
 * count of those, which is what lets a caller send the rest rather than the
 * whole dataset again.
 */
@Isolated
@QuarkusTest
@TestProfile(StreamBodyLimitTest.Node.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class StreamBodyLimitTest {
	/** The size the node accepts for a streamed body, in bytes. */
	private static final int LIMIT = 4096;

	/** A node on its own disk that accepts a streamed body of 4K. */
	public static class Node implements QuarkusTestProfile {
		@Override
		public Map<String, String> getConfigOverrides() {
			Path directory;
			try {
				directory = Files.createTempDirectory("exofind-stream-limit-test");
			} catch(IOException e) {
				throw new IllegalStateException("Could not make a directory to run in", e);
			}

			return Map.of(
				"exofind.storage.mode", "local",
				"exofind.storage.local.directory", directory.toString(),
				"exofind.api.max-body-size", "2K",
				"exofind.api.max-stream-body-size", "4K"
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
	 * A stream past the size a body held in memory may have, but under the size
	 * a streamed body may have. The media type is what decides which size
	 * applies, so this one is indexed.
	 */
	@Test
	@Order(2)
	void testAStreamUnderTheStreamedSizeIsIndexed() throws Exception {
		var body = StreamedRequestBodyTest.documents(80);
		assertThat(body.length, is(greaterThan(2048)));
		assertThat(body.length, is(lessThan(LIMIT)));

		var response = StreamedRequestBodyTest.stream(
			"/v1alpha1/indexes/hats/documents",
			"application/x-ndjson",
			body
		);

		assertThat(response.statusCode(), is(200));
	}

	/**
	 * A stream past the size a streamed body may have. It is refused where it
	 * passes the size rather than where it started, so the documents before
	 * that are indexed and counted.
	 */
	@Test
	@Order(3)
	void testAStreamPastTheStreamedSizeIsRefusedAndSaysHowFarItGot() throws Exception {
		var body = StreamedRequestBodyTest.documents(2000);

		var response = StreamedRequestBodyTest.stream(
			"/v1alpha1/indexes/hats/documents",
			"application/x-ndjson",
			body
		);
		var answer = StreamedRequestBodyTest.json(response);
		var arguments = answer.get("errors").get(0).get("arguments");

		assertThat(response.statusCode(), is(413));
		assertThat(answer.get("code").asText(), is("request:body_too_large"));
		assertThat(arguments.get("limit").asText(), is(Integer.toString(LIMIT)));
		assertThat(arguments.get("processed").asInt(), is(greaterThan(0)));
	}
}
