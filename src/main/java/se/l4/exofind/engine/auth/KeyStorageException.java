package se.l4.exofind.engine.auth;

import se.l4.exofind.engine.errors.ErrorType;

/**
 * Thrown when a change to the keys could not be stored.
 *
 * <p>Three things stop a change: this node has nowhere to keep keys, the
 * storage could not be reached, or another node changed the keys at the same
 * time often enough that this change never got a version to build on. Each has
 * its own code, and all three leave the stored keys exactly as they were.
 */
public class KeyStorageException extends AuthException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType UNAVAILABLE = ErrorType.withCode("auth:keys:unavailable")
		.withMessage(
			"This node has nowhere to keep keys and cannot manage them; it can only be"
				+ " reached with its root key"
		);

	private static final ErrorType CONFLICT = ErrorType.withCode("auth:keys:conflict")
		.withMessage("The keys were changed by someone else while this change was being made");

	private static final ErrorType IO_ERROR = ErrorType.withCode("auth:keys:io_error")
		.withMessage("The keys could not be read from or written to storage");

	private KeyStorageException(ErrorType type) {
		super(type);
	}

	private KeyStorageException(ErrorType type, Throwable cause) {
		super(type, ErrorType.toArguments(), cause);
	}

	/**
	 * This node has no storage to keep keys in.
	 */
	public static KeyStorageException unavailable() {
		return new KeyStorageException(UNAVAILABLE);
	}

	/**
	 * Another node kept changing the keys underneath this change.
	 */
	public static KeyStorageException conflict() {
		return new KeyStorageException(CONFLICT);
	}

	/**
	 * The storage could not be reached.
	 */
	public static KeyStorageException ioError(Throwable cause) {
		return new KeyStorageException(IO_ERROR, cause);
	}
}
