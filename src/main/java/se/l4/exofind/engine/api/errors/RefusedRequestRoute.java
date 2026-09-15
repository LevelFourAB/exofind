package se.l4.exofind.engine.api.errors;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.logging.Log;
import se.l4.exofind.engine.metrics.RequestMetrics;
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
 * <p>A request that states a {@code Content-Length} past what
 * {@link RequestBodyLimits} allows is refused here, before the body arrives and
 * before a resource method is chosen. Refusing it early is what keeps the node
 * from reading a body it has already decided not to take.
 *
 * <p>A body that states no length is counted as it arrives instead, by
 * {@link RequestBodyLimitFilter}. Between them every request body the node
 * reads is bounded.
 *
 * <p>The answer closes the connection, because the caller is still sending a
 * body that nothing reads.
 */
@ApplicationScoped
public class RefusedRequestRoute {
	private static final Log logger = Log.of(RefusedRequestRoute.class);

	/**
	 * Where this runs among the handlers of the router. Ahead of everything the
	 * framework registers, so that a body past the limit is answered before any
	 * handler asks for it.
	 */
	private static final int ORDER = -3;

	private final ObjectMapper mapper;
	private final RequestMetrics metrics;
	private final RequestBodyLimits limits;

	public RefusedRequestRoute(
		ObjectMapper mapper,
		RequestMetrics metrics,
		RequestBodyLimits limits
	) {
		this.mapper = mapper;
		this.metrics = metrics;
		this.limits = limits;
	}

	/**
	 * Add the handler to the router of the node while it is being built.
	 */
	public void register(@Observes Router router) {
		router.route().order(ORDER).handler(this::answer);
	}

	private void answer(RoutingContext context) {
		var headers = context.request().headers();
		var limit = limits.forContentType(headers.get(HttpHeaders.CONTENT_TYPE));
		var length = headers.get(HttpHeaders.CONTENT_LENGTH);

		if(length == null || !isPastLimit(length, limit)) {
			context.next();
			return;
		}

		var status = Response.Status.REQUEST_ENTITY_TOO_LARGE.getStatusCode();
		var arguments = ErrorType.toArguments("limit", limit);
		var body = ErrorResponse.of(
			RequestBodyTooLargeException.TYPE.getCode(),
			RequestBodyTooLargeException.TYPE.format(arguments),
			null,
			Map.of("limit", Long.toString(limit))
		);

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
	private static boolean isPastLimit(String length, long limit) {
		if(limit == RequestBodyLimits.NONE) {
			return false;
		}

		try {
			return Long.parseLong(length) > limit;
		} catch(NumberFormatException e) {
			return false;
		}
	}
}
