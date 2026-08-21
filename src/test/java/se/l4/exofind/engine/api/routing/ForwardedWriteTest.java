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

import se.l4.exofind.engine.index.state.IndexerLease;
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
 * <p>Who the indexer is comes from the lease object, written here the way a
 * real indexer writes it. Answers about the lease are cached for a few
 * seconds on the node, so the tests that change it wait for the node to
 * notice rather than expecting it at once.
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
			return Map.ofEntries(
				Map.entry("exofind.storage.mode", "object"),
				Map.entry("remote.storage.url", TestObjectStorage.url()),
				Map.entry("remote.storage.access-key", TestObjectStorage.ACCESS_KEY),
				Map.entry("remote.storage.secret-key", TestObjectStorage.SECRET_KEY),
				Map.entry("remote.storage.bucket", TestObjectStorage.BUCKET),
				Map.entry("remote.storage.prefix", PREFIX),
				Map.entry("indexer", "false"),
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
	 * Write the lease the way an indexer holding the role writes it, naming
	 * the given address as where writes are served.
	 */
	private static void writeLease(String address) {
		var lease = IndexerLease.newBuilder()
			.setNode("stand-in")
			.setAddress(address)
			.setExpiresAt(System.currentTimeMillis() + Duration.ofMinutes(10).toMillis())
			.build();

		TestObjectStorage.client().putObject(
			PutObjectRequest.builder()
				.bucket(TestObjectStorage.BUCKET)
				.key(NonIndexingNode.PREFIX + "/indexer-lease.ef.bin")
				.contentType("application/octet-stream")
				.build(),
			RequestBody.fromBytes(lease.toByteArray())
		);
	}

	private static io.restassured.specification.RequestSpecification asRoot() {
		return given().header("Authorization", "Bearer " + NonIndexingNode.ROOT_KEY);
	}

	/**
	 * Repeat a request until the node answers something other than the given
	 * status, for the tests that change the lease and have to wait out the
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
			.when().post("/v1alpha1/indexes/books/search")
			.then().statusCode(404);
	}

	@Test
	@Order(3)
	void aWriteIsPassedAlongWholeAndAnsweredWithWhatTheIndexerSaid() {
		writeLease(indexerAddress());
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

	@Test
	@Order(10)
	void anIndexerThatDoesNotAnswerIsABadGateway() throws IOException {
		int deadPort;
		try(var socket = new ServerSocket(0)) {
			deadPort = socket.getLocalPort();
		}

		writeLease("http://127.0.0.1:" + deadPort);

		var response = awaitOtherThan(200, () ->
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
