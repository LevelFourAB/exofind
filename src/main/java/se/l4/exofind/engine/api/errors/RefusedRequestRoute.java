package se.l4.exofind.engine.api.errors;

import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import se.l4.exofind.engine.logging.Log;
import se.l4.exofind.engine.metrics.RequestMetrics;
import io.quarkus.runtime.configuration.MemorySize;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Gives the error body of the API to a body larger than the node accepts.
 *
 * <p>A request whose {@code Content-Length} is past
 * {@code quarkus.http.limits.max-body-size} is refused by the HTTP router
 * before a resource method is chosen, so no
 * {@link jakarta.ws.rs.ext.ExceptionMapper} ever sees it and the refusal the
 * router writes carries no body. This answers such a request first, with the
 * body every other failure carries, and leaves every other request to the rest
 * of the stack.
 *
 * <p>The answer closes the connection, because the caller is still sending a
 * body that nothing reads.
 */
@ApplicationScoped
public class RefusedRequestRoute {
	private static final Log logger = Log.of(RefusedRequestRoute.class);

	/**
	 * Where this runs among the handlers of the router. The refusal it replaces
	 * is written at {@code -2}, so this has to be ahead of that one.
	 */
	private static final int ORDER = -3;

	private final ObjectMapper mapper;
	private final RequestMetrics metrics;
	private final long limit;

	public RefusedRequestRoute(
		ObjectMapper mapper,
		RequestMetrics metrics,
		@ConfigProperty(name = "quarkus.http.limits.max-body-size") Optional<MemorySize> limit
	) {
		this.mapper = mapper;
		this.metrics = metrics;
		this.limit = limit.map(MemorySize::asLongValue).orElse(Long.MAX_VALUE);
	}

	/**
	 * Add the handler to the router of the node while it is being built.
	 */
	public void register(@Observes Router router) {
		router.route().order(ORDER).handler(this::answer);
	}

	private void answer(RoutingContext context) {
		var length = context.request().headers().get(HttpHeaders.CONTENT_LENGTH);

		if(length == null || !isPastLimit(length)) {
			context.next();
			return;
		}

		var status = Response.Status.REQUEST_ENTITY_TOO_LARGE.getStatusCode();
		var body = RefusedRequestMapper.bodyOf(status);

		String json;
		try {
			json = mapper.writeValueAsString(body);
		} catch(JsonProcessingException e) {
			logger.atError()
				.setCause(e)
				.log("The body of a refused request could not be written");

			context.next();
			return;
		}

		metrics.recordError(body.code());

		context.response()
			.putHeader(HttpHeaders.CONNECTION, "close")
			.putHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
			.setStatusCode(status)
			.end(json);
	}

	/**
	 * Whether the length a request states is past what this node accepts. A
	 * length that is not a number is left to the rest of the stack, which
	 * refuses the request for being malformed.
	 */
	private boolean isPastLimit(String length) {
		try {
			return Long.parseLong(length) > limit;
		} catch(NumberFormatException e) {
			return false;
		}
	}
}
