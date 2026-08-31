package se.l4.exofind.engine.api.v1alpha1.documents;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

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
 * Reading documents back out over a real connection, for the parts that only a
 * request can answer: which of the two formats a caller gets, and that a key
 * granted searching alone does not reach the documents themselves.
 *
 * <p>What each format holds is tested against the endpoint directly - see
 * {@code DocumentScanResourceTest}. What is here is the negotiation between
 * them, which lives in the annotations rather than in any code that could be
 * called.
 */
@Isolated
@QuarkusTest
@TestProfile(DocumentScanNegotiationTest.LocalStorage.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DocumentScanNegotiationTest {
	public static class LocalStorage implements QuarkusTestProfile {
		static final String ROOT_KEY = "exok_root_document_scan_test";

		@Override
		public Map<String, String> getConfigOverrides() {
			Path directory;
			try {
				directory = Files.createTempDirectory("exofind-document-scan-test");
			} catch(IOException e) {
				throw new IllegalStateException("Could not make a directory to run in", e);
			}

			return Map.of(
				"exofind.storage.mode", "local",
				"exofind.storage.local.directory", directory.toString(),
				"exofind.auth.mode", "keys",
				"exofind.auth.root-key", ROOT_KEY
			);
		}
	}

	private static io.restassured.specification.RequestSpecification asRoot() {
		return given()
			.header("Authorization", "Bearer " + LocalStorage.ROOT_KEY)
			.contentType(ContentType.JSON);
	}

	@Test
	@Order(1)
	void theIndexIsFilled() {
		asRoot()
			.body("""
				{
					"fields": {
						"id": { "type": "string", "primaryKey": true, "required": true },
						"title": { "type": "string", "matching": {} }
					}
				}
				""")
			.when().put("/v1alpha1/admin/indexes/books")
			.then().statusCode(201);

		asRoot()
			.body("""
				{
					"documents": [
						{ "id": "1", "title": "Silent Spring" },
						{ "id": "2", "title": "The Overstory" }
					]
				}
				""")
			.when().post("/v1alpha1/indexes/books/documents")
			.then().statusCode(200);

		asRoot()
			.when().post("/v1alpha1/admin/indexes/books/actions/commit")
			.then().statusCode(200);
	}

	/**
	 * A caller that says nothing about what it wants gets the answer with the
	 * key to carry on after in it, rather than the stream that says nothing but
	 * the documents.
	 */
	@Test
	@Order(2)
	void aCallerThatAsksForAnythingGetsJson() {
		given()
			.header("Authorization", "Bearer " + LocalStorage.ROOT_KEY)
			.header("Accept", "*/*")
			.when().get("/v1alpha1/indexes/books/documents")
			.then()
			.statusCode(200)
			.contentType("application/json")
			.body("documents.id", is(java.util.List.of("1", "2")));
	}

	@Test
	@Order(3)
	void aCallerThatAsksForTheStreamGetsOneDocumentPerLine() {
		var body = given()
			.header("Authorization", "Bearer " + LocalStorage.ROOT_KEY)
			.header("Accept", "application/x-ndjson")
			.when().get("/v1alpha1/indexes/books/documents")
			.then()
			.statusCode(200)
			.contentType("application/x-ndjson")
			.extract().body().asString();

		assertThat(
			java.util.List.of(body.split("\n")),
			contains(
				"{\"id\":\"1\",\"title\":\"Silent Spring\"}",
				"{\"id\":\"2\",\"title\":\"The Overstory\"}"
			)
		);
	}

	@Test
	@Order(4)
	void aLimitOutsideWhatIsAllowedIsRefused() {
		asRoot()
			.when().get("/v1alpha1/indexes/books/documents?limit=0")
			.then()
			.statusCode(400)
			.body("errors[0].code", is("request:scan:limit_invalid"));
	}

	/**
	 * Reading the documents is its own permission, so a key that may search
	 * the index cannot read what is in it document by document.
	 */
	@Test
	@Order(5)
	void aKeyThatMaySearchMayNotReadTheDocuments() {
		var credential = asRoot()
			.body("""
				{
					"description": "the search backend",
					"grants": [
						{ "permissions": ["search"], "indexes": ["books"] }
					]
				}
				""")
			.when().post("/v1alpha1/admin/keys")
			.then()
			.statusCode(201)
			.extract().path("credential").toString();

		given()
			.header("Authorization", "Bearer " + credential)
			.when().get("/v1alpha1/indexes/books/documents")
			.then().statusCode(403);
	}

	@Test
	@Order(6)
	void aKeyGrantedTheDocumentsReadsThem() {
		var credential = asRoot()
			.body("""
				{
					"description": "the rebuild",
					"grants": [
						{ "permissions": ["documents.read"], "indexes": ["books"] }
					]
				}
				""")
			.when().post("/v1alpha1/admin/keys")
			.then()
			.statusCode(201)
			.extract().path("credential").toString();

		given()
			.header("Authorization", "Bearer " + credential)
			.when().get("/v1alpha1/indexes/books/documents")
			.then()
			.statusCode(200)
			.body("documents.size()", is(2));
	}
}
