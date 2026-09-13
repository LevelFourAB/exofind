package se.l4.exofind.engine.index;

import se.l4.exofind.engine.errors.ErrorType;

public class IndexOutOfDateException extends IndexException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType TYPE = ErrorType.withCode("index:out_of_date")
		.withStatus(409)
		.withArguments("index")
		.withMessage("The index `{{index}}` has state `{{state}}` and cannot be modified");

	public IndexOutOfDateException(String index, IndexState state) {
		super(TYPE, "index", index, "state", state);
	}
}
