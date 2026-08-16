package se.l4.exofind.engine.index;

import se.l4.exofind.engine.errors.ErrorType;

/**
 * Thrown when something needs the copy of a document as it was given and the
 * index does not have one - either because it is set to keep nothing, or
 * because the document was indexed while it was.
 *
 * <p>Changing some of the fields of a document is what needs it: the fields a
 * document has can not be told from the index alone, so there is nothing to
 * merge the change into. Sending the document whole is what an index without
 * the copies takes.
 */
public class IndexSourceNotKeptException extends IndexException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType TYPE = ErrorType.withCode("index:source:not_kept")
		.withArguments("index")
		.withMessage(
			"The index `{{index}}` does not keep a copy of the documents as they were given, so only whole documents can be indexed"
		);

	public IndexSourceNotKeptException(String index) {
		super(TYPE, "index", index);
	}
}
