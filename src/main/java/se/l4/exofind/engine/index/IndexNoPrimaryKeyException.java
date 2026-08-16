package se.l4.exofind.engine.index;

import se.l4.exofind.engine.errors.ErrorType;

/**
 * Thrown when a request names a document by its primary key in an index whose
 * definition declares none.
 *
 * Without a primary key a document is only ever added, never replaced or
 * removed on its own, as nothing in the index tells one document from another.
 * Answering as though the key had simply not been indexed would read the same
 * way, and only one of the two is fixed by indexing it.
 */
public class IndexNoPrimaryKeyException extends IndexException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType TYPE = ErrorType.withCode("index:no_primary_key")
		.withArguments("index")
		.withMessage(
			"The index `{{index}}` has no primary key, so a document can not be named by one"
		);

	public IndexNoPrimaryKeyException(String index) {
		super(TYPE, "index", index);
	}
}
