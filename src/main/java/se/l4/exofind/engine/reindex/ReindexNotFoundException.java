package se.l4.exofind.engine.reindex;

import se.l4.exofind.engine.errors.EngineException;
import se.l4.exofind.engine.errors.ErrorType;

/**
 * Thrown when a reindex is asked about for an index that has no record of
 * one.
 */
public class ReindexNotFoundException extends EngineException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType TYPE = ErrorType.withCode("reindex:not_found")
		.withArguments("index")
		.withMessage("The index `{{index}}` has no reindex");

	public ReindexNotFoundException(String index) {
		super(TYPE, "index", index);
	}
}
