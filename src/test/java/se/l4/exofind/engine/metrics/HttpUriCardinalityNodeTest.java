package se.l4.exofind.engine.metrics;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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
 * The {@code uri} tag of {@code http.server.requests} on a running node. A
 * request refused before a route is chosen would be reported under the path
 * it arrived with. These cases send such refusals, each to a path that holds a
 * client-chosen document key, and read what {@code /q/metrics} shows.
 */
@Isolated
@QuarkusTest
@TestProfile(HttpUriCardinalityNodeTest.Node.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class HttpUriCardinalityNodeTest {
	/** A node on its own disk that accepts small bodies. */
	public static class Node implements QuarkusTestProfile {
		@Override
		public Map<String, String> getConfigOverrides() {
			Path directory;
			try {
				directory = Files.createTempDirectory("exofind-uri-cardinality-test");
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

	private static final String KEY_PATH = "/v1alpha1/indexes/books/documents/";

	private static final Pattern URI_TAG = Pattern.compile("uri=\"([^\"]*)\"");

	/** The {@code uri} values the HTTP server meter holds right now. */
	private static List<String> uriTags() {
		var text = given().when().get("/q/metrics").then().statusCode(200).extract().asString();

		return text.lines()
			.filter(line -> line.startsWith("http_server_requests_seconds_count"))
			.map(URI_TAG::matcher)
			.filter(m -> m.find())
			.map(m -> m.group(1))
			.distinct()
			.toList();
	}

	private static List<String> leaked() {
		return uriTags().stream().filter(uri -> uri.startsWith(KEY_PATH + "leak-")).toList();
	}

	@Test
	@Order(1)
	void testTheIndexIsDefined() {
		given().contentType(ContentType.JSON)
			.body("""
				{ "fields": { "id": { "type": "string", "primaryKey": true, "required": true } } }
				""")
			.when().put("/v1alpha1/admin/indexes/books")
			.then().statusCode(201);
	}

	/**
	 * A body with a media type the endpoint does not read is refused with
	 * {@code 415} before a resource method is chosen.
	 */
	@Test
	@Order(2)
	void testAnUnsupportedMediaTypeDoesNotMakeTheKeyATagValue() {
		// Given requests with a body the endpoint does not read, each to its own key
		for(var i = 0; i < 5; i++) {
			given().contentType("text/plain").body("x")
				.when().put(KEY_PATH + "leak-415-" + i)
				.then().statusCode(415);
		}

		// When the metrics are read
		var leaked = leaked().stream().filter(uri -> uri.contains("415")).toList();

		// Then no document key became a tag value
		assertThat(leaked, is(empty()));
	}

	/**
	 * An {@code Accept} the endpoint cannot answer in is refused with
	 * {@code 406} before a resource method is chosen.
	 */
	@Test
	@Order(3)
	void testAnUnacceptableAcceptDoesNotMakeTheKeyATagValue() {
		// Given reads that accept only a media type the endpoint does not write
		for(var i = 0; i < 5; i++) {
			given().accept("image/png")
				.when().get(KEY_PATH + "leak-406-" + i)
				.then().statusCode(406);
		}

		// When the metrics are read
		var leaked = leaked().stream().filter(uri -> uri.contains("406")).toList();

		// Then no document key became a tag value
		assertThat(leaked, is(empty()));
	}

	/**
	 * A body that states a length past the limit is refused with {@code 413}
	 * by a router handler, before any route is chosen.
	 */
	@Test
	@Order(4)
	void testABodyPastTheLimitDoesNotMakeTheKeyATagValue() {
		// Given writes whose body is past the limit, each to its own key
		var body = "{\"id\":\"" + "x".repeat(4096) + "\"}";
		for(var i = 0; i < 5; i++) {
			given().contentType(ContentType.JSON).body(body)
				.when().put(KEY_PATH + "leak-413-" + i)
				.then().statusCode(413);
		}

		// When the metrics are read
		var leaked = leaked().stream().filter(uri -> uri.contains("413")).toList();

		// Then no document key became a tag value
		assertThat(leaked, is(empty()));
	}

	/**
	 * A method the path does not have is refused with {@code 405} before a
	 * resource method is chosen. The index name in the path is the
	 * client-chosen part here.
	 */
	@Test
	@Order(5)
	void testAMethodThePathDoesNotHaveDoesNotMakeTheNameATagValue() {
		for(var i = 0; i < 5; i++) {
			given().when().delete("/v1alpha1/indexes/leak-405-" + i + "/search")
				.then().statusCode(405);
		}

		assertThat(uriTags().stream().filter(uri -> uri.contains("leak-405")).toList(), is(empty()));
		assertThat(uriTags(), hasItem(HttpUriCardinality.UNKNOWN));
	}

	/**
	 * A route with no path parameter has a template equal to its path. A
	 * refusal of a request that reached it keeps the route's name.
	 */
	@Test
	@Order(6)
	void testARefusalOnARouteWithoutParametersKeepsTheRoute() {
		given().contentType(ContentType.JSON).body("{ \"notAProperty\": 1 }")
			.when().post("/v1alpha1/admin/keys")
			.then().statusCode(400);

		assertThat(uriTags(), hasItem("/v1alpha1/admin/keys"));
	}

	/**
	 * A path no route has keeps the value the framework gives it, and the
	 * non-application endpoints stay out of the meter.
	 */
	@Test
	@Order(7)
	void testAPathNoRouteHasIsNotFoundAndMetricsAreNotCounted() {
		given().when().get("/not-a-route-" + 1).then().statusCode(404);

		var uris = uriTags();
		assertThat(uris, hasItem("NOT_FOUND"));
		assertThat(uris.stream().filter(uri -> uri.startsWith("/q/")).toList(), is(empty()));
		assertThat(uris, not(hasItem(HttpUriCardinality.UNROUTED)));
	}

	/**
	 * The bound of {@code exofind.metrics.http.max-uri-tags} is shared. Paths a
	 * client chose use it up, and a route used for the first time after that
	 * loses its own series.
	 */
	@Test
	@Order(8)
	void testARouteUsedLateKeepsItsTemplateAfterClientsSendRefusedRequests() {
		// Given more refused requests to distinct keys than the bound of 200
		for(var i = 0; i < 210; i++) {
			given().contentType("text/plain").body("x")
				.when().put(KEY_PATH + "flood-" + i);
		}

		// When a route is used for the first time
		given().contentType(ContentType.JSON)
			.when().post("/v1alpha1/admin/indexes/books/actions/commit")
			.then().statusCode(200);

		// Then it is reported under its template
		assertThat(uriTags(), hasItem("/v1alpha1/admin/indexes/{name}/actions/commit"));
	}
}
