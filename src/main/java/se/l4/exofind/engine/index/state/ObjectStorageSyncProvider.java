package se.l4.exofind.engine.index.state;

import java.nio.file.Path;

import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.storage.ObjectStorage;

/**
 * StateSyncProvider for indexes shared through an object storage, where the
 * bucket is the source of truth and this node holds a copy.
 */
public class ObjectStorageSyncProvider implements StateSyncProvider {
	private final ObjectStorage storage;

	public ObjectStorageSyncProvider(ObjectStorage storage) {
		this.storage = storage;
	}

	@Override
	public StateSync createSync(IndexName generation, Path dataPath) {
		return new ObjectStorageSync(
			storage.client(),
			generation.toString(),
			dataPath,
			storage.bucket(),
			storage.indexPath(generation)
		);
	}
}
