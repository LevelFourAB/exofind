package se.l4.exofind.engine.index;

import se.l4.exofind.engine.errors.ErrorType;

/**
 * Thrown when a score explanation names something the index does not hold -
 * a document under the given key, or a value of an object field at the given
 * position.
 *
 * <p>An explanation is asked for by naming one target, the same way a document
 * is read by its key, so a target that is not there is reported as a name that
 * answers to nothing rather than as a search that is written wrong.
 */
public class IndexExplainTargetNotFoundException extends IndexException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType DOCUMENT =
		ErrorType.withCode("index:explain:document_not_found")
			.withArguments("key")
			.withMessage(
				"No document is indexed under the key `{{key}}`, so there is nothing to explain"
			);

	private static final ErrorType VALUE =
		ErrorType.withCode("index:explain:value_not_found")
			.withArguments("key", "path", "index")
			.withMessage(
				"The document `{{key}}` has no value of `{{path}}` at position {{index}}"
			);

	private IndexExplainTargetNotFoundException(ErrorType type, Object... arguments) {
		super(type, arguments);
	}

	/**
	 * The index holds no document under the key the explanation names.
	 *
	 * @param key
	 * @return
	 */
	public static IndexExplainTargetNotFoundException document(String key) {
		return new IndexExplainTargetNotFoundException(DOCUMENT, "key", key);
	}

	/**
	 * The document is indexed and holds no value of the named object field at
	 * the given position.
	 *
	 * @param key
	 * @param path
	 *   the object field the values belong to
	 * @param index
	 *   the position among those values
	 * @return
	 */
	public static IndexExplainTargetNotFoundException value(
		String key,
		String path,
		int index
	) {
		return new IndexExplainTargetNotFoundException(
			VALUE,
			"key", key,
			"path", path,
			"index", String.valueOf(index)
		);
	}
}
