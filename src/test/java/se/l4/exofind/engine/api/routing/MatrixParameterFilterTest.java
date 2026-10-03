package se.l4.exofind.engine.api.routing;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

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

/**
 * That a path holding a semicolon that is not percent-encoded is refused, and
 * that the document named by the part before the semicolon is not touched.
 *
 * <p>Requests are sent with the JDK client, so the path reaches the node byte
 * for byte as written here. A client library can encode the semicolon on its
 * way out.
 */
@Isolated
@QuarkusTest
@TestProfile(MatrixParameterFilterTest.Node.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MatrixParameterFilterTest {
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final HttpClient CLIENT = HttpClient.newHttpClient();

	private static final String DOCUMENTS = "/v1alpha1/indexes/keys/documents";

	/** A node on its own disk. */
	public static class Node implements QuarkusTestProfile {
		@Override
		public Map<String, String> getConfigOverrides() {
			Path directory;
			try {
				directory = Files.createTempDirectory("exofind-matrix-parameter-test");
			} catch(IOException e) {
				throw new IllegalStateException("Could not make a directory to run in", e);
			}

			return Map.of(
				"exofind.storage.mode", "local",
				"exofind.storage.local.directory", directory.toString()
			);
		}
	}

	@Test
	@Order(1)
	void theIndexIsDefinedAndFilled() throws Exception {
		var created = send("PUT", "/v1alpha1/admin/indexes/keys", """
			{
				"fields": {
					"id": { "type": "string", "primaryKey": true, "required": true },
					"code": { "type": "string", "filter": {} }
				}
			}
			""");
		assertThat(created.body(), created.statusCode(), is(201));

		var indexed = send("POST", DOCUMENTS, """
			{ "documents": [ { "id": "x", "code": "original" } ] }
			""");
		assertThat(indexed.body(), indexed.statusCode(), is(200));

		commit();
	}

	@Test
	@Order(2)
	void aPutToAKeyHoldingASemicolonIsRefusedAndLeavesTheDocumentBeforeIt() throws Exception {
		// When
		var put = send("PUT", DOCUMENTS + "/x;y", "{ \"code\": \"overwritten\" }");
		commit();

		// Then
		assertRefused(put);
		assertThat(codeOfX(), is("original"));
	}

	@Test
	@Order(3)
	void aDeleteOfAKeyHoldingASemicolonIsRefusedAndLeavesTheDocumentBeforeIt() throws Exception {
		// When
		var delete = send("DELETE", DOCUMENTS + "/x;y", null);
		commit();

		// Then
		assertRefused(delete);
		var x = send("GET", DOCUMENTS + "/x", null);
		assertThat(x.body(), x.statusCode(), is(200));
	}

	@Test
	@Order(4)
	void aReadOfAKeyHoldingASemicolonIsRefused() throws Exception {
		// When
		var read = send("GET", DOCUMENTS + "/x;y", null);

		// Then the answer is not the document "x"
		assertRefused(read);
	}

	@Test
	@Order(5)
	void aChangeToAKeyHoldingASemicolonIsRefusedAndLeavesTheDocumentBeforeIt() throws Exception {
		// When
		var patch = send("PATCH", DOCUMENTS + "/x;y", "{ \"code\": \"patched\" }");
		commit();

		// Then
		assertRefused(patch);
		assertThat(codeOfX(), is("original"));
	}

	/**
	 * A key that starts with a semicolon leaves an empty segment once the
	 * matrix parameters are removed, which is the path of the whole
	 * collection.
	 */
	@Test
	@Order(6)
	void aReadOfAKeyStartingWithASemicolonIsRefused() throws Exception {
		// When
		var read = send("GET", DOCUMENTS + "/;x", null);

		// Then the answer is not a listing of the collection
		assertRefused(read);
	}

	/**
	 * An index name is cut at the semicolon the same way as a key.
	 */
	@Test
	@Order(7)
	void aWriteToAnIndexNameHoldingASemicolonIsRefused() throws Exception {
		// When
		var put = send(
			"PUT",
			"/v1alpha1/indexes/keys;other/documents/x",
			"{ \"code\": \"overwritten\" }"
		);
		commit();

		// Then
		assertRefused(put);
		assertThat(codeOfX(), is("original"));
	}

	/**
	 * A key holding a semicolon is reached by sending the semicolon encoded.
	 */
	@Test
	@Order(8)
	void aKeyHoldingAnEncodedSemicolonReachesItsOwnDocument() throws Exception {
		// When
		var put = send("PUT", DOCUMENTS + "/z%3By", "{ \"code\": \"own\" }");
		commit();

		// Then
		assertThat(put.body(), put.statusCode(), is(204));
		var read = send("GET", DOCUMENTS + "/z%3By", null);
		assertThat(read.body(), json(read).get("document").get("id").asText(), is("z;y"));
		assertThat(codeOfX(), is("original"));
	}

	/**
	 * Only the path is checked. A semicolon in the query string is a part of
	 * a value.
	 */
	@Test
	@Order(9)
	void aSemicolonInTheQueryStringIsAccepted() throws Exception {
		// When
		var listing = send("GET", DOCUMENTS + "?after=a;b", null);

		// Then
		assertThat(listing.body(), listing.statusCode(), is(200));
	}

	private static void assertRefused(HttpResponse<String> response) throws IOException {
		assertThat(response.body(), response.statusCode(), is(400));
		assertThat(response.body(), json(response).get("code").asText(), is("request:path_invalid"));
	}

	private static String codeOfX() throws Exception {
		var x = send("GET", DOCUMENTS + "/x", null);
		assertThat(x.body(), x.statusCode(), is(200));
		return json(x).get("document").get("code").asText();
	}

	private static void commit() throws Exception {
		var commit = send("POST", "/v1alpha1/admin/indexes/keys/actions/commit", null);
		assertThat(commit.body(), commit.statusCode(), is(200));
	}

	private static HttpResponse<String> send(
		String method,
		String path,
		String body
	) throws Exception {
		var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + RestAssured.port + path))
			.method(
				method,
				body == null
					? HttpRequest.BodyPublishers.noBody()
					: HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
			);

		if(body != null) {
			builder.header("Content-Type", "application/json");
		}

		return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static JsonNode json(HttpResponse<String> response) throws IOException {
		return MAPPER.readTree(response.body());
	}
}
