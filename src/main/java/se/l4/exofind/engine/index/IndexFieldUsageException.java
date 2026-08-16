package se.l4.exofind.engine.index;

import se.l4.exofind.engine.errors.ErrorType;

/**
 * Thrown when a query uses a field in a way the definition of the index did
 * not ask for.
 *
 * Every way of using a field is opt-in and costs something at index time, so
 * what was never asked for was never written. Filtering on a field that is not
 * filterable would find nothing at all, which reads as an empty index rather
 * than as the definition needing a line added to it.
 */
public class IndexFieldUsageException extends IndexException {
	private static final ErrorType TYPE = ErrorType.withCode("index:query:usage_not_enabled")
		.withArguments("name", "usage")
		.withMessage("Field `{{name}}` is not defined for `{{usage}}`");

	public IndexFieldUsageException(String field, String usage) {
		super(TYPE, "name", field, "usage", usage);
	}
}
