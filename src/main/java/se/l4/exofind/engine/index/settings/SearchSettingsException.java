package se.l4.exofind.engine.index.settings;

import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.index.IndexException;

/**
 * Thrown when a change to the search settings of an index could not be stored.
 *
 * <p>Three things stop a change: this node has nowhere to keep settings, the
 * storage could not be reached, or another node changed the settings at the
 * same time often enough that this change never got a version to build on.
 * Each has its own code, and all three leave the stored settings exactly as
 * they were.
 */
public class SearchSettingsException extends IndexException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType UNAVAILABLE = ErrorType.withCode("index:settings:unavailable")
		.withMessage(
			"This node has nowhere to keep search settings and cannot manage them"
		);

	private static final ErrorType CONFLICT = ErrorType.withCode("index:settings:conflict")
		.withMessage(
			"The settings were changed by someone else while this change was being made"
		);

	private static final ErrorType IO_ERROR = ErrorType.withCode("index:settings:io_error")
		.withMessage("The settings could not be read from or written to storage");

	private SearchSettingsException(ErrorType type) {
		super(type, ErrorType.toArguments());
	}

	private SearchSettingsException(ErrorType type, Throwable cause) {
		super(type, ErrorType.toArguments(), cause);
	}

	/**
	 * This node has no storage to keep settings in.
	 */
	public static SearchSettingsException unavailable() {
		return new SearchSettingsException(UNAVAILABLE);
	}

	/**
	 * Another node kept changing the settings underneath this change.
	 */
	public static SearchSettingsException conflict() {
		return new SearchSettingsException(CONFLICT);
	}

	/**
	 * The storage could not be reached.
	 */
	public static SearchSettingsException ioError(Throwable cause) {
		return new SearchSettingsException(IO_ERROR, cause);
	}
}
