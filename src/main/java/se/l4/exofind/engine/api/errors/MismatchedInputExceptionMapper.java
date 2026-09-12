package se.l4.exofind.engine.api.errors;

import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import se.l4.exofind.engine.metrics.RequestMetrics;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Answers a value that does not fit the property it is written at with the
 * error body of the API.
 *
 * <p>The framework ships a mapper for this exact exception that writes a shape
 * of its own, and a mapper is chosen by the class it names before the priority
 * it carries. This one names the same class at a priority ahead of the
 * framework's, so it takes those failures over. {@link JsonExceptionMapper}
 * builds the answer, and handles every other way a body fails to read.
 */
@Provider
@Priority(Priorities.USER)
public class MismatchedInputExceptionMapper
	implements ExceptionMapper<MismatchedInputException> {
	private final RequestMetrics metrics;

	public MismatchedInputExceptionMapper(RequestMetrics metrics) {
		this.metrics = metrics;
	}

	@Override
	public Response toResponse(MismatchedInputException e) {
		return JsonExceptionMapper.respond(metrics, e);
	}
}
