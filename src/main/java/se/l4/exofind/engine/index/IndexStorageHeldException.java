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
 *
 * <p>In local storage mode the same applies to an index directory that the
 * registry does not name and that holds a definition, a change log or a
 * Lucene commit. A delete in local mode writes no mark, so nothing says that
 * such a directory was deleted, and it can be the only copy of its data. See
 * {@link #inLocalDirectory(IndexName)}.
 *
 * <p>In object storage mode, a creation is also refused when the registry is
 * gone and the bucket holds generations of any index. See
 * {@link #withoutRegistry(IndexName)}.
 */
public class IndexStorageHeldException extends IndexException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType TYPE = ErrorType.withCode("index:generation:storage_held")
		.withStatus(409)
		.withArguments("generation")
		.withMessage(
			"The storage holds a generation `{{generation}}` the registry does not name,"
				+ " so nothing can be created there. Register it with a registry"
				+ " repair, or remove its objects from the storage"
		);

	/**
	 * The same condition in local storage mode, where there is no registry
	 * repair and the data is a directory on the disk of the node.
	 */
	private static final ErrorType LOCAL_TYPE = ErrorType.withCode("index:generation:storage_held")
		.withStatus(409)
		.withArguments("generation")
		.withMessage(
			"The local storage directory holds a generation `{{generation}}` the registry"
				+ " does not name, so nothing can be created there. Put back a registry"
				+ " that names it, or delete its directory by hand if its index was deleted"
		);

	/**
	 * The same condition when the registry itself is gone, and the storage
	 * holds generations of other indexes than the one being created.
	 */
	private static final ErrorType WITHOUT_REGISTRY_TYPE =
		ErrorType.withCode("index:generation:storage_held")
			.withStatus(409)
			.withArguments("generation")
			.withMessage(
				"The registry is gone from the storage, but the storage holds the generation"
					+ " `{{generation}}`. Rebuild the registry with a registry repair before"
					+ " you create an index"
			);

	public IndexStorageHeldException(IndexName generation) {
		this(TYPE, generation);
	}

	private IndexStorageHeldException(ErrorType type, IndexName generation) {
		super(type, "generation", generation.toString());
	}

	/**
	 * Refuse a creation over the directory of a generation that a node in
	 * local storage mode holds and that the registry does not name.
	 *
	 * @param generation
	 *   the generation whose directory holds data
	 * @return
	 *   the exception to throw
	 */
	public static IndexStorageHeldException inLocalDirectory(IndexName generation) {
		return new IndexStorageHeldException(LOCAL_TYPE, generation);
	}

	/**
	 * Refuse a creation that would write the first registry, when the
	 * registry is gone and the storage holds a generation that a registry
	 * repair registers. A registry written from nothing names only the new
	 * index, and every node that reads it drops all other indexes.
	 *
	 * @param generation
	 *   a generation the storage holds
	 * @return
	 *   the exception to throw
	 */
	public static IndexStorageHeldException withoutRegistry(IndexName generation) {
		return new IndexStorageHeldException(WITHOUT_REGISTRY_TYPE, generation);
	}
}
