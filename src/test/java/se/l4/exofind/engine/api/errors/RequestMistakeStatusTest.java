package se.l4.exofind.engine.api.errors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * That a mistake in a request is answered with the status that says whose it
 * is: {@code 400} for a request to rewrite, {@code 404} for a name that stands
 * for nothing.
 *
 * <p>The status is decided in one place, {@link EngineExceptionMapper}, from
 * the class of the exception. Every case below reaches that mapper through a
 * real request, so an exception that stops being one of the classes it names
 * shows here as a {@code 500} rather than passing as a unit test of the class
 * it no longer has.
 */
@Isolated
@QuarkusTest
@TestProfile(RequestMistakeStatusTest.Node.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RequestMistakeStatusTest {
	/**
	 * A node on its own disk, which takes the indexer role without being asked
	 * and so accepts the writes the cases below are built on.
	 */
	public static class Node implements QuarkusTestProfile {
		@Override
		public Map<String, String> getConfigOverrides() {
			Path directory;
			try {
				directory = Files.createTempDirectory("exofind-request-mistake-test");
			} catch(IOException e) {
				throw new IllegalStateException("Could not make a directory to run in", e);
			}

			return Map.of(
				"exofind.storage.mode", "local",
				"exofind.storage.local.directory", directory.toString()
			);
		}
	}

	private static RequestSpecification request() {
		return given().contentType(ContentType.JSON);
	}

	/**
	 * Products holding one variant, which is what a clause reaching inside an
	 * object needs to be refused for reaching outside a {@code nested} clause.
	 */
	@Test
	@Order(1)
	void testTheIndexTheRestIsAskedOfIsDefined() {
		request()
			.body("""
				{
					"fields": {
						"id": { "type": "string", "primaryKey": true, "required": true },
						"name": { "type": "string", "matching": {} },
						"variants": {
							"type": "object",
							"multiple": true,
							"mode": "nested",
							"fields": {
								"color": { "type": "string", "filter": {} }
							}
						}
					}
				}
				""")
			.when().put("/v1alpha1/admin/indexes/products")
			.then().statusCode(201);

		request()
			.body("""
				{
					"documents": [
						{ "id": "1", "name": "Rain jacket", "variants": [{ "color": "red" }] }
					]
				}
				""")
			.when().post("/v1alpha1/indexes/products/documents")
			.then().statusCode(200);

		request()
			.when().post("/v1alpha1/admin/indexes/products/actions/commit")
			.then().statusCode(200);
	}

	/**
	 * A search the index cannot answer as written. Rewriting it is what makes
	 * it run, so it is the request that is reported as wrong.
	 */
	@Test
	@Order(2)
	void testAQueryTheIndexCanNotAnswerIsABadRequest() {
		request()
			.body("""
				{
					"query": [
						{ "field": "variants.color", "match": { "value": "red" } }
					]
				}
				""")
			.when().post("/v1alpha1/indexes/products/search")
			.then()
			.statusCode(400)
			.body("code", is("index:query:nested:outside"));
	}

	/**
	 * A definition that contradicts itself. Reported as a validation failure,
	 * which is what gives the caller the path of the part to fix.
	 */
	@Test
	@Order(3)
	void testADefinitionThatContradictsItselfIsABadRequest() {
		request()
			.body("""
				{
					"fields": {
						"id": { "type": "string", "primaryKey": true, "required": true },
						"title": { "type": "string", "matching": { "analyzer": {} } }
					}
				}
				""")
			.when().put("/v1alpha1/admin/indexes/contradictory")
			.then()
			.statusCode(400)
			.body("code", is("validation"))
			.body("errors[0].code", is("index:field:analyzer:invalid"))
			.body("errors[0].path", is("fields.title.matching.analyzer"));
	}

	/**
	 * A `null` written where a value goes. The stored format has no way to hold
	 * one, so it is refused by the place it sits rather than left to fail
	 * somewhere that names nothing.
	 */
	@Test
	@Order(4)
	void testANullInsideAListIsABadRequest() {
		request()
			.body("""
				{
					"fields": {
						"id": { "type": "string", "primaryKey": true, "required": true }
					},
					"localeFallback": { "chain": ["en", null] }
				}
				""")
			.when().put("/v1alpha1/admin/indexes/nulls")
			.then()
			.statusCode(400)
			.body("code", is("validation"))
			.body("errors[0].code", is("request:value_required"))
			.body("errors[0].path", is("localeFallback.chain[1]"));
	}

	/**
	 * A line of a streamed request that is not JSON, which is read one document
	 * at a time rather than parsed whole. The position says which line to fix.
	 */
	@Test
	@Order(5)
	void testADocumentThatIsNotJsonIsABadRequest() {
		given()
			.contentType("application/x-ndjson")
			.body(
				"{\"id\": \"2\", \"name\": \"Sun hat\"}\n{\"id\": \"3\", \"name\": }\n"
					.getBytes(StandardCharsets.UTF_8)
			)
			.when().post("/v1alpha1/indexes/products/documents")
			.then()
			.statusCode(400)
			.body("code", is("validation"))
			.body("errors[0].code", is("request:document:malformed"));
	}

	/**
	 * An explanation names one hit. Without a key it names none, which is the
	 * request being incomplete rather than the hit being missing.
	 */
	@Test
	@Order(6)
	void testExplainWithoutAKeyIsABadRequest() {
		request()
			.body("{}")
			.when().post("/v1alpha1/indexes/products/search/actions/explain")
			.then()
			.statusCode(400)
			.body("code", is("validation"))
			.body("errors[0].code", is("search:explain:key_required"));
	}

	/**
	 * A key the index holds nothing under. The request is well formed and names
	 * something that is not there, the same as a document read by its key.
	 */
	@Test
	@Order(7)
	void testExplainOfAKeyThatIsNotIndexedIsNotFound() {
		request()
			.body("{}")
			.when()
			.post("/v1alpha1/indexes/products/search/actions/explain?key=404")
			.then()
			.statusCode(404)
			.body("code", is("index:explain:document_not_found"));
	}
}
