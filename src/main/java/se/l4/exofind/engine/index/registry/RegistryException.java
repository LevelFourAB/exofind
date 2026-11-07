package se.l4.exofind.engine.index.registry;

import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.index.IndexException;

/**
 * Thrown when a change to the registry of indexes could not be stored.
 *
 * <p>Two things stop a change: the storage could not be reached, or another
 * node changed the registry at the same time often enough that this change
 * never got a version to build on. Each has its own code, and both leave the
 * registry exactly as it was - so no index was created, promoted or removed.
 *
 * <p>One case is not sure: a write that the storage did not answer, followed
 * by a read that does not tell whether it landed. The cause is then a
 * {@link RegistryWriteUnknownException}, and the change may be in the
 * registry. A create takes its own registration back in that case, and a
 * removal is reported as {@link RegistryRemovalUnknownException}.
 */
public class RegistryException extends IndexException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType CONFLICT = ErrorType.withCode("storage:conflict")
		.withStatus(409)
		.withMessage(
			"The indexes were changed by someone else while this change was being made"
		);

	private static final ErrorType IO_ERROR = ErrorType.withCode("storage:io_error")
		.withStatus(409)
		.withMessage("The indexes could not be read from or written to storage");

	private RegistryException(ErrorType type) {
		super(type, ErrorType.toArguments());
	}

	/**
	 * A failure to reach the storage, for a subclass that tells more about
	 * it.
	 */
	protected RegistryException(Throwable cause) {
		this(IO_ERROR, cause);
	}

	private RegistryException(ErrorType type, Throwable cause) {
		super(type, ErrorType.toArguments(), cause);
	}

	/**
	 * Another node kept changing the registry underneath this change.
	 */
	public static RegistryException conflict() {
		return new RegistryException(CONFLICT);
	}

	/**
	 * The storage could not be reached.
	 */
	public static RegistryException ioError(Throwable cause) {
		return new RegistryException(IO_ERROR, cause);
	}
}
