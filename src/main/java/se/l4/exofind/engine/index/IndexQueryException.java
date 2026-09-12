package se.l4.exofind.engine.index;

import org.eclipse.collections.api.map.MapIterable;

import se.l4.exofind.engine.errors.ErrorType;

/**
 * Thrown when a search asks the index for something the way it is written can
 * not be answered - a clause on a field the path does not hold, a facet range
 * that covers nothing, a locale this engine has no analysis for.
 *
 * <p>Sending the same search again gives the same answer, and rewriting it is
 * what makes it run, so it is reported as a request to fix rather than as a
 * fault of the node. The arguments of the error say which part to change.
 *
 * <p>Errors that carry their own arguments, such as a field that is missing,
 * have a class of their own. This one covers the codes shared by the places a
 * query is compiled, each of which describes itself through its
 * {@link ErrorType}.
 */
public class IndexQueryException extends IndexException {
	private static final long serialVersionUID = 1L;

	public IndexQueryException(ErrorType type, MapIterable<String, Object> arguments) {
		super(type, arguments);
	}

	public IndexQueryException(ErrorType type, Object... arguments) {
		super(type, arguments);
	}
}
