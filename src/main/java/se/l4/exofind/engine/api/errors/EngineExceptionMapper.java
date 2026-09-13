package se.l4.exofind.engine.api.errors;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.map.MapIterable;

import se.l4.exofind.engine.auth.UnauthenticatedException;
import se.l4.exofind.engine.errors.EngineException;
import se.l4.exofind.engine.errors.ErrorMessage;
import se.l4.exofind.engine.errors.Retryable;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.logging.Log;
import se.l4.exofind.engine.metrics.RequestMetrics;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Turns the exceptions of the engine into {@link ErrorResponse}.
 *
 * <p>The status of the response is the one the {@code ErrorType} of the
 * exception declares, so a code is answered with one status wherever it is
 * thrown from, and a new exception gets a usable response - with its code,
 * status and message - without any work here. The reason a code is answered
 * with the status it has belongs beside the declaration of the code.
 */
@Provider
public class EngineExceptionMapper implements ExceptionMapper<EngineException> {
	private static final Log logger = Log.of(EngineExceptionMapper.class);

	private final RequestMetrics metrics;

	public EngineExceptionMapper(RequestMetrics metrics) {
		this.metrics = metrics;
	}

	@Override
	public Response toResponse(EngineException e) {
		/*
		 * The status is declared beside the code, so the type of the exception
		 * says what it is answered with and the class of the exception decides
		 * nothing. A type that states no status is a failure of the node.
		 */
		var status = e.getStatus();

		/*
		 * Counted by code rather than by status, since the code names what went
		 * wrong and several of them share a status.
		 */
		metrics.recordError(e.getCode());

		if(status >= 500) {
			logger.atError()
				.addKeyValue("code", e.getCode())
				.setCause(e)
				.log("Request failed; " + e.getMessage());
		}

		var response = Response.status(status)
			.type(MediaType.APPLICATION_JSON)
			.entity(toBody(e));

		if(e instanceof UnauthenticatedException) {
			/*
			 * Says which scheme to present a credential under, which is what
			 * makes a 401 answerable rather than only a refusal.
			 */
			response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		}

		if(e instanceof Retryable retryable) {
			/*
			 * Says how long to wait before sending the same request again, in
			 * whole seconds and never less than one, as the header counts
			 * seconds and a zero would say to retry at once.
			 */
			var seconds = Math.max(1, retryable.retryAfter().toSeconds());
			response.header(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
		}

		return response.build();
	}

	private static ErrorResponse toBody(EngineException e) {
		if(e instanceof ValidationException validation) {
			var errors = validation.getErrors();

			return new ErrorResponse(
				e.getCode(),
				errors.size() == 1
					? errors.get(0).getMessage()
					: "Request contains " + errors.size() + " errors",
				toDetails(errors)
			);
		}

		var detail = new ErrorResponse.ErrorDetail(
			e.getCode(),
			e.getMessage(),
			null,
			toArguments(e.getArguments())
		);

		return new ErrorResponse(e.getCode(), e.getMessage(), List.of(detail));
	}

	/**
	 * Render located errors as the API states them. A response that reports
	 * problems while still succeeding, such as the entries of a batch the index
	 * refused, describes them the same way a failed request does, so a client
	 * reads one shape wherever a problem reaches it.
	 *
	 * @param errors
	 * @return
	 *   the problems, in the order they were found
	 */
	public static List<ErrorResponse.ErrorDetail> toDetails(ListIterable<ErrorMessage> errors) {
		return errors.collect(EngineExceptionMapper::toDetail).toList();
	}

	private static ErrorResponse.ErrorDetail toDetail(ErrorMessage message) {
		var path = message.getLocation().describe();

		return new ErrorResponse.ErrorDetail(
			message.getCode(),
			message.getMessage(),
			path.isEmpty() ? null : path,
			toArguments(message.getArguments())
		);
	}

	/**
	 * Render the arguments of an error as strings. Arguments exist so callers
	 * can build their own message from the code, which does not need the
	 * types the engine happens to use internally.
	 *
	 * @param arguments
	 * @return
	 */
	private static Map<String, String> toArguments(MapIterable<String, Object> arguments) {
		if(arguments.isEmpty()) {
			return null;
		}

		var result = new LinkedHashMap<String, String>();
		for(var entry : arguments.keyValuesView()) {
			result.put(entry.getOne(), String.valueOf(entry.getTwo()));
		}

		return result;
	}
}
