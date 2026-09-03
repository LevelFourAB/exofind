package se.l4.exofind.engine.index;

import se.l4.exofind.engine.errors.ErrorType;

/**
 * Thrown when a generation is being created on a storage prefix that already
 * holds a manifest the registry does not name, and nothing says the data
 * was deleted.
 *
 * <p>A generation created there would pull the old files on open and start
 * out holding documents nobody indexed into it. Data a delete left behind
 * carries a removal mark and is cleared before the generation opens; a
 * manifest without one is either an interrupted rollout or an index whose
 * registry entry was lost, and which of the two cannot be read off the
 * storage. The caller chooses: a registry repair registers it, or the objects
 * are removed from the storage by hand.
 */
public class IndexStorageHeldException extends IndexException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType TYPE = ErrorType.withCode("index:generation:storage_held")
		.withArguments("generation")
		.withMessage(
			"The storage holds a generation `{{generation}}` the registry does not name,"
				+ " so nothing can be created there. Register it with a registry"
				+ " repair, or remove its objects from the storage"
		);

	public IndexStorageHeldException(IndexName generation) {
		super(TYPE, "generation", generation.toString());
	}
}
