package se.l4.exofind.engine.api.routing;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import com.sun.net.httpserver.HttpServer;

import se.l4.exofind.engine.index.registry.GenerationEntry;
import se.l4.exofind.engine.index.registry.IndexEntry;
import se.l4.exofind.engine.index.registry.IndexRegistryStore;
import se.l4.exofind.engine.index.state.IndexerLeadership;
import se.l4.exofind.engine.index.state.TestObjectStorage;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * A node that is not the indexer passing writes along to it, tested through
 * the whole HTTP stack: requests arrive over HTTP at a real node running
 * against a real object storage, and what it forwards arrives over HTTP at a
 * server standing in for the indexer.
 *
 * <p>The stand-in records what reaches it and answers whatever a test tells
 * it to, which is what lets the tests say exactly what a forwarded request
 * has to carry - the caller's own credential, the body untouched, the mark
 * that stops a second hop - and exactly what a caller gets back. A second
 * real node would serve the requests instead of showing them.
 *
 * <p>Which node writes which index comes from the leadership table, written
 * here the way real candidates write it. Answers about the table are cached
 * for a few seconds on the node, so the tests that change it wait for the
 * node to notice rather than expecting it at once.
 */
@QuarkusTest
@TestProfile(ForwardedWriteTest.NonIndexingNode.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ForwardedWriteTest {
	/**
	 * A node sharing the test storage that may never index, so every request
	 * only the indexer serves has to be passed along or refused.
	 */
	public static class NonIndexingNode implements QuarkusTestProfile {
		static final String ROOT_KEY = "exok_root_forward_test";

		/**
		 * Prefix of these tests, keeping their lease apart from the other
		 * tests sharing the bucket. A constant rather than something random:
		 * the storage container lives for one JVM, so there is no earlier run
		 * to collide with - and this class is loaded once more in the
		 * classloader Quarkus builds the application in, where a random value
		 * would come out different.
		 */
		static final String PREFIX = "forward-write-test";

		@Override
		public Map<String, String> getConfigOverrides() {
			Path directory;
			try {
				directory = Files.createTempDirectory("exofind-forward-write-test");
			} catch(IOException e) {
				throw new IllegalStateException("Could not make a directory to run in", e);
			}

			return Map.ofEntries(
				Map.entry("exofind.storage.mode", "object"),
				Map.entry("exofind.storage.local.directory", directory.toString()),
				Map.entry("exofind.storage.remote.url", TestObjectStorage.url()),
				Map.entry("exofind.storage.remote.access-key", TestObjectStorage.ACCESS_KEY),
				Map.entry("exofind.storage.remote.secret-key", TestObjectStorage.SECRET_KEY),
				Map.entry("exofind.storage.remote.bucket", TestObjectStorage.BUCKET),
				Map.entry("exofind.storage.remote.prefix", PREFIX),
				Map.entry("exofind.indexer.enabled", "false"),
				Map.entry("exofind.auth.mode", "keys"),
				Map.entry("exofind.auth.root-key", ROOT_KEY),
				// A lease that cannot be read only says so at debug level
				Map.entry(
					"quarkus.log.category.\"se.l4.exofind.engine.index.state\".level",
					"DEBUG"
				)
			);
		}
	}

	/**
	 * One request as it arrived at the stand-in.
	 */
	record Received(
		String method,
		URI uri,
		Map<String, List<String>> headers,
		byte[] body
	) {
		/**
		 * One header as it arrived, looked up the way header names compare -
		 * the server holding them normalizes their case.
		 */
		String header(String name) {
			for(var header : headers.entrySet()) {
				if(header.getKey().equalsIgnoreCase(name)) {
					return header.getValue().get(0);
				}
			}

			return null;
		}
	}

	/**
	 * What the stand-in answers with, until a test says otherwise.
	 */
	record Answer(int status, Map<String, String> headers, byte[] body) {
		static Answer json(int status, String body) {
			return new Answer(
				status,
				Map.of("Content-Type", "application/json"),
				body.getBytes(StandardCharsets.UTF_8)
			);
		}
	}

	private static HttpServer indexer;
	private static final ConcurrentLinkedQueue<Received> received = new ConcurrentLinkedQueue<>();
	private static volatile Answer answer = Answer.json(200, "{}");

	/**
	 * Register the indexes the tests write to. A write for an index the
	 * deployment does not hold is served where it lands for its 404 rather
	 * than forwarded, so forwarding is only observable for indexes that
	 * exist.
	 */
	@BeforeAll
	static void registerIndexes() {
		var registry = IndexRegistryStore.newBuilder();
		for(var name : List.of("books", "games", "fresh")) {
			registry.addIndexes(
				IndexEntry.newBuilder()
					.setName(name)
					.setLive("1")
					.addGenerations(GenerationEntry.newBuilder().setName("1"))
			);
		}

		TestObjectStorage.client().putObject(
			PutObjectRequest.builder()
				.bucket(TestObjectStorage.BUCKET)
				.key(NonIndexingNode.PREFIX + "/registry/indexes.ef.bin")
				.contentType("application/octet-stream")
				.build(),
			RequestBody.fromBytes(registry.build().toByteArray())
		);
	}

	@BeforeAll
	static void startIndexerStandIn() throws IOException {
		indexer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		indexer.createContext("/", exchange -> {
			received.add(new Received(
				exchange.getRequestMethod(),
				exchange.getRequestURI(),
				Map.copyOf(exchange.getRequestHeaders()),
				exchange.getRequestBody().readAllBytes()
			));

			var current = answer;
			for(var header : current.headers().entrySet()) {
				exchange.getResponseHeaders().add(header.getKey(), header.getValue());
			}

			if(current.body().length == 0) {
				exchange.sendResponseHeaders(current.status(), -1);
			} else {
				exchange.sendResponseHeaders(current.status(), current.body().length);
				exchange.getResponseBody().write(current.body());
			}

			exchange.close();
		});
		indexer.start();
	}

	@AfterAll
	static void stopIndexerStandIn() {
		indexer.stop(0);
	}

	@BeforeEach
	void reset() {
		received.clear();
		answer = Answer.json(200, "{}");
	}

	private static String indexerAddress() {
		return "http://127.0.0.1:" + indexer.getAddress().getPort();
	}

	/**
	 * Write the leadership table the way real candidates write it. Claims are
	 * given as pairs of index name and address, each held by a node named for
	 * its address, alive for long enough that the tests never race an expiry.
	 */
	private static void writeLeadership(
		Map<String, String> claims,
		Map<String, String> candidates
	) {
		var alive = System.currentTimeMillis() + Duration.ofMinutes(10).toMillis();
		var table = IndexerLeadership.newBuilder();

		for(var claim : claims.entrySet()) {
			table.addClaims(
				IndexerLeadership.Claim.newBuilder()
					.setIndex(claim.getKey())
					.setNode("stand-in-" + claim.getValue())
					.setAddress(claim.getValue())
					.setExpiresAt(alive)
			);
		}

		for(var candidate : candidates.entrySet()) {
			table.addCandidates(
				IndexerLeadership.Candidate.newBuilder()
					.setNode(candidate.getKey())
					.setAddress(candidate.getValue())
					.setExpiresAt(alive)
			);
		}

		TestObjectStorage.client().putObject(
			PutObjectRequest.builder()
				.bucket(TestObjectStorage.BUCKET)
				.key(NonIndexingNode.PREFIX + "/indexer-leadership.ef.bin")
				.contentType("application/octet-stream")
				.build(),
			RequestBody.fromBytes(table.build().toByteArray())
		);
	}

	/**
	 * The common case of the tests: the stand-in holds {@code books}.
	 */
	private static void claimBooksAt(String address) {
		writeLeadership(Map.of("books", address), Map.of());
	}

	private static io.restassured.specification.RequestSpecification asRoot() {
		return given().header("Authorization", "Bearer " + NonIndexingNode.ROOT_KEY);
	}

	/**
	 * Repeat a request until the node answers something other than the given
	 * status, for the tests that change the table and have to wait out the
	 * node's cached answer about it.
	 */
	private static Response awaitOtherThan(int status, Supplier<Response> request) {
		var deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();

		while(true) {
			var response = request.get();
			if(response.statusCode() != status || System.nanoTime() > deadline) {
				return response;
			}

			try {
				Thread.sleep(250);
			} catch(InterruptedException e) {
				Thread.currentThread().interrupt();
				return response;
			}
		}
	}

	/**
	 * Repeat a request until the node answers with the given status, for the
	 * tests where the stale answer could be more than one thing.
	 */
	private static Response awaitStatus(int status, Supplier<Response> request) {
		var deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();

		while(true) {
			var response = request.get();
			if(response.statusCode() == status || System.nanoTime() > deadline) {
				return response;
			}

			try {
				Thread.sleep(250);
			} catch(InterruptedException e) {
				Thread.currentThread().interrupt();
				return response;
			}
		}
	}

	@Test
	@Order(1)
	void aWriteWithNoIndexerToPassItToIsRefused() {
		asRoot()
			.contentType("application/json")
			.body("{\"documents\": []}")
			.when().post("/v1alpha1/indexes/books/documents")
			.then()
			.statusCode(409)
			.body("code", is("indexer:unavailable"));

		assertThat(received.poll(), is(nullValue()));
	}

	/**
	 * A search is served wherever it lands, indexer or not - reaching the
	 * resource is what answers 404 for an index that does not exist, where a
	 * request that waited for an indexer would have been refused with 409.
	 */
	@Test
	@Order(2)
	void aSearchIsServedWhereItLands() {
		asRoot()
			.contentType("application/json")
			.body("{}")
			.when().post("/v1alpha1/indexes/missing/search")
			.then().statusCode(404);
	}

	@Test
	@Order(3)
	void aWriteIsPassedAlongWholeAndAnsweredWithWhatTheIndexerSaid() {
		claimBooksAt(indexerAddress());
		answer = Answer.json(200, "{\"indexed\": 1}");

		var body = "{\"documents\": [{\"id\": \"1\", \"title\": \"Silent Spring\"}]}";

		var response = awaitOtherThan(409, () ->
			asRoot()
				.contentType("application/json")
				.body(body)
				.when().post("/v1alpha1/indexes/books/documents")
		);

		response.then()
			.statusCode(200)
			.contentType("application/json")
			.body("indexed", is(1));

		var forwarded = received.poll();
		assertThat(forwarded, is(notNullValue()));
		assertThat(forwarded.method(), is("POST"));
		assertThat(forwarded.uri().getPath(), is("/v1alpha1/indexes/books/documents"));
		assertThat(new String(forwarded.body(), StandardCharsets.UTF_8), is(body));

		// The caller's own credential, so the indexer refuses what this node would have
		assertThat(
			forwarded.header("Authorization"),
			is("Bearer " + NonIndexingNode.ROOT_KEY)
		);
		assertThat(forwarded.header("Content-Type"), is("application/json"));

		// The mark that stops the request from being passed along a second time
		assertThat(forwarded.header(IndexerForwardFilter.FORWARDED_HEADER), is("true"));
	}

	@Test
	@Order(4)
	void theQueryStringTravelsWithTheRequest() {
		asRoot()
			.contentType("application/json")
			.body("{\"documents\": [{\"id\": \"1\"}]}")
			.when().post("/v1alpha1/indexes/books/documents/actions/update?missing=skip")
			.then().statusCode(200);

		var forwarded = received.poll();
		assertThat(forwarded, is(notNullValue()));
		assertThat(forwarded.uri().getQuery(), is("missing=skip"));
	}

	/**
	 * A dataset arrives as newline delimited JSON precisely because it can be
	 * large, so it has to pass through rather than being read whole - and has
	 * to arrive byte for byte as it was sent.
	 */
	@Test
	@Order(5)
	void aStreamedDatasetPassesThroughUntouched() {
		var line = "{\"id\": \"%d\", \"title\": \"A title that pads the line out to something\"}";
		var body = new StringBuilder();
		for(var i = 0; i < 5_000; i++) {
			body.append(line.formatted(i)).append('\n');
		}
		var bytes = body.toString().getBytes(StandardCharsets.UTF_8);

		asRoot()
			.contentType("application/x-ndjson")
			.body(bytes)
			.when().post("/v1alpha1/indexes/books/documents")
			.then().statusCode(200);

		var forwarded = received.poll();
		assertThat(forwarded, is(notNullValue()));

		// The client under test appends a charset of its own to what it sends
		assertThat(forwarded.header("Content-Type"), startsWith("application/x-ndjson"));
		assertThat(java.util.Arrays.equals(forwarded.body(), bytes), is(true));
	}

	/**
	 * The conditional headers are how a caller avoids overwriting someone
	 * else's change, so they have to reach the node that actually decides -
	 * and the Location the indexer answers with points at itself, which is
	 * pointed back at the host the caller was talking to.
	 */
	@Test
	@Order(6)
	void definitionsForwardConditionsAndComeBackWithALocalLocation() {
		answer = new Answer(
			201,
			Map.of(
				"Content-Type", "application/json",
				"ETag", "\"1\"",
				"Location", indexerAddress() + "/v1alpha1/admin/indexes/books"
			),
			"{}".getBytes(StandardCharsets.UTF_8)
		);

		var response = asRoot()
			.contentType("application/json")
			.header("If-Match", "\"0\"")
			.body("{\"fields\": {}}")
			.when().put("/v1alpha1/admin/indexes/books");

		response.then()
			.statusCode(201)
			.header("ETag", "\"1\"")
			.header(
				"Location",
				"http://localhost:" + RestAssured.port + "/v1alpha1/admin/indexes/books"
			);

		var forwarded = received.poll();
		assertThat(forwarded, is(notNullValue()));
		assertThat(forwarded.method(), is("PUT"));
		assertThat(forwarded.header("If-Match"), is("\"0\""));
	}

	@Test
	@Order(7)
	void aDeleteForwardsWithoutABodyAndRelaysAnEmptyAnswer() {
		answer = new Answer(204, Map.of(), new byte[0]);

		asRoot()
			.when().delete("/v1alpha1/indexes/books/documents/1")
			.then().statusCode(204);

		var forwarded = received.poll();
		assertThat(forwarded, is(notNullValue()));
		assertThat(forwarded.method(), is("DELETE"));
		assertThat(forwarded.body().length, is(0));
	}

	/**
	 * What the indexer refuses arrives refused as it said it, not wrapped in
	 * an answer of this node's own - the caller is talking to the indexer,
	 * however many nodes stand in between.
	 */
	@Test
	@Order(8)
	void whatTheIndexerAnsweredIsRelayedRefusalsIncluded() {
		answer = Answer.json(409, "{\"code\": \"index:version_mismatch\"}");

		asRoot()
			.contentType("application/json")
			.body("{\"documents\": []}")
			.when().post("/v1alpha1/indexes/books/documents")
			.then()
			.statusCode(409)
			.body("code", is("index:version_mismatch"));
	}

	/**
	 * Answers about who the indexer is lag, so two nodes that both think the
	 * other one is it could pass a request between each other until one of
	 * them noticed. One hop is allowed; a marked request that still has not
	 * found the indexer is refused instead.
	 */
	@Test
	@Order(9)
	void aRequestAlreadyPassedAlongOnceIsRefusedRatherThanPassedAgain() {
		asRoot()
			.contentType("application/json")
			.header(IndexerForwardFilter.FORWARDED_HEADER, "true")
			.body("{\"documents\": []}")
			.when().post("/v1alpha1/indexes/books/documents")
			.then()
			.statusCode(409)
			.body("code", is("indexer:unavailable"));

		assertThat(received.poll(), is(nullValue()));
	}

	/**
	 * Leadership is per index: writes to two indexes held by two different
	 * nodes each reach their own holder.
	 */
	@Test
	@Order(10)
	void writesToDifferentIndexesReachDifferentHolders() throws Exception {
		var second = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		var secondReceived = new ConcurrentLinkedQueue<Received>();
		second.createContext("/", exchange -> {
			secondReceived.add(new Received(
				exchange.getRequestMethod(),
				exchange.getRequestURI(),
				Map.copyOf(exchange.getRequestHeaders()),
				exchange.getRequestBody().readAllBytes()
			));

			var body = "{}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		second.start();

		try {
			var secondAddress = "http://127.0.0.1:" + second.getAddress().getPort();
			writeLeadership(
				Map.of(
					"books", indexerAddress(),
					"games", secondAddress
				),
				Map.of()
			);

			awaitOtherThan(409, () ->
				asRoot()
					.contentType("application/json")
					.body("{\"documents\": []}")
					.when().post("/v1alpha1/indexes/games/documents")
			).then().statusCode(200);

			var toSecond = secondReceived.poll();
			assertThat(toSecond, is(notNullValue()));
			assertThat(toSecond.uri().getPath(), is("/v1alpha1/indexes/games/documents"));
			assertThat(received.poll(), is(nullValue()));

			asRoot()
				.contentType("application/json")
				.body("{\"documents\": []}")
				.when().post("/v1alpha1/indexes/books/documents")
				.then().statusCode(200);

			var toFirst = received.poll();
			assertThat(toFirst, is(notNullValue()));
			assertThat(toFirst.uri().getPath(), is("/v1alpha1/indexes/books/documents"));
			assertThat(secondReceived.poll(), is(nullValue()));
		} finally {
			second.stop(0);
		}
	}

	/**
	 * An index nothing holds is sent to a live candidate, which is what makes
	 * the first write to a brand-new index find a node to appoint - this node
	 * does not compete, so someone that does has to be found.
	 */
	@Test
	@Order(11)
	void aWriteToAnIndexNothingHoldsIsSentToACandidate() {
		writeLeadership(Map.of(), Map.of("stand-in", indexerAddress()));

		awaitOtherThan(409, () ->
			asRoot()
				.contentType("application/json")
				.body("{\"documents\": []}")
				.when().post("/v1alpha1/indexes/fresh/documents")
		).then().statusCode(200);

		var forwarded = received.poll();
		assertThat(forwarded, is(notNullValue()));
		assertThat(forwarded.uri().getPath(), is("/v1alpha1/indexes/fresh/documents"));
	}

	/**
	 * A write naming an index the deployment does not hold is served where it
	 * lands for its 404, rather than being forwarded toward a writer that
	 * would answer the same - existence is answered by the registry, not by
	 * finding out the long way.
	 */
	@Test
	@Order(12)
	void aWriteToAnIndexTheDeploymentDoesNotHoldIsAnsweredWhereItLands() {
		writeLeadership(Map.of(), Map.of("stand-in", indexerAddress()));

		awaitStatus(404, () ->
			asRoot()
				.contentType("application/json")
				.body("{\"documents\": []}")
				.when().post("/v1alpha1/indexes/unregistered/documents")
		).then().statusCode(404);

		assertThat(received.poll(), is(nullValue()));
	}

	@Test
	@Order(13)
	void anIndexerThatDoesNotAnswerIsABadGateway() throws IOException {
		int deadPort;
		try(var socket = new ServerSocket(0)) {
			deadPort = socket.getLocalPort();
		}

		claimBooksAt("http://127.0.0.1:" + deadPort);

		var response = awaitStatus(502, () ->
			asRoot()
				.contentType("application/json")
				.body("{\"documents\": []}")
				.when().post("/v1alpha1/indexes/books/documents")
		);

		response.then()
			.statusCode(502)
			.body("code", is("indexer:unreachable"));
	}
}
