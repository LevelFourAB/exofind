package se.l4.exofind.engine.index.registry;

import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.index.IndexException;

/**
 * Thrown when the registry audit is asked of a node that has no storage to
 * compare the registry with - a node storing locally, whose directory is the
 * deployment rather than a copy of one.
 */
public class RegistryAuditUnavailableException extends IndexException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType UNAVAILABLE =
		ErrorType.withCode("index:registry:audit_unavailable")
			.withMessage(
				"The registry audit compares the registry with the object storage the"
					+ " indexes live in, and this node stores locally - there is nothing"
					+ " to compare with while EXOFIND_STORAGE_MODE is 'local'"
			);

	public RegistryAuditUnavailableException() {
		super(UNAVAILABLE, ErrorType.toArguments());
	}
}
