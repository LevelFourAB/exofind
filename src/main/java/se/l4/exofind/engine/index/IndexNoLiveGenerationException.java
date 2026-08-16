package se.l4.exofind.engine.index;

import se.l4.exofind.engine.errors.ErrorType;

/**
 * Thrown when an index exists but answers for none of its generations, so
 * there is nothing for its name to resolve to.
 *
 * <p>Told apart from the index not being there because the two are fixed
 * differently: this one is answered by promoting a generation, and the
 * generations are there to be listed and searched by name in the meantime.
 */
public class IndexNoLiveGenerationException extends IndexException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType TYPE = ErrorType.withCode("index:no_live_generation")
		.withArguments("index")
		.withMessage(
			"The index `{{index}}` has no live generation, so its name answers for nothing."
				+ " Promote one of its generations"
		);

	public IndexNoLiveGenerationException(String index) {
		super(TYPE, "index", index);
	}
}
