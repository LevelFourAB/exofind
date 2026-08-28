package se.l4.exofind.engine.index;

import org.eclipse.collections.api.list.ListIterable;

import se.l4.exofind.engine.errors.ErrorMessage;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ValidationException;

/**
 * Thrown when a definition would be stored over documents that were not
 * indexed under it.
 *
 * <p>Carries one located error per difference, from
 * {@link se.l4.exofind.engine.index.schema.DefinitionCompatibility}, so a
 * caller is told which fields refused rather than only that something did.
 *
 * <p>Nothing about the definition is wrong - the same one is accepted by a
 * generation holding no documents - so this says the request and the state of
 * the index disagree. It is answered by filling a new generation and promoting
 * it, or, where the documents are about to be sent again anyway, by saying
 * outright that they may go stale.
 */
public class IndexDefinitionIncompatibleException extends ValidationException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType ERROR_TYPE =
		ErrorType.withCode("index:definition:incompatible")
			.withArguments("errors")
			.withMessage(
				"The definition changes how documents are indexed, and the generation"
					+ " already holds documents indexed the previous way: {{errors}}"
			);

	public IndexDefinitionIncompatibleException(ListIterable<ErrorMessage> errors) {
		super(ERROR_TYPE, errors);
	}
}
