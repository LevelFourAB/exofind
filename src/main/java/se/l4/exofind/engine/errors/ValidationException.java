package se.l4.exofind.engine.errors;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.list.ListIterable;

/**
 * ValidationException is thrown when one or more validation errors occur.
 */
public class ValidationException extends EngineException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType ERROR_TYPE = ErrorType.withCode("validation")
		.withArguments("errors")
		.withMessage("Validation failed, with errors: {{errors}}");

	private final ListIterable<ErrorMessage> errors;

	public ValidationException(ListIterable<ErrorMessage> errors) {
		super(ERROR_TYPE, Maps.immutable.of("errors", errors));
		this.errors = errors.toImmutable();
	}

	public ValidationException(ErrorMessage... errors) {
		this(Lists.immutable.of(errors));
	}

	/**
	 * Get the validation errors that caused this exception.
	 * 
	 * @return
	 */
	public ListIterable<ErrorMessage> getErrors() {
		return errors;
	}
}
