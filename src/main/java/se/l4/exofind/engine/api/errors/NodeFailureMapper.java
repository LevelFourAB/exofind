package se.l4.exofind.engine.api.errors;

import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.logging.Log;
import se.l4.exofind.engine.metrics.RequestMetrics;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Answers a failure no other mapper names with the error body of the API.
 *
 * <p>A mapper is chosen by how close the class it names is to the class thrown,
 * so this one answers only what {@link EngineExceptionMapper},
 * {@link JsonExceptionMapper} and {@link RefusedRequestMapper} leave. Such a
 * failure is a fault of the node, so it is logged with its cause and answered
 * with {@code 500}.
 */
@Provider
@Priority(Priorities.USER)
public class NodeFailureMapper implements ExceptionMapper<Throwable> {
	private static final Log logger = Log.of(NodeFailureMapper.class);

	private static final ErrorType NODE_ERROR = ErrorType.withCode("node:error")
		.withMessage("The node could not serve the request");

	private final RequestMetrics metrics;

	public NodeFailureMapper(RequestMetrics metrics) {
		this.metrics = metrics;
	}

	@Override
	public Response toResponse(Throwable e) {
		metrics.recordError(NODE_ERROR.getCode());

		logger.atError()
			.addKeyValue("code", NODE_ERROR.getCode())
			.setCause(e)
			.log("Request failed; " + e.getMessage());

		return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
			.type(MediaType.APPLICATION_JSON)
			.entity(ErrorResponse.of(
				NODE_ERROR.getCode(),
				NODE_ERROR.getMessage(),
				null,
				null
			))
			.build();
	}
}
