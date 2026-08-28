package se.l4.exofind.engine.reindex;

import se.l4.exofind.engine.errors.EngineException;
import se.l4.exofind.engine.errors.ErrorType;

/**
 * Thrown when a reindex is asked for while the index already has one that is
 * not finished. One job per index at a time - cancel the running one, or wait
 * for it.
 */
public class ReindexInProgressException extends EngineException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType TYPE = ErrorType.withCode("reindex:in_progress")
		.withArguments("index")
		.withMessage(
			"The index `{{index}}` already has a reindex that is not finished"
		);

	public ReindexInProgressException(String index) {
		super(TYPE, "index", index);
	}
}
