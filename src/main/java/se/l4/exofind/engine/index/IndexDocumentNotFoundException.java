package se.l4.exofind.engine.index;

import se.l4.exofind.engine.errors.ErrorType;

/**
 * Thrown when a request names one document by its primary key and the index
 * holds nothing under it.
 *
 * <p>Changing some of the fields of a document is what needs the document to
 * be there: it says what to change about a document rather than what should be
 * there, so there is nothing to write without one. Indexing and removal are
 * statements of desired state and never throw this.
 */
public class IndexDocumentNotFoundException extends IndexException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType TYPE = ErrorType.withCode("index:document:not_found")
		.withArguments("key")
		.withMessage("No document is indexed under the key `{{key}}`");

	public IndexDocumentNotFoundException(String key) {
		super(TYPE, "key", key);
	}
}
