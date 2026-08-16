package se.l4.exofind.engine.index;

import se.l4.exofind.engine.errors.ErrorType;

public class IndexReadonlyException extends IndexException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType TYPE = ErrorType.withCode("index:readonly")
		.withArguments("index")
		.withMessage("The index `{{index}}` is readonly and cannot be modified");

	public IndexReadonlyException(String index) {
		super(TYPE, "index", index);
	}
}
