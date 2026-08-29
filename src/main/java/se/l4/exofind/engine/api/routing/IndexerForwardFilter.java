package se.l4.exofind.engine.api.routing;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.state.IndexerOwnership;
import se.l4.exofind.engine.index.state.IndexerUnavailableException;
import se.l4.exofind.engine.index.state.IndexerUnreachableException;
import se.l4.exofind.engine.logging.Log;
import se.l4.exofind.engine.metrics.Meters;
import se.l4.exofind.engine.metrics.RequestMetrics;
import jakarta.annotation.Priority;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;

/**
 * Passes requests only an index's writer serves along to it, so a caller can
 * send every request to any node without knowing which node writes which
 * index.
 *
 * <p>Where a request runs is declared on the endpoint with {@link ServedBy},
 * checked here in one place for the same reason permissions are: a mutating
 * endpoint that does not say is refused rather than served, because one that
 * forgot would quietly write on a node that should have passed the request
 * along. Which index a request is about is read from the {@code name} path
 * parameter, the way {@code AuthFilter} reads it. The request is forwarded
 * as it arrived - method, body and headers, the caller's own credential
 * included, so the node serving it decides for itself whether the caller may
 * make it and forwarding grants nothing.
 *
 * <p>An index nothing writes - just created, or its writer just died - is
 * claimed by this node on the spot when it competes for indexes, so the
 * write that found no writer is what appoints one. A node that does not
 * compete forwards such a write to a candidate that does. Only an index the
 * deployment holds appoints a writer this way; a write naming one it does
 * not - unless the endpoint says it may create it - is served where it
 * lands, so the answer is the endpoint's 404 rather than a claim on a name
 * that does not exist.
 *
 * <p>A forwarded request is marked, and one that arrives marked at a node
 * that still cannot serve it is refused instead of forwarded again. Answers
 * about who writes an index lag reality by a short while, so two nodes could
 * otherwise pass a request between each other until one of them noticed. One
 * hop spends the lag; the caller retries against fresher answers.
 *
 * <p>Runs after {@code AuthFilter}, so nothing is forwarded for a caller this
 * node would not even let in.
 */
@Provider
@Priority(Priorities.AUTHORIZATION)
public class IndexerForwardFilter implements ContainerRequestFilter {
	private static final Log logger = Log.of(IndexerForwardFilter.class);

	/**
	 * Header marking a request as already forwarded once.
	 */
	public static final String FORWARDED_HEADER = "X-Exofind-Forwarded";

	/**
	 * How long connecting to the indexer may take before it is considered
	 * unreachable. Only the connection - a request that is streaming a large
	 * body is given whatever time it needs, the same as it would be given
	 * arriving at the indexer directly.
	 */
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

	/**
	 * Request headers that describe the connection to this node rather than
	 * the request, and stay on it instead of being forwarded. The client
	 * sending the forwarded request supplies its own.
	 */
	private static final Set<String> CONNECTION_REQUEST_HEADERS = Set.of(
		"host",
		"connection",
		"content-length",
		"transfer-encoding",
		"expect",
		"upgrade",
		"te",
		"keep-alive",
		"trailer",
		"via",
		"proxy-authorization",
		"proxy-connection"
	);

	/**
	 * Response headers that describe the connection to the indexer rather
	 * than the response, and are not relayed. This node's own server supplies
	 * its own framing, and its own date.
	 */
	private static final Set<String> CONNECTION_RESPONSE_HEADERS = Set.of(
		"connection",
		"content-length",
		"transfer-encoding",
		"keep-alive",
		"trailer",
		"upgrade",
		"te",
		"via",
		"date",
		"server",
		"proxy-authenticate"
	);

	@Context
	ResourceInfo resourceInfo;

	private final NodeState nodeState;
	private final IndexerOwnership ownership;
	private final Indexes indexes;
	private final RequestMetrics metrics;
	private final HttpClient client;

	public IndexerForwardFilter(
		NodeState nodeState,
		IndexerOwnership ownership,
		Indexes indexes,
		RequestMetrics metrics
	) {
		this.nodeState = nodeState;
		this.ownership = ownership;
		this.indexes = indexes;
		this.metrics = metrics;

		/*
		 * Pinned to HTTP/1.1 rather than negotiating: the JDK client would
		 * otherwise try to upgrade cleartext connections, and a body that is
		 * being streamed through has nothing to gain from the attempt.
		 */
		this.client = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.followRedirects(HttpClient.Redirect.NEVER)
			.connectTimeout(CONNECT_TIMEOUT)
			.build();
	}

