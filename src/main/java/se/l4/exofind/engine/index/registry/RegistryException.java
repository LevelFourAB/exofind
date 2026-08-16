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
 */
public class RegistryException extends IndexException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType CONFLICT = ErrorType.withCode("index:registry:conflict")
		.withMessage(
			"The indexes were changed by someone else while this change was being made"
		);

	private static final ErrorType IO_ERROR = ErrorType.withCode("index:registry:io_error")
		.withMessage("The indexes could not be read from or written to storage");

	private RegistryException(ErrorType type) {
		super(type, ErrorType.toArguments());
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
