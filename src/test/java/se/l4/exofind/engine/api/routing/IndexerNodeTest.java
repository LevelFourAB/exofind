package se.l4.exofind.engine.api.routing;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import com.sun.net.httpserver.HttpServer;

import se.l4.exofind.engine.index.state.IndexerLeadership;
import se.l4.exofind.engine.index.state.TestObjectStorage;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * A candidate node running against a real object storage: what it claims,
 * what it serves, and what it passes along. The other side of
 * {@link ForwardedWriteTest}, which runs a node that never competes.
 *
 * <p>The interesting moment is creation: an index that does not exist yet has
 * no holder, so the create finding this node is what appoints it - the claim
 * has to be in the table by the time the create is answered, not a renewal
 * round later.
 */
@QuarkusTest
@TestProfile(IndexerNodeTest.CandidateNode.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class IndexerNodeTest {
	/**
	 * A candidate sharing the test storage, competing under a fixed name so
	 * the tests can recognize its claims in the table.
	 */
	public static class CandidateNode implements QuarkusTestProfile {
		static final String ROOT_KEY = "exok_root_indexer_node_test";
		static final String NODE = "under-test";
		static final String PREFIX = "indexer-node-test";

		@Override
		public Map<String, String> getConfigOverrides() {
			return Map.ofEntries(
				Map.entry("exofind.storage.mode", "object"),
				Map.entry("remote.storage.url", TestObjectStorage.url()),
				Map.entry("remote.storage.access-key", TestObjectStorage.ACCESS_KEY),
				Map.entry("remote.storage.secret-key", TestObjectStorage.SECRET_KEY),
				Map.entry("remote.storage.bucket", TestObjectStorage.BUCKET),
				Map.entry("remote.storage.prefix", PREFIX),
				Map.entry("indexer", "true"),
				Map.entry("node.id", NODE),
				Map.entry("node.address", "http://localhost:8081"),
				// Fast rounds, so a test never waits long for one
				Map.entry("indexer.lease.duration", "2s"),
				Map.entry("exofind.auth.mode", "keys"),
				Map.entry("exofind.auth.root-key", ROOT_KEY)
			);
		}
	}

	record Received(String method, URI uri, Map<String, List<String>> headers) {
		String header(String name) {
			for(var header : headers.entrySet()) {
				if(header.getKey().equalsIgnoreCase(name)) {
					return header.getValue().get(0);
				}
			}

			return null;
		}
	}

	private static HttpServer holder;
	private static final ConcurrentLinkedQueue<Received> received = new ConcurrentLinkedQueue<>();

	@BeforeAll
	static void startHolderStandIn() throws IOException {
		holder = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		holder.createContext("/", exchange -> {
			exchange.getRequestBody().readAllBytes();
			received.add(new Received(
				exchange.getRequestMethod(),
				exchange.getRequestURI(),
				Map.copyOf(exchange.getRequestHeaders())
			));

			var body = "{}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		holder.start();
	}

	@AfterAll
	static void stopHolderStandIn() {
		holder.stop(0);
	}

	private static io.restassured.specification.RequestSpecification asRoot() {
		return given()
			.header("Authorization", "Bearer " + CandidateNode.ROOT_KEY)
			.contentType(ContentType.JSON);
	}

	private static String tableKey() {
		return CandidateNode.PREFIX + "/indexer-leadership.ef.bin";
	}

	private static IndexerLeadership readTable() throws IOException {
		try(
			var response = TestObjectStorage.client().getObject(
				GetObjectRequest.builder()
					.bucket(TestObjectStorage.BUCKET)
					.key(tableKey())
					.build()
			)
		) {
			return IndexerLeadership.parseFrom(response.readAllBytes());
		}
	}

	/**
	 * The node named as holding an index in the table right now, or
	 * {@code null} when nothing holds it.
	 */
	private static String holderOf(String index) throws IOException {
		for(var claim : readTable().getClaimsList()) {
			if(claim.getIndex().equals(index)) {
				return claim.getNode();
			}
		}

		return null;
	}

	@Test
	@Order(1)
	void theCreateAppointsThisNode() throws Exception {
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

		// Claimed by serving the create, not by a renewal round later
		assertThat(holderOf("books"), is(CandidateNode.NODE));
	}

	@Test
	@Order(2)
	void theClaimedIndexTakesWritesAndAnswersSearches() {
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
	 * A candidate is not the holder of everything: an index another node
	 * holds alive is forwarded to it, not claimed from it.
	 */
	@Test
	@Order(3)
	void anIndexHeldElsewhereIsForwardedNotTaken() throws Exception {
		var address = "http://127.0.0.1:" + holder.getAddress().getPort();
		var alive = System.currentTimeMillis() + Duration.ofMinutes(10).toMillis();

		/*
		 * Added on top of the current table rather than replacing it, so the
		 * node's own claims survive - the node itself only ever writes
		 * conditionally, but this test does not compete for the object.
		 */
		var table = readTable().toBuilder()
			.addClaims(
				IndexerLeadership.Claim.newBuilder()
					.setIndex("foreign")
					.setNode("someone-else")
					.setAddress(address)
					.setExpiresAt(alive)
			)
			.build();

		TestObjectStorage.client().putObject(
			PutObjectRequest.builder()
				.bucket(TestObjectStorage.BUCKET)
				.key(tableKey())
				.contentType("application/octet-stream")
				.build(),
			RequestBody.fromBytes(table.toByteArray())
		);

		/*
		 * The node answers from its cached table, refreshed by its own
		 * rounds, so the foreign claim takes a round or two to be seen.
		 */
		var deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
		while(true) {
			var response = asRoot()
				.body("{\"documents\": []}")
				.when().post("/v1alpha1/indexes/foreign/documents");

			if(response.statusCode() == 200 || System.nanoTime() > deadline) {
				response.then().statusCode(200);
				break;
			}

			Thread.sleep(250);
		}

		var forwarded = received.poll();
		assertThat(forwarded, is(notNullValue()));
		assertThat(forwarded.uri().getPath(), is("/v1alpha1/indexes/foreign/documents"));
		assertThat(forwarded.header(IndexerForwardFilter.FORWARDED_HEADER), is("true"));

		// Still held by the other node, not taken by this one
		assertThat(holderOf("foreign"), is("someone-else"));
	}

	/**
	 * A write naming an index the deployment does not hold is answered with
	 * the endpoint's 404 and appoints nothing - a retried typo must not write
	 * claims into the table over and over.
	 */
	@Test
	@Order(4)
	void aWriteToAnIndexTheDeploymentDoesNotHoldClaimsNothing() throws Exception {
		asRoot()
			.body("{\"documents\": []}")
			.when().post("/v1alpha1/indexes/nowhere/documents")
			.then().statusCode(404);

		assertThat(holderOf("nowhere"), is(nullValue()));
	}
}
