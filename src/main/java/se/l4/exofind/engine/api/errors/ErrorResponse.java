package se.l4.exofind.engine.api.errors;

import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

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
@Schema(
	name = "ErrorResponse",
	description = """
		The body of every failed request. Error codes use colon-separated \
		namespaces such as `index:field:invalid_name`, are stable across API \
		versions, and are never renamed or reused, so clients match on `code` \
		rather than on `message`. See \
		[Errors](https://levelfourab.github.io/exofind/reference/errors/)."""
)
public record ErrorResponse(
	@Schema(
		description = """
			Identifies the failure type. For validation failures this is \
			`validation` and the individual problems are listed in `errors`; \
			for every other failure it matches the `code` of the single entry \
			in `errors`.""",
		examples = "validation"
	)
	String code,

	@Schema(
		description = """
			Human-readable message for log output. Match on `code` rather than \
			on `message`. When a validation failure holds one problem this is \
			that problem's message; when it holds several it reads \
			`Request contains N errors`.""",
		examples = "Request contains 2 errors"
	)
	String message,

	@Schema(description = """
		All problems found in the request. A validation failure reports every \
		field it found a problem with, so a caller fixes them in one pass \
		rather than one request at a time.""")
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
	@Schema(name = "ErrorDetail", description = "One problem found in a request.")
	public record ErrorDetail(
		@Schema(
			description = "The error code identifying this specific problem.",
			examples = "index:field:invalid_primary_key_multiple"
		)
		String code,

		@Schema(
			description = "Human-readable description of this problem.",
			examples = "Field `id` is marked as a primary key and multiple, primary keys can not have multiple values"
		)
		String message,

		@Schema(
			description = """
				Location of the offending value in the request, such as \
				`fields.title` or `documents[1].nonexistent`. Omitted when the \
				problem applies to the request as a whole.""",
			examples = "id"
		)
		String path,

		@Schema(description = """
			The values the message was rendered with, so a client can render a \
			message of its own from the code.""")
		Map<String, String> arguments
	) {
	}
}
