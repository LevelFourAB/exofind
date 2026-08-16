package se.l4.exofind.engine.index;

import se.l4.exofind.engine.errors.ErrorType;

public class IndexInvalidQueryTypeException extends IndexException {
	private static final ErrorType TYPE = ErrorType.withCode("index:invalid-query-type")
		.withArguments("fieldType", "queryType")
		.withMessage("The query type `{{queryType}}` is invalid for field type `{{fieldType}}`");

	public IndexInvalidQueryTypeException(String fieldType, String queryType) {
		super(TYPE, "fieldType", fieldType, "queryType", queryType);
	}
}
