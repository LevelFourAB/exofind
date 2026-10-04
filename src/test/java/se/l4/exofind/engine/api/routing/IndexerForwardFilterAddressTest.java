package se.l4.exofind.engine.api.routing;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.jboss.logmanager.ExtLogRecord;
import org.jboss.logmanager.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.index.registry.RegisteredIndex;
import se.l4.exofind.engine.index.state.IndexerOwnership;
import se.l4.exofind.engine.index.state.IndexerUnavailableException;
import se.l4.exofind.engine.metrics.RequestMetrics;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * How the forwarding filter reads the address of the writer and builds the
 * request it sends there. An address that cannot be forwarded to refuses
 * every write, so the reason has to reach the log of the node that refused.
 */
public class IndexerForwardFilterAddressTest {
	static class Endpoints {
		@ServedBy(ServedBy.Node.INDEXER)
		public void writesAnIndex() {
		}
	}

	HttpServer indexer;
	ConcurrentLinkedQueue<URI> received;

	NodeState nodeState;
	IndexerOwnership ownership;
	Indexes indexes;
	IndexerForwardFilter filter;

	org.jboss.logmanager.Logger backing;
	Capture capture;

	@BeforeEach
	void setup() throws IOException {
		received = new ConcurrentLinkedQueue<>();

		indexer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		indexer.createContext("/", exchange -> {
			received.add(exchange.getRequestURI());
			exchange.getRequestBody().readAllBytes();
			exchange.sendResponseHeaders(204, -1);
			exchange.close();
		});
		indexer.start();

		nodeState = mock(NodeState.class);
		ownership = mock(IndexerOwnership.class);
		indexes = mock(Indexes.class);

		when(indexes.getRegistered("books")).thenReturn(Optional.of(
			new RegisteredIndex(
				"books",
				Lists.immutable.of(new RegisteredIndex.Generation("1", null, null)),
				"1",
				null,
				Sets.immutable.empty(),
				null
			)
		));

		filter = new IndexerForwardFilter(nodeState, ownership, indexes, RequestMetrics.none());

		backing = org.jboss.logmanager.Logger.getLogger(IndexerForwardFilter.class.getName());
		backing.setLevel(Level.INFO);
		capture = new Capture();
		backing.addHandler(capture);
	}

	@AfterEach
	void cleanup() {
		backing.removeHandler(capture);
		indexer.stop(0);
	}

	private static Method endpoint(String name) {
		try {
			return Endpoints.class.getMethod(name);
		} catch(NoSuchMethodException e) {
			throw new IllegalStateException(e);
		}
	}

	private Response run(String method, URI requestUri) throws IOException {
		var resourceInfo = mock(ResourceInfo.class);
		when(resourceInfo.getResourceMethod()).thenReturn(endpoint("writesAnIndex"));
		filter.resourceInfo = resourceInfo;

		var parameters = new MultivaluedHashMap<String, String>();
		parameters.putSingle(ServedBy.INDEX_PARAMETER, "books");

		var uriInfo = mock(UriInfo.class);
		when(uriInfo.getPathParameters()).thenReturn(parameters);
		when(uriInfo.getRequestUri()).thenReturn(requestUri);

		var request = mock(ContainerRequestContext.class);
		when(request.getMethod()).thenReturn(method);
		when(request.getUriInfo()).thenReturn(uriInfo);
		when(request.getHeaders()).thenReturn(new MultivaluedHashMap<>());
		when(request.getEntityStream()).thenReturn(new ByteArrayInputStream(new byte[0]));

		var answered = new Response[1];
		doAnswer(invocation -> {
			answered[0] = invocation.getArgument(0);
			return null;
		}).when(request).abortWith(any());

		filter.filter(request);
		return answered[0];
	}

