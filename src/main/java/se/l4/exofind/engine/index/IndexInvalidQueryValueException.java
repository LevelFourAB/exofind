package se.l4.exofind.engine.index;

import se.l4.exofind.engine.errors.ErrorType;

/**
 * Thrown when a matcher carries a value of the wrong kind for the field it is
 * used on, such as looking for a number in a field that holds text.
 */
public class IndexInvalidQueryValueException extends IndexException {
	private static final ErrorType TYPE = ErrorType.withCode("index:query:invalid_value")
		.withArguments("name", "expected")
		.withMessage("Field `{{name}}` can only be searched for a {{expected}} value");

	public IndexInvalidQueryValueException(String field, String expected) {
		super(TYPE, "name", field, "expected", expected);
	}
}
