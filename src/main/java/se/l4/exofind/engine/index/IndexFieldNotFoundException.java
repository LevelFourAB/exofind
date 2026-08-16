package se.l4.exofind.engine.index;

import se.l4.exofind.engine.errors.ErrorType;

/**
 * Thrown when a query mentions a field the index does not have.
 *
 * Searching a field that is not there is answered with this rather than with
 * no results, because the two look the same to a caller and only one of them
 * can be fixed.
 */
public class IndexFieldNotFoundException extends IndexException {
	private static final ErrorType TYPE = ErrorType.withCode("index:query:field_not_found")
		.withArguments("name")
		.withMessage("Field `{{name}}` does not exist in index");

	public IndexFieldNotFoundException(String field) {
		super(TYPE, "name", field);
	}
}
