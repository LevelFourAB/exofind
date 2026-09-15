package se.l4.exofind.engine.api.errors;

import com.fasterxml.jackson.databind.exc.MismatchedInputException;

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
	private final JsonExceptionMapper jsonErrors;

	public MismatchedInputExceptionMapper(JsonExceptionMapper jsonErrors) {
		this.jsonErrors = jsonErrors;
	}

	@Override
	public Response toResponse(MismatchedInputException e) {
		return jsonErrors.toResponse(e);
	}
}
