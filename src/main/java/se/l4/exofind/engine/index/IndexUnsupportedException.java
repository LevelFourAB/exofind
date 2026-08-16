package se.l4.exofind.engine.index;

import se.l4.exofind.engine.errors.ErrorType;

/**
 * Thrown when an index says its meaning depends on something this build does
 * not have, so this node refuses to resolve it rather than answering from a
 * generation the deployment did not name.
 *
 * <p>Unlike {@link IndexState#UNSUPPORTED}, which is a definition this node
 * cannot read, this is reached before anything about the index is opened - the
 * registry alone says the node is too old for it. Upgrading the node is the way
 * out of both.
 */
public class IndexUnsupportedException extends IndexException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType TYPE = ErrorType.withCode("index:unsupported")
		.withArguments("index", "features")
		.withMessage(
			"The index `{{index}}` needs features this node does not have: {{features}}."
				+ " Upgrade this node to use it"
		);

	public IndexUnsupportedException(String index, String features) {
		super(TYPE, "index", index, "features", features);
	}
}
