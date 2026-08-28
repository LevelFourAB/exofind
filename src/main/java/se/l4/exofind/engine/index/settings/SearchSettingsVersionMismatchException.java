package se.l4.exofind.engine.index.settings;

import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.index.IndexException;

/**
 * Thrown when search settings are replaced based on a version that is no
 * longer the current one, indicating that someone else has changed them in the
 * meantime.
 */
public class SearchSettingsVersionMismatchException extends IndexException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType TYPE = ErrorType.withCode("index:settings:version_mismatch")
		.withArguments("index")
		.withMessage(
			"The search settings of index `{{index}}` are not at the version the update expected"
		);

	public SearchSettingsVersionMismatchException(String index) {
		super(TYPE, "index", index);
	}
}
