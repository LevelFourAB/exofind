package se.l4.exofind.engine.api.errors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

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
import io.restassured.specification.RequestSpecification;

/**
 * That a request refused before an endpoint ran is answered with the error body
 * the API states. Without these cases the framework answers such a request with
 * a shape of its own, or with nothing at all.
 *
 * <p>Each case below is refused by a different part of the stack - the JSON
 * reader, the code that picks a resource method, and the HTTP router that
 * counts the bytes of a body - and a client reads one body for all of them. The
 * cases run as real requests, because the framework refuses them before any
 * code of this project is reached.
 */
@Isolated
@QuarkusTest
@TestProfile(RefusedRequestBodyTest.Node.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RefusedRequestBodyTest {
	/**
	 * A node on its own disk, accepting bodies up to a size small enough to
	 * send one past it in a test.
	 */
	public static class Node implements QuarkusTestProfile {
		@Override
		public Map<String, String> getConfigOverrides() {
			Path directory;
			try {
				directory = Files.createTempDirectory("exofind-refused-request-test");
			} catch(IOException e) {
				throw new IllegalStateException("Could not make a directory to run in", e);
			}

			return Map.of(
				"exofind.storage.mode", "local",
				"exofind.storage.local.directory", directory.toString(),
				"quarkus.http.limits.max-body-size", "2K"
			);
		}
	}

	private static RequestSpecification request() {
		return given().contentType(ContentType.JSON);
	}

	/** An index for the cases that are refused on the way to an endpoint. */
	@Test
	@Order(1)
	void testTheIndexTheRestIsAskedOfIsDefined() {
		request()
			.body("""
				{
					"fields": {
						"id": { "type": "string", "primaryKey": true, "required": true },
						"name": { "type": "string", "matching": {} }
					}
				}
				""")
			.when().put("/v1alpha1/admin/indexes/books")
			.then().statusCode(201);
	}

	/**
	 * A property no model has. Dropping it would serve the request with what
	 * the property named left at its default, so it is refused and named.
	 */
	@Test
	@Order(2)
	void testAnUnknownPropertyIsRefusedAndNamed() {
		request()
			.body("""
				{
					"fields": { "id": { "type": "string", "primaryKey": true } },
					"localefallback": { "chain": ["en"] }
				}
				""")
			.when().put("/v1alpha1/admin/indexes/unknown-property")
			.then()
			.statusCode(400)
			.body("code", is("request:unknown_property"))
			.body("errors[0].code", is("request:unknown_property"))
			.body("errors[0].path", is("/localefallback"))
			.body("errors[0].arguments.property", is("localefallback"));
	}

	/**
	 * A property nested inside the request. The path is a JSON Pointer, so it
	 * names the place in the body as the caller wrote it.
	 */
	@Test
	@Order(3)
	void testAnUnknownPropertyReportsWhereItSits() {
		request()
			.body("""
				{
					"fields": {
						"id": { "type": "string", "primaryKey": true, "sortable": true }
					}
				}
				""")
			.when().put("/v1alpha1/admin/indexes/unknown-nested")
			.then()
			.statusCode(400)
			.body("code", is("request:unknown_property"))
			.body("errors[0].path", is("/fields/id/sortable"));
	}

	/**
	 * A body that is not JSON. There is no property to point at, so the reason
	 * and the place the parse stopped are what say where to look.
	 */
	@Test
	@Order(4)
	void testABodyThatIsNotJsonIsABadRequest() {
		request()
			.body("{ \"fields\": ")
			.when().put("/v1alpha1/admin/indexes/malformed")
			.then()
			.statusCode(400)
			.body("code", is("request:malformed"))
			.body("errors[0].code", is("request:malformed"))
			.body("errors[0].arguments.line", is("1"))
			.body("errors[0].arguments.column", notNullValue());
	}

	/**
	 * A value of the wrong type. The path names the property it is written at.
	 */
	@Test
	@Order(5)
	void testAValueOfTheWrongTypeIsABadRequest() {
		request()
			.body("{ \"limit\": \"lots\" }")
			.when().post("/v1alpha1/indexes/books/search")
			.then()
			.statusCode(400)
			.body("code", is("request:value_invalid"))
			.body("errors[0].code", is("request:value_invalid"))
			.body("errors[0].path", is("/limit"));
	}

	/**
	 * A tag naming no member of a tagged union. The value of the tag is what is
	 * wrong, so it is reported as an invalid value.
	 */
	@Test
	@Order(6)
	void testATypeThatNamesNothingIsABadRequest() {
		request()
			.body("""
				{
					"fields": { "id": { "type": "nonesuch" } }
				}
				""")
			.when().put("/v1alpha1/admin/indexes/unknown-type")
			.then()
			.statusCode(400)
			.body("code", is("request:value_invalid"))
			.body("errors[0].path", startsWith("/fields"));
	}

	/**
	 * A path no endpoint answers. The router refuses it, and it carries the
	 * same body as every other failure.
	 */
	@Test
	@Order(7)
	void testAPathNoEndpointAnswersIsNotFound() {
		request()
			.when().get("/v1alpha1/nothing/here")
			.then()
			.statusCode(404)
			.body("code", is("request:not_found"))
			.body("errors[0].code", is("request:not_found"));
	}

	/**
	 * A method the path is not answered for.
	 */
	@Test
	@Order(8)
	void testAMethodThePathDoesNotHaveIsRefused() {
		request()
			.when().delete("/v1alpha1/indexes/books/search")
			.then()
			.statusCode(405)
			.body("code", is("request:method_not_allowed"));
	}

	/**
	 * A body in a media type the endpoint does not read.
	 */
	@Test
	@Order(9)
	void testAMediaTypeTheEndpointDoesNotReadIsRefused() {
		given()
			.contentType("text/plain")
			.body("hello")
			.when().post("/v1alpha1/indexes/books/search")
			.then()
			.statusCode(415)
			.body("code", is("request:unsupported_media_type"));
	}

	/**
	 * An {@code Accept} naming nothing the endpoint answers in.
	 */
	@Test
	@Order(10)
	void testAnAcceptTheEndpointCanNotAnswerIsRefused() {
		given()
			.contentType(ContentType.JSON)
			.accept("text/plain")
			.body("{}")
			.when().post("/v1alpha1/indexes/books/search")
			.then()
			.statusCode(406)
			.body("code", is("request:not_acceptable"));
	}

	/**
	 * A body past the size the node accepts, which the HTTP router refuses
	 * while it is still arriving - before there is an endpoint to refuse it.
	 */
	@Test
	@Order(11)
	void testABodyPastTheSizeTheNodeAcceptsIsRefused() {
		request()
			.body("{ \"query\": \"" + "x".repeat(4000) + "\" }")
			.when().post("/v1alpha1/indexes/books/search")
			.then()
			.statusCode(413)
			.body("code", is("request:too_large"))
			.body("errors[0].code", is("request:too_large"));
	}
}
