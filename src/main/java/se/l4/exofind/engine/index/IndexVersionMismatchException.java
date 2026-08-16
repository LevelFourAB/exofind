package se.l4.exofind.engine.index;

import se.l4.exofind.engine.errors.ErrorType;

/**
 * IndexVersionMismatchException is thrown when a definition is updated based
 * on a version that is no longer the current one, indicating that someone else
 * has updated the definition in the meantime.
 */
public class IndexVersionMismatchException extends IndexException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType TYPE = ErrorType.withCode("index:version-mismatch")
		.withArguments("index", "expected", "actual")
		.withMessage(
			"The definition of index `{{index}}` has version `{{actual}}`, but the update expected `{{expected}}`"
		);

	public IndexVersionMismatchException(String index, String expected, String actual) {
		super(TYPE, "index", index, "expected", expected, "actual", actual);
	}
}