	/**
	 * The Kubernetes guide says a node logs "Indexer address cannot be
	 * forwarded to" when EXOFIND_NODE_ADDRESS cannot be used. An address
	 * without a scheme, such as {@code exofind-0:8080}, parses as a URI with
	 * no host. Every write is then refused with 409 and the operator must
	 * still see why.
	 */
	@Test
	void anAddressWithoutASchemeIsReportedInTheLog() {
		// Given the writer recorded an address without a scheme
		when(ownership.hasHolder("books")).thenReturn(true);
		when(ownership.indexerAddress("books")).thenReturn(Optional.of("exofind-0:8080"));

		// When a write arrives at a node that does not write the index
		assertThrows(
			IndexerUnavailableException.class,
			() -> run("POST", URI.create("http://localhost:8080/v1alpha1/indexes/books/documents"))
		);

		// Then the node logs that the address cannot be forwarded to
		assertThat(capture.messages(), hasItem(
			containsString("Indexer address cannot be forwarded to")
		));
	}

	/**
	 * An address that does not parse as a URI at all, such as an IP address
	 * without a scheme.
	 */
	@Test
	void anAddressThatDoesNotParseIsReportedInTheLog() {
		// Given the writer recorded an address that is not a URI
		when(ownership.hasHolder("books")).thenReturn(true);
		when(ownership.indexerAddress("books")).thenReturn(Optional.of("10.0.0.5:8080"));

		// When a write arrives at a node that does not write the index
		assertThrows(
			IndexerUnavailableException.class,
			() -> run("POST", URI.create("http://localhost:8080/v1alpha1/indexes/books/documents"))
		);

		// Then the node logs that the address cannot be forwarded to
		assertThat(capture.messages(), hasItem(
			containsString("Indexer address cannot be forwarded to")
		));
	}

	/**
	 * A host name with an underscore, as some container networks give, also
	 * parses with no host. The operator must see why writes are refused.
	 */
	@Test
	void anAddressWithAnUnderscoreInTheHostIsReportedInTheLog() {
		// Given the writer recorded an address whose host holds an underscore
		when(ownership.hasHolder("books")).thenReturn(true);
		when(ownership.indexerAddress("books"))
			.thenReturn(Optional.of("http://exofind_indexer:8080"));

		// When a write arrives at a node that does not write the index
		assertThrows(
			IndexerUnavailableException.class,
			() -> run("POST", URI.create("http://localhost:8080/v1alpha1/indexes/books/documents"))
		);

		// Then the node logs that the address cannot be forwarded to
		assertThat(capture.messages(), hasItem(
			containsString("Indexer address cannot be forwarded to")
		));
	}

	/**
	 * A document key is a path segment. The writer must receive the same
	 * encoded path and query, or the write lands on another document.
	 */
	@Test
	void theForwardedRequestKeepsTheEncodedPathAndQuery() throws IOException {
		// Given the writer is another node at a usable address
		when(ownership.hasHolder("books")).thenReturn(true);
		when(ownership.indexerAddress("books"))
			.thenReturn(Optional.of("http://127.0.0.1:" + indexer.getAddress().getPort()));

		// When a write names a key that holds encoded characters
		var path = "/v1alpha1/indexes/books/documents/a%2Fb%20c%25d%7Bx%7D";
		var query = "refresh=wait&tag=a%26b";
		var response = run("DELETE", URI.create("http://localhost:8080" + path + "?" + query));

		// Then the writer receives the same raw path and query
		assertThat(response, is(notNullValue()));
		var forwarded = received.poll();
		assertThat(forwarded, is(notNullValue()));
		assertThat(forwarded.getRawPath(), is(path));
		assertThat(forwarded.getRawQuery(), is(query));
	}

	static class Capture extends Handler {
		final List<LogRecord> records = new CopyOnWriteArrayList<>();

		@Override
		public void publish(LogRecord record) {
			records.add(record);
		}

		List<String> messages() {
			return records.stream()
				.map(record -> record instanceof ExtLogRecord ext
					? ext.getFormattedMessage()
					: record.getMessage())
				.toList();
		}

		@Override
		public void flush() {
		}

		@Override
		public void close() {
		}
	}
}
