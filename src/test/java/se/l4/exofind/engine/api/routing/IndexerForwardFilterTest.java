package se.l4.exofind.engine.api.routing;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.index.registry.RegisteredIndex;
import se.l4.exofind.engine.index.state.IndexerOwnership;
import se.l4.exofind.engine.metrics.RequestMetrics;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * Tests for what the forwarding filter decides on its own: whether a request
 * carries a body to pass along, and whether a name it does not hold is worth
 * claiming.
 *
 * <p>The requests are built here instead of arriving over HTTP, because a
 * request framed the way HTTP/2 frames one - no {@code Content-Length} and no
 * {@code Transfer-Encoding} - cannot be sent over the HTTP/1.1 connection the
 * test client opens. What the filter passes along arrives at a stand-in server
 * over a real connection, so the body is checked as the indexer receives it.
 */
public class IndexerForwardFilterTest {
	/**
	 * Stands in for the resource methods, so the filter is tested against the
	 * annotation rather than against any one endpoint.
	 */
	static class Endpoints {
		@ServedBy(ServedBy.Node.INDEXER)
		public void writesAnIndex() {
		}

		@ServedBy(value = ServedBy.Node.INDEXER, creates = true)
		public void createsAnIndex() {
		}
	}

	private static final String INDEXED = "{\"indexed\": 1}";

	HttpServer indexer;
	ConcurrentLinkedQueue<byte[]> received;

	NodeState nodeState;
	IndexerOwnership ownership;
	Indexes indexes;
	IndexerForwardFilter filter;

	@BeforeEach
	void setup() throws IOException {
		received = new ConcurrentLinkedQueue<>();

		indexer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		indexer.createContext("/", exchange -> {
			received.add(exchange.getRequestBody().readAllBytes());

			var body = INDEXED.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		indexer.start();

		nodeState = mock(NodeState.class);
		ownership = mock(IndexerOwnership.class);
		indexes = mock(Indexes.class);

		filter = new IndexerForwardFilter(nodeState, ownership, indexes, RequestMetrics.none());
	}

	@AfterEach
	void cleanup() {
		indexer.stop(0);
	}

	private String indexerAddress() {
		return "http://127.0.0.1:" + indexer.getAddress().getPort();
	}

	private static Method endpoint(String name) {
		try {
			return Endpoints.class.getMethod(name);
		} catch(NoSuchMethodException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Register an index of one generation under a name, as a deployment that
	 * holds it answers.
	 */
	private void hold(String index) {
		when(indexes.getRegistered(index)).thenReturn(Optional.of(
			new RegisteredIndex(
				index,
				Lists.immutable.of(new RegisteredIndex.Generation("1", null, null)),
				"1",
				null,
				Sets.immutable.empty(),
				null
			)
		));
	}

	/**
	 * Build a request at one of the stand-in endpoints and run the filter over
	 * it.
	 *
	 * @param method
	 *   the HTTP method the request arrives with
	 * @param endpoint
	 *   name of the stand-in endpoint it matched
	 * @param name
	 *   what its {@code name} path parameter reads
	 * @param body
	 *   the body it carries, framed by no header at all, or {@code null} for a
	 *   request that carries none
	 * @return
	 *   the response the filter answered with, or {@code null} when it left the
	 *   request to be served here
	 */
	private Response run(String method, String endpoint, String name, String body)
		throws IOException {
		var resourceInfo = mock(ResourceInfo.class);
		when(resourceInfo.getResourceMethod()).thenReturn(endpoint(endpoint));
		filter.resourceInfo = resourceInfo;

		var parameters = new MultivaluedHashMap<String, String>();
		parameters.putSingle(ServedBy.INDEX_PARAMETER, name);

		var uriInfo = mock(UriInfo.class);
		when(uriInfo.getPathParameters()).thenReturn(parameters);
		when(uriInfo.getRequestUri())
			.thenReturn(URI.create("http://localhost:8080/v1alpha1/indexes/" + name + "/documents"));

		var request = mock(ContainerRequestContext.class);
		when(request.getMethod()).thenReturn(method);
		when(request.getUriInfo()).thenReturn(uriInfo);
		when(request.getHeaders()).thenReturn(new MultivaluedHashMap<>());
		when(request.getEntityStream()).thenReturn(
			new ByteArrayInputStream(
				body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8)
			)
		);

		var answered = new Response[1];
		doAnswer(invocation -> {
			answered[0] = invocation.getArgument(0);
			return null;
		}).when(request).abortWith(any());

		filter.filter(request);

		return answered[0];
	}

	/**
	 * HTTP/2 frames a body without either of the headers HTTP/1.1 frames one
	 * with. Reading only the headers took such a request for one carrying
	 * nothing, and the indexer answered as though the caller had sent no
	 * documents.
	 */
	@Test
	public void aBodyFramedByNoHeaderIsPassedAlong() throws IOException {
		hold("books");
		when(ownership.indexerAddress("books")).thenReturn(Optional.of(indexerAddress()));

		var documents = "{\"documents\": [{\"id\": \"1\"}]}";
		var response = run("POST", "writesAnIndex", "books", documents);

		assertThat(response, is(notNullValue()));
		assertThat(response.getStatus(), is(200));

		var forwarded = received.poll();
		assertThat(forwarded, is(notNullValue()));
		assertThat(new String(forwarded, StandardCharsets.UTF_8), is(documents));
	}

	/**
	 * A method that carries no body sends none, so the indexer is not left
	 * waiting for a stream that never arrives.
	 */
	@Test
	public void aDeleteWithNoBodyPassesAlongWithoutOne() throws IOException {
		hold("books");
		when(ownership.indexerAddress("books")).thenReturn(Optional.of(indexerAddress()));

		var response = run("DELETE", "writesAnIndex", "books", null);

		assertThat(response, is(notNullValue()));
		assertThat(received.poll().length, is(0));
	}

	/**
	 * An endpoint that creates the index it names still cannot create a
	 * generation of one the deployment does not hold, so claiming the name
	 * would contend for a writer of something that only answers 404.
	 */
	@Test
	public void aGenerationOfAnIndexTheDeploymentDoesNotHoldIsNotClaimed() throws IOException {
		when(indexes.getRegistered("books")).thenReturn(Optional.empty());
		when(ownership.hasHolder("books")).thenReturn(false);

		var response = run("PUT", "createsAnIndex", "books@2", "{\"fields\": {}}");

		// Served here, so the answer is the endpoint's own 404
		assertThat(response, is(nullValue()));
		verify(ownership, never()).tryClaim(anyString());
		assertThat(received.poll(), is(nullValue()));
	}

	/**
	 * Creating the index itself does claim the name, which is how the request
	 * that creates an index appoints the node that writes it.
	 */
	@Test
	public void creatingAnIndexTheDeploymentDoesNotHoldClaimsIt() throws IOException {
		when(indexes.getRegistered("books")).thenReturn(Optional.empty());
		when(ownership.tryClaim("books")).thenReturn(true);

		var response = run("PUT", "createsAnIndex", "books", "{\"fields\": {}}");

		assertThat(response, is(nullValue()));
		verify(indexes).reopenForWriting("books");
	}

	/**
	 * A generation of an index the deployment holds is claimed like any other
	 * write to it.
	 */
	@Test
	public void aGenerationOfAnIndexTheDeploymentHoldsIsClaimed() throws IOException {
		hold("books");
		when(ownership.tryClaim("books")).thenReturn(true);

		var response = run("PUT", "createsAnIndex", "books@2", "{\"fields\": {}}");

		assertThat(response, is(nullValue()));
		verify(indexes).reopenForWriting("books");
	}
}
