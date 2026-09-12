package se.l4.exofind.engine.api.errors;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.collections.api.map.MapIterable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.metrics.RequestMetrics;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Turns a request body Jackson could not read into an {@link ErrorResponse}.
 *
 * <p>A body that is not JSON, a property no model has, and a value that does
 * not fit the property it is written at are all the caller's to fix, so each
 * is answered with {@code 400} and a {@code request:*} code. Without this the
 * framework answers them with a shape of its own, and the API states one body
 * for every failure.
 *
 * <p>The {@code path} of the problem is a JSON Pointer into the body, such as
 * {@code /fields/title/matching}. It names the place in the request as the
 * caller wrote it, not the Java type the request was read into.
 *
 * <p>Jackson reports a value that does not fit as
 * {@link com.fasterxml.jackson.databind.exc.MismatchedInputException}, which
 * the framework maps itself. {@link MismatchedInputExceptionMapper} takes those
 * over and builds its answer here.
 */
@Provider
@Priority(Priorities.USER)
public class JsonExceptionMapper implements ExceptionMapper<JsonProcessingException> {
	private static final ErrorType MALFORMED = ErrorType.withCode("request:malformed")
		.withArguments("reason", "line", "column")
		.withMessage("The request body could not be read as JSON: {{reason}}");

	private static final ErrorType UNKNOWN_PROPERTY = ErrorType
		.withCode("request:unknown_property")
		.withArguments("path", "property")
		.withMessage("`{{property}}` is not a property of this request");

	private static final ErrorType VALUE_INVALID = ErrorType.withCode("request:value_invalid")
		.withArguments("path", "reason")
		.withMessage("`{{path}}` cannot be given that value: {{reason}}");

	/** The same failure where the value is the body itself, which has no path. */
	private static final ErrorType BODY_INVALID = ErrorType.withCode("request:value_invalid")
		.withArguments("reason")
		.withMessage("The request body is not shaped as this request needs: {{reason}}");

	private final RequestMetrics metrics;

	public JsonExceptionMapper(RequestMetrics metrics) {
		this.metrics = metrics;
	}

	@Override
	public Response toResponse(JsonProcessingException e) {
		return respond(metrics, e);
	}

	/**
	 * Answer one body Jackson could not read, counting the code it is answered
	 * with the way every other failure is counted.
	 */
	static Response respond(RequestMetrics metrics, JsonProcessingException e) {
		var body = toBody(e);
		metrics.recordError(body.code());

		return Response.status(Response.Status.BAD_REQUEST)
			.type(MediaType.APPLICATION_JSON)
			.entity(body)
			.build();
	}

	/**
	 * The body for one failure, chosen by what Jackson could not do with the
	 * request.
	 */
	private static ErrorResponse toBody(JsonProcessingException e) {
		if(e instanceof UnrecognizedPropertyException unknown) {
			/*
			 * A property no model has. Dropping it would serve the request
			 * with the setting the property named left at its default, so a
			 * misspelled name is reported instead.
			 */
			var path = pointerOf(unknown);

			return body(
				UNKNOWN_PROPERTY,
				path,
				ErrorType.toArguments(
					"path", path,
					"property", unknown.getPropertyName()
				)
			);
		} else if(e instanceof JsonMappingException mapping) {
			/*
			 * A value that does not fit where it sits: the wrong type, or a
			 * tag naming no member of a union.
			 */
			var path = pointerOf(mapping);
			var reason = reasonOf(mapping);

			return path == null
				? body(BODY_INVALID, null, ErrorType.toArguments("reason", reason))
				: body(
					VALUE_INVALID,
					path,
					ErrorType.toArguments("path", path, "reason", reason)
				);
		}

		/*
		 * Not JSON at all, so there is no property to point at. The line and
		 * the column where the parse stopped say where to look.
		 */
		var location = e.getLocation();

		return body(
			MALFORMED,
			null,
			ErrorType.toArguments(
				"reason", e.getOriginalMessage(),
				"line", location == null ? null : location.getLineNr(),
				"column", location == null ? null : location.getColumnNr()
			)
		);
	}

	private static ErrorResponse body(
		ErrorType type,
		String path,
		MapIterable<String, Object> arguments
	) {
		return ErrorResponse.of(
			type.getCode(),
			type.format(arguments),
			path,
			toArguments(arguments)
		);
	}

	/**
	 * Write where a mapping failure sits as a JSON Pointer, so the path points
	 * into the request as it was sent.
	 *
	 * @return
	 *   the pointer, or {@code null} when the failure is of the body as a whole
	 */
	private static String pointerOf(JsonMappingException e) {
		var pointer = new StringBuilder();

		for(var reference : e.getPath()) {
			if(reference.getFieldName() != null) {
				pointer.append('/').append(escape(reference.getFieldName()));
			} else if(reference.getIndex() >= 0) {
				pointer.append('/').append(reference.getIndex());
			}
		}

		return pointer.isEmpty() ? null : pointer.toString();
	}

	/**
	 * Escape one name for a JSON Pointer, where {@code ~} and {@code /} are the
	 * two characters a segment cannot carry as itself.
	 */
	private static String escape(String name) {
		return name.replace("~", "~0").replace("/", "~1");
	}

	/**
	 * The sentence Jackson wrote about the value, without the chain of types it
	 * appends. The path already says where the value sits, in the names the
	 * request uses.
	 */
	private static String reasonOf(JsonMappingException e) {
		var reason = e.getOriginalMessage();
		var at = reason.indexOf(" (through reference chain:");

		return at < 0 ? reason : reason.substring(0, at);
	}

	/**
	 * Render the arguments as strings, leaving out the ones the failure had
	 * nothing to put in.
	 */
	private static Map<String, String> toArguments(MapIterable<String, Object> arguments) {
		var result = new LinkedHashMap<String, String>();

		for(var entry : arguments.keyValuesView()) {
			if(entry.getTwo() != null) {
				result.put(entry.getOne(), String.valueOf(entry.getTwo()));
			}
		}

		return result.isEmpty() ? null : result;
	}
}