	@Override
	public void filter(ContainerRequestContext request) throws IOException {
		var method = resourceInfo.getResourceMethod();
		if(method == null) {
			// Nothing was matched, so there is nothing to say where it runs
			return;
		}

		if(isRead(request.getMethod())) {
			// Reads are always served where they land
			return;
		}

		var servedBy = method.getAnnotation(ServedBy.class);
		if(servedBy == null) {
			throw new IllegalStateException(
				"Endpoint " + method.getDeclaringClass().getName() + "#" + method.getName()
					+ " does not say which node serves it, so it cannot be served. Annotate"
					+ " it with @ServedBy"
			);
		}

		if(servedBy.value() == ServedBy.Node.ANY_NODE) {
			return;
		}

		var parameter = request.getUriInfo().getPathParameters()
			.getFirst(ServedBy.INDEX_PARAMETER);
		if(parameter == null) {
			throw new IllegalStateException(
				"Endpoint " + method.getDeclaringClass().getName() + "#" + method.getName()
					+ " is served by the node writing an index but has no `"
					+ ServedBy.INDEX_PARAMETER + "` path parameter naming which"
			);
		}

		// Ownership is held by name; every generation of it is written together
		var index = IndexName.parse(parameter).index();

		if(nodeState.isIndexer(index)) {
			return;
		}

		/*
		 * Only an index the deployment holds is worth appointing a writer
		 * for. Without this, a retried write to a name that does not exist
		 * would claim and drop the name over and over, contending with the
		 * real coordination - the 404 the endpoint answers costs nothing.
		 */
		var exists = servedBy.creates() || indexes.getRegistered(index).isPresent();

		if(exists && ownership.tryClaim(index)) {
			/*
			 * Nothing wrote the index and now this node does - which is how
			 * the first write to a created index, and the first write after
			 * its writer died, appoints one instead of failing. The reopen
			 * the claim queued runs on another thread, so the writer this
			 * request needs is opened before the request goes on to use it.
			 */
			indexes.reopenForWriting(index);
			return;
		}

		if(!exists && !ownership.hasHolder(index)) {
			/*
			 * The deployment does not hold the index and nothing writes it,
			 * so it is served here for the endpoint's 404. An index created
			 * elsewhere a moment ago is not this case: its creation claimed
			 * it, so it has a holder even while this node's registry lags.
			 */
			return;
		}

		if(request.getHeaderString(FORWARDED_HEADER) != null) {
			/*
			 * Already forwarded once and still nowhere it can be served, so
			 * whoever sent it here was working from an answer that has since
			 * gone stale. Refused rather than forwarded again - see the class
			 * doc.
			 */
			metrics.recordForward("stale");
			throw new IndexerUnavailableException();
		}

		var target = ownership.indexerAddress(index)
			.map(address -> resolve(address, request.getUriInfo().getRequestUri()))
			.orElse(null);

		if(target == null) {
			metrics.recordForward("unavailable");
			throw new IndexerUnavailableException();
		}

		forward(request, target);
	}

	private static boolean isRead(String method) {
		return HttpMethod.GET.equals(method)
			|| HttpMethod.HEAD.equals(method)
			|| HttpMethod.OPTIONS.equals(method);
	}

	/**
	 * Send the request to the indexer and answer with whatever it answered.
	 */
	private void forward(ContainerRequestContext request, URI target) {
		var outgoing = HttpRequest.newBuilder(target)
			.method(request.getMethod(), bodyOf(request));

		for(var header : request.getHeaders().entrySet()) {
			if(CONNECTION_REQUEST_HEADERS.contains(lower(header.getKey()))) {
				continue;
			}

			for(var value : header.getValue()) {
				outgoing.header(header.getKey(), value);
			}
		}

		outgoing.header(FORWARDED_HEADER, "true");

		HttpResponse<InputStream> response;
		try {
			response = client.send(outgoing.build(), HttpResponse.BodyHandlers.ofInputStream());
		} catch(IOException e) {
			metrics.recordForward("unreachable");
			throw new IndexerUnreachableException(e);
		} catch(InterruptedException e) {
			Thread.currentThread().interrupt();
			metrics.recordForward("unreachable");
			throw new IndexerUnreachableException(e);
		}

		metrics.recordForward(Meters.OUTCOME_SUCCESS);
		request.abortWith(relay(response, request.getUriInfo().getRequestUri()));
	}

