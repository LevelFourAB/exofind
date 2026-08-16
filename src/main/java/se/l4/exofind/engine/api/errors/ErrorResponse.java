package se.l4.exofind.engine.api.errors;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The body returned for every failed request.
 *
 * The top level describes what went wrong with the request as a whole, while
 * {@code errors} carries the individual problems found in the request - a
 * definition with two bad fields reports both, so a caller does not have to
 * fix them one request at a time.
 *
 * @param code
 *   machine readable code for the failure
 * @param message
 *   human readable description of the failure
 * @param errors
 *   the individual problems, when the failure has any
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
	String code,
	String message,
	List<ErrorDetail> errors
) {
	/**
	 * @param code
	 *   machine readable code for this problem
	 * @param message
	 *   human readable description of this problem
	 * @param path
	 *   where in the request the problem is, such as {@code fields.title}, or
	 *   {@code null} when it applies to the request as a whole
	 * @param arguments
	 *   the values the message was rendered with, so a caller can render its
	 *   own message from the code
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ErrorDetail(
		String code,
		String message,
		String path,
		Map<String, String> arguments
	) {
	}
}
