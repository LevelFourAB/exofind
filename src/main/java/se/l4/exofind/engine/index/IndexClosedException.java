package se.l4.exofind.engine.index;

import se.l4.exofind.engine.errors.ErrorType;

/**
 * Thrown when an operation reaches an {@link Index} instance that has been
 * closed on this node. Happens when a caller holds on to an instance across
 * the moment it is closed to make room for another index - the index itself
 * still exists, and asking for it again opens a fresh instance.
 */
public class IndexClosedException extends IndexException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType TYPE = ErrorType.withCode("index:closed")
		.withArguments("index")
		.withMessage("The index `{{index}}` was closed on this node, try the request again");

	public IndexClosedException(String index) {
		super(TYPE, "index", index);
	}
}