	/**
	 * The body of the request as something that can be sent again, streaming
	 * rather than held - a dataset arriving as newline delimited JSON passes
	 * through without this node holding more than a buffer of it.
	 */
	private static HttpRequest.BodyPublisher bodyOf(ContainerRequestContext request) {
		if(!hasBody(request)) {
			return HttpRequest.BodyPublishers.noBody();
		}

		var stream = request.getEntityStream();
		return HttpRequest.BodyPublishers.ofInputStream(() -> stream);
	}

	/**
	 * Whether the request carries a body, read from how it was framed - the
	 * stream itself answers only by being consumed.
	 */
	private static boolean hasBody(ContainerRequestContext request) {
		var length = request.getHeaderString(HttpHeaders.CONTENT_LENGTH);
		if(length != null) {
			return !"0".equals(length.trim());
		}

		return request.getHeaderString("Transfer-Encoding") != null;
	}

	/**
	 * Turn the indexer's response into this request's response: same status,
	 * same body, same headers apart from the ones that describe the
	 * connection they arrived on.
	 */
	private static Response relay(HttpResponse<InputStream> response, URI requestUri) {
		var builder = Response.status(response.statusCode());

		for(var header : response.headers().map().entrySet()) {
			var name = lower(header.getKey());

			if(CONNECTION_RESPONSE_HEADERS.contains(name)) {
				continue;
			}

			for(var value : header.getValue()) {
				builder.header(
					header.getKey(),
					/*
					 * A Location the indexer built points at itself, and what
					 * it points at is served by every node - so it is pointed
					 * back at the host the caller was already talking to.
					 */
					"location".equals(name) ? rewriteLocation(value, requestUri) : value
				);
			}
		}

		if(mayCarryBody(response.statusCode())) {
			builder.entity(response.body());
		} else {
			close(response.body());
		}

		return builder.build();
	}

	/**
	 * Whether a status allows a body at all - relaying one anyway would make
	 * this node frame a message its own server refuses to send.
	 */
	private static boolean mayCarryBody(int status) {
		return status != 204 && status != 205 && status != 304;
	}

	/**
	 * Point an absolute location at the host the caller reached, keeping the
	 * path the indexer chose. A location that is not a URI with a host is
	 * passed through - relative ones already work from anywhere.
	 */
	private static String rewriteLocation(String location, URI requestUri) {
		try {
			var uri = URI.create(location);
			if(uri.getHost() == null) {
				return location;
			}

			return UriBuilder.fromUri(uri)
				.scheme(requestUri.getScheme())
				.host(requestUri.getHost())
				.port(requestUri.getPort())
				.build()
				.toString();
		} catch(IllegalArgumentException e) {
			return location;
		}
	}

	/**
	 * Point the request being served at the indexer: same path and query,
	 * with the scheme, host and port its address carries. An address without
	 * a port means the default port of its scheme.
	 *
	 * @return
	 *   the target, or {@code null} when the address cannot be resolved into
	 *   one
	 */
	private static URI resolve(String address, URI requestUri) {
		try {
			var addressUri = URI.create(address);
			if(addressUri.getHost() == null) {
				return null;
			}

			var builder = UriBuilder.fromUri(requestUri)
				.host(addressUri.getHost())
				.port(addressUri.getPort());

			if(addressUri.getScheme() != null) {
				builder.scheme(addressUri.getScheme());
			}

			return builder.build();
		} catch(IllegalArgumentException e) {
			logger.atWarn()
				.addKeyValue("address", address)
				.log("Indexer address cannot be forwarded to; " + e.getMessage());

			return null;
		}
	}

	private static String lower(String name) {
		return name.toLowerCase(Locale.ROOT);
	}

	private static void close(InputStream stream) {
		try {
			stream.close();
		} catch(IOException e) {
			// Nothing was going to be read from it
		}
	}
}
