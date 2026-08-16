package se.l4.exofind.engine.index;

import se.l4.exofind.engine.errors.ErrorType;

public class IndexNotFoundException extends IndexException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType TYPE = ErrorType.withCode("index:not-found")
		.withArguments("index")
		.withMessage("The index `{{index}}` does not exist");

	public IndexNotFoundException(String index) {
		super(TYPE, "index", index);
	}
}
