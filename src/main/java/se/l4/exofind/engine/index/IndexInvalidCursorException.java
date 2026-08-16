package se.l4.exofind.engine.index;

import se.l4.exofind.engine.errors.ErrorType;

/**
 * Thrown when a search continues from a {@link se.l4.exofind.engine.query.SortKey}
 * that does not fit how the search is ordered - the key carries a value per
 * step of the sort it was taken under, tie breakers included, so a sort that
 * has changed shape since leaves the key not naming a position at all.
 *
 * Continuing from where the key was handed out is the caller's to redo, which
 * is why this is a refusal rather than an answer from the wrong position.
 */
public class IndexInvalidCursorException extends IndexException {
	private static final ErrorType TYPE = ErrorType.withCode("index:query:invalid_cursor")
		.withMessage(
			"The position to continue from does not fit how the search is ordered"
		);

	public IndexInvalidCursorException() {
		super(TYPE);
	}
}
