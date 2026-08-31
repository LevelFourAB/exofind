package se.l4.exofind.engine.storage;

import static io.restassured.RestAssured.given;
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
 * A whole node running on its own disk, doing what a deployment does: define
 * an index, put a document in, commit it, search for it, and create a key that
 * reaches it.
 *
 * <p>Everything else about local storage is tested a piece at a time. This is
 * here for the wiring between the pieces - which registry, which key store and
 * which indexer role a node ends up with is decided once at startup, and
 * nothing below this level would notice if that decision stopped being made.
 */
@Isolated
@QuarkusTest
@TestProfile(LocalStorageNodeTest.LocalStorage.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class LocalStorageNodeTest {
	/**
	 * A node storing locally, checking credentials the way any deployment
	 * outside dev mode does so that the keys on disk are actually used.
	 */
	public static class LocalStorage implements QuarkusTestProfile {
		static final String ROOT_KEY = "exok_root_local_storage_test";

		@Override
		public Map<String, String> getConfigOverrides() {
			Path directory;
			try {
				directory = Files.createTempDirectory("exofind-local-storage-test");
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

	/**
	 * A node storing locally is the only node there is, so it takes the
	 * indexer role without being asked to - otherwise every write below would
	 * be refused as read-only.
	 */
	@Test
	@Order(1)
	void testTheIndexIsCreated() {
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
	}

	@Test
	@Order(2)
	void testADocumentIsIndexedAndFound() {
		asRoot()
			.body("""
				{
					"documents": [
						{ "id": "1", "title": "Silent Spring" }
					]
				}
				""")
			.when().post("/v1alpha1/indexes/books/documents")
			.then().statusCode(200);

		asRoot()
			.when().post("/v1alpha1/admin/indexes/books/actions/commit")
			.then().statusCode(200);

		asRoot()
			.body("""
				{
					"query": [
						{ "type": "text", "text": "silent" }
					]
				}
				""")
			.when().post("/v1alpha1/indexes/books/search")
			.then()
			.statusCode(200)
			.body("total.count", is(1));
	}

	/**
	 * The registry is a file beside the indexes rather than an object, so an
	 * index created above is one the node still knows about.
	 */
	@Test
	@Order(3)
	void testTheIndexIsListed() {
		asRoot()
			.when().get("/v1alpha1/admin/indexes")
			.then()
			.statusCode(200)
			.body("indexes.name", is(java.util.List.of("books")));
	}

	/**
	 * Keys are kept on disk too, so a node storing locally can be given a key
	 * rather than being reachable only with the root key it was started with.
	 */
	@Test
	@Order(4)
	void testAKeyIsCreatedAndWorks() {
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
			.contentType(ContentType.JSON)
			.body("""
				{
					"query": [
						{ "type": "text", "text": "silent" }
					]
				}
				""")
			.when().post("/v1alpha1/indexes/books/search")
			.then()
			.statusCode(200)
			.body("total.count", is(1));

		/*
		 * The key was granted searching alone, so it reaches the documents and
		 * nothing else - which is the check that what was written to disk came
		 * back as the grants it was created with.
		 */
		given()
			.header("Authorization", "Bearer " + credential)
			.when().get("/v1alpha1/admin/keys")
			.then().statusCode(403);
	}
}
