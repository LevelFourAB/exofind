package se.l4.exofind.engine.api.errors;

import com.fasterxml.jackson.core.JsonProcessingException;

import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.logging.Log;
import se.l4.exofind.engine.metrics.RequestMetrics;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Gives the error body of the API to a request the framework refused before an
 * endpoint ran.
 *
 * <p>A path no endpoint answers, a method an endpoint does not have, a body in
 * a media type it does not read, and an {@code Accept} it cannot answer in are
 * all refused by the framework itself, which answers them with a status and
 * nothing else. A client reads the same body for every failure, so each of
 * these carries one as well.
 *
 * <p>A refusal that already carries a body is answered as it is. Only a
 * response with nothing in it is filled in here, so an endpoint that builds a
 * {@link WebApplicationException} of its own keeps what it built.
 */
@Provider
@Priority(Priorities.USER)
public class RefusedRequestMapper implements ExceptionMapper<WebApplicationException> {
	private static final Log logger = Log.of(RefusedRequestMapper.class);

	private static final ErrorType NOT_FOUND = ErrorType.withCode("request:not_found")
		.withStatus(404)
		.withMessage("No endpoint answers this path");

	private static final ErrorType METHOD_NOT_ALLOWED = ErrorType
		.withCode("request:method_not_allowed")
		.withStatus(405)
		.withMessage("This path is not answered for this method");

	private static final ErrorType NOT_ACCEPTABLE = ErrorType
		.withCode("request:not_acceptable")
		.withStatus(406)
		.withMessage("This endpoint answers in none of the media types `Accept` allows");

	private static final ErrorType UNSUPPORTED_MEDIA_TYPE = ErrorType
		.withCode("request:unsupported_media_type")
		.withStatus(415)
		.withMessage("This endpoint does not read a body in the media type `Content-Type` names");

	/** A refusal with a status none of the above names. */
	private static final ErrorType REFUSED = ErrorType.withCode("request:refused")
		.withStatus(400)
		.withMessage("The request was refused");

	/** The node failed, and the request is not the thing that is wrong. */
	private static final ErrorType NODE_ERROR = ErrorType.withCode("node:error")
		.withStatus(500)
		.withMessage("The node could not serve the request");

	private final RequestMetrics metrics;
	private final JsonExceptionMapper jsonErrors;

	public RefusedRequestMapper(RequestMetrics metrics, JsonExceptionMapper jsonErrors) {
		this.metrics = metrics;
		this.jsonErrors = jsonErrors;
	}

	@Override
	public Response toResponse(WebApplicationException e) {
		var refused = e.getResponse();

		if(refused.hasEntity()) {
			return refused;
		}

		if(e.getCause() instanceof JsonProcessingException json) {
			/*
			 * The body reader wraps a body it could not parse, so the
			 * failure arrives here with the parse failure as its cause.
			 */
			return jsonErrors.toResponse(json);
		}

		var status = refused.getStatus();
		var type = typeOf(status);

		metrics.recordError(type.getCode());

		if(status >= 500) {
			logger.atError()
				.addKeyValue("code", type.getCode())
				.setCause(e)
				.log("Request failed; " + e.getMessage());
		}

		return Response.fromResponse(refused)
			.type(MediaType.APPLICATION_JSON)
			.entity(bodyOf(status))
			.build();
	}

	/**
	 * The body a refusal carries, built from the status the request was
	 * refused with.
	 *
	 * @param status
	 *   the HTTP status of the refusal
	 * @return
	 *   the body to answer with
	 */
	static ErrorResponse bodyOf(int status) {
		var type = typeOf(status);

		return ErrorResponse.of(type.getCode(), type.getMessage(), null, null);
	}

	/**
	 * The code a refusal carries, from the status the framework chose. The
	 * status says which refusal it is; the exception classes the framework
	 * throws for them are not all distinct.
	 */
	private static ErrorType typeOf(int status) {
		return switch(status) {
			case 404 -> NOT_FOUND;
			case 405 -> METHOD_NOT_ALLOWED;
			case 406 -> NOT_ACCEPTABLE;
			case 413 -> RequestBodyTooLargeException.TYPE;
			case 415 -> UNSUPPORTED_MEDIA_TYPE;
			default -> status >= 500 ? NODE_ERROR : REFUSED;
		};
	}
}
