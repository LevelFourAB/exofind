package se.l4.exofind.engine.reindex;

import se.l4.exofind.engine.errors.EngineException;
import se.l4.exofind.engine.errors.ErrorType;

/**
 * Thrown for a request that would change a generation a reindex job is
 * filling - writing documents into it, or promoting it before the job says it
 * is ready. The job's replay works from a copy only it writes, so anything
 * else landing in the target would invalidate it.
 */
public class ReindexTargetBusyException extends EngineException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType TYPE = ErrorType.withCode("reindex:target_busy")
		.withArguments("name")
		.withMessage(
			"The generation `{{name}}` is being filled by a reindex and cannot be"
				+ " changed until the reindex is finished or cancelled"
		);

	public ReindexTargetBusyException(String name) {
		super(TYPE, "name", name);
	}
}
