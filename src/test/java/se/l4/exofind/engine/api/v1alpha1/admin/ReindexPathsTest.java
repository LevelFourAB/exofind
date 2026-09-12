package se.l4.exofind.engine.api.v1alpha1.admin;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.hasItem;
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
import io.restassured.specification.RequestSpecification;

/**
 * Where the reindex endpoints answer, over HTTP rather than by calling the
 * resource.
 *
 * <p>Starting a job is an action on the generation it fills, while the job
 * record belongs to the index and answers beside the listing of every job. The
 * two names are different things, so a request reaching the wrong one is the
 * mistake this is here to catch.
 *
 * <p>The requests are sent with keys granted one index rather than with the
 * root key, because what the permission is checked against is the name in the
 * path. A key granted {@code books} reaching a job on another index is the only
 * sign that the check still reads the name the job belongs to.
 */
@Isolated
@QuarkusTest
@TestProfile(ReindexPathsTest.LocalStorage.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ReindexPathsTest {
	/**
	 * A node storing locally and checking credentials, so that the keys the
	 * tests create are the ones the requests are answered against.
	 */
	public static class LocalStorage implements QuarkusTestProfile {
		static final String ROOT_KEY = "exok_root_reindex_paths_test";

		@Override
		public Map<String, String> getConfigOverrides() {
			Path directory;
			try {
				directory = Files.createTempDirectory("exofind-reindex-paths-test");
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

	private static String readingBooks;
	private static String reindexingBooks;

	private static RequestSpecification asRoot() {
		return given()
			.header("Authorization", "Bearer " + LocalStorage.ROOT_KEY)
			.contentType(ContentType.JSON);
	}

	private static RequestSpecification as(String credential) {
		return given()
			.header("Authorization", "Bearer " + credential)
			.contentType(ContentType.JSON);
	}

	private static String keyGranting(String permissions) {
		return asRoot()
			.body("""
				{
					"description": "a test key",
					"grants": [
						{ "permissions": [%s], "indexes": ["books", "books@*"] }
					]
				}
				""".formatted(permissions))
			.when().post("/v1alpha1/admin/keys")
			.then()
			.statusCode(201)
			.extract().path("credential").toString();
	}

	private static String definition() {
		return """
			{
				"fields": {
					"id": { "type": "string", "primaryKey": true, "required": true },
					"title": { "type": "string", "matching": {} }
				}
			}
			""";
	}

	@Test
	@Order(1)
	void theSourceAndTheTargetAreCreated() {
		asRoot()
			.body(definition())
			.when().put("/v1alpha1/admin/indexes/books")
			.then().statusCode(201);

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
			.body(definition())
			.when().put("/v1alpha1/admin/indexes/books@2")
			.then().statusCode(201);

		readingBooks = keyGranting("\"indexes.read\"");
		reindexingBooks = keyGranting("\"indexes.read\", \"indexes.reindex\"");
	}

	/**
	 * The name a start names is the generation to fill, which is why starting
	 * stayed an action on the index rather than moving to the job.
	 */
	@Test
	@Order(2)
	void aJobStartsOnTheGenerationItFills() {
		as(reindexingBooks)
			.body("{ \"promote\": \"manual\" }")
			.when().post("/v1alpha1/admin/indexes/books@2/actions/reindex")
			.then()
			.statusCode(202)
			.body("index", is("books"))
			.body("target", is("books@2"));
	}

	@Test
	@Order(3)
	void theRecordAnswersUnderTheIndexItBelongsTo() {
		as(readingBooks)
			.when().get("/v1alpha1/admin/reindexes/books")
			.then()
			.statusCode(200)
			.body("index", is("books"))
			.body("target", is("books@2"));
	}

	/**
	 * The job belongs to the index however the caller names it, so a
	 * generation of the index answers with the same record.
	 */
	@Test
	@Order(4)
	void aGenerationOfTheIndexAnswersTheSameRecord() {
		as(readingBooks)
			.when().get("/v1alpha1/admin/reindexes/books@2")
			.then()
			.statusCode(200)
			.body("index", is("books"));
	}

	@Test
	@Order(5)
	void theListingNamesTheJob() {
		asRoot()
			.when().get("/v1alpha1/admin/reindexes")
			.then()
			.statusCode(200)
			.body("reindexes.index", hasItem("books"));
	}

	/**
	 * The name in the path is what the permission is checked against. A key
	 * granted `books` alone is answered as though another index were not
	 * there, rather than being told the index has no job.
	 */
	@Test
	@Order(6)
	void aRecordOutsideTheGrantIsNotFound() {
		as(readingBooks)
			.when().get("/v1alpha1/admin/reindexes/shops")
			.then()
			.statusCode(404)
			.body("code", is("index:not_found"));
	}

	@Test
	@Order(7)
	void cancellingNeedsTheReindexPermission() {
		as(readingBooks)
			.when().post("/v1alpha1/admin/reindexes/books/actions/cancel")
			.then().statusCode(403);
	}

	@Test
	@Order(8)
	void cancellingAnswersTheClosedRecord() {
		as(reindexingBooks)
			.when().post("/v1alpha1/admin/reindexes/books/actions/cancel")
			.then()
			.statusCode(200)
			.body("phase", is("cancelled"));
	}

	/**
	 * The record stays readable after a cancel, which is why cancelling is an
	 * action rather than a removal of the job.
	 */
	@Test
	@Order(9)
	void theCancelledRecordIsStillRead() {
		as(readingBooks)
			.when().get("/v1alpha1/admin/reindexes/books")
			.then()
			.statusCode(200)
			.body("phase", is("cancelled"));
	}

	/**
	 * The paths the job record answered at before it became a resource of its
	 * own are gone rather than kept alongside.
	 */
	@Test
	@Order(10)
	void theOldPathsAnswerNothing() {
		asRoot()
			.when().get("/v1alpha1/admin/indexes/books/actions/reindex")
			.then().statusCode(anyOf(is(404), is(405)));

		asRoot()
			.when().post("/v1alpha1/admin/indexes/books/actions/reindex/cancel")
			.then().statusCode(anyOf(is(404), is(405)));
	}
}
