package se.l4.exofind.engine.index.registry;

import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.index.IndexException;

/**
 * Raised when a promote that was conditional on the generation an index
 * answers for found another one there.
 *
 * <p>Carried by
 * {@link IndexRegistry#promote(String, String, String) a conditional promote},
 * which a reindex makes: the generation it filled is only complete against the
 * one it read, so promoting it over a third generation that went live in the
 * meantime would take the index back to what it read and lose everything
 * written to that third generation.
 */
public class LiveGenerationMovedException extends IndexException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType TYPE =
		ErrorType.withCode("index:generation:live_moved")
			.withArguments("index", "expected", "live")
			.withMessage(
				"The index `{{index}}` answers for the generation `{{live}}`"
					+ " rather than `{{expected}}`, so it cannot be promoted"
					+ " away from it"
			);

	public LiveGenerationMovedException(String index, String expected, String live) {
		super(TYPE, "index", index, "expected", expected, "live", String.valueOf(live));
	}
}
