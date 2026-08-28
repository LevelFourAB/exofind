package se.l4.exofind.engine.index.settings;

import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.index.IndexException;

/**
 * Thrown when the search settings of an index are asked for and the index has
 * none - it searches with its definition alone, which is the state deleting
 * the settings returns it to.
 */
public class SearchSettingsNotFoundException extends IndexException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType TYPE = ErrorType.withCode("index:settings:not_found")
		.withArguments("index")
		.withMessage("The index `{{index}}` has no search settings");

	public SearchSettingsNotFoundException(String index) {
		super(TYPE, "index", index);
	}
}
