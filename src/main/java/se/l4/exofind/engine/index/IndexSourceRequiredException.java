package se.l4.exofind.engine.index;

import se.l4.exofind.engine.errors.ErrorType;

/**
 * Thrown when a search asks for a field that only the copy of the document
 * could answer for, and the index keeps no copy.
 *
 * <p>An object, and every field inside one, is never stored on its own - an
 * index whose {@code source} is {@code none} can filter, sort and count them
 * but has nothing to return them from. Refused rather than answered with the
 * field left out, because the two look the same to a caller and only one of
 * them can be fixed.
 *
 * <p>What the definition says now is what decides this. Documents indexed
 * while the index did keep its copies still hold every value they were given,
 * and a search naming no fields at all brings back whatever each document has.
 */
public class IndexSourceRequiredException extends IndexException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType TYPE = ErrorType.withCode("index:query:source_not_kept")
		.withArguments("name")
		.withMessage(
			"Field `{{name}}` can only be returned from the copy of the document, which this index does not keep"
		);

	public IndexSourceRequiredException(String field) {
		super(TYPE, "name", field);
	}
}
