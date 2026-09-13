package se.l4.exofind.engine.api.v1alpha1.documents.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

import se.l4.exofind.engine.api.errors.ErrorResponse.ErrorDetail;

/**
 * One entry of a batch the index refused, reported by a request that was sent
 * with {@code ?onError=skip} and carried on past it.
 *
 * <p>The entry is named twice over, because the two numbers count different
 * things. {@code position} counts the entries the request sent, from zero, and
 * locates the entry in the list a caller sent. {@code line} counts the lines of
 * a newline-delimited body, from one, and locates the entry in the file a
 * caller sent it from. A body that spreads an entry over several lines, or
 * separates entries with blank lines, has more lines than entries.
 *
 * @param position
 *   which entry of the batch this was, counted from zero
 * @param line
 *   the line of a newline-delimited body the entry starts on, counted from one,
 *   or {@code null} for a body that carries the entries in an array
 * @param errors
 *   what was wrong with the entry, described the way a failed request describes
 *   its problems
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
	name = "DocumentFailure",
	description = """
		One entry of a batch the index refused, reported by a request sent with \
		`?onError=skip`. The entry is named by `position` for a caller walking \
		the list it sent, and also by `line` for a caller pointing at a \
		newline-delimited file.""",
	examples = DocumentFailure.EXAMPLE
)
public record DocumentFailure(
	@Schema(
		description = """
			Which entry of the batch this was, counted from zero. Matches the \
			index in the `path` of each error.""",
		examples = "3"
	)
	int position,

	@Schema(
		description = """
			The line of the request body the entry starts on, counted from one. \
			Present only for a newline-delimited body; a body that carries the \
			entries in a `documents` array omits it.""",
		examples = "4"
	)
	Integer line,

	@Schema(description = """
		Everything wrong with the entry, in the shape the `errors` of a failed \
		request take.""")
	List<ErrorDetail> errors
) {
	/** The example failure, as the JSON the engine answers with. */
	public static final String EXAMPLE = """
		{
		  "position": 3,
		  "line": 4,
		  "errors": [
		    {
		      "code": "document:field_unknown",
		      "message": "Field `nonexistent` does not exist in index",
		      "path": "documents[3].nonexistent",
		      "arguments": {
		        "position": "3",
		        "processed": "3",
		        "name": "nonexistent"
		      }
		    }
		  ]
		}""";
}
