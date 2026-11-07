package se.l4.exofind.engine.index.state;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.OptionalLong;

import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.registry.ObjectStorageRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryCodec;
import se.l4.exofind.engine.index.registry.RegistryStorage;
import se.l4.exofind.engine.storage.ObjectStorage;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

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
		return createSync(generation, dataPath, null);
	}

	@Override
	public StateSync createSync(IndexName generation, Path dataPath, Instant createdAt) {
		return new ObjectStorageSync(
			storage.client(),
			generation.toString(),
			dataPath,
			storage.bucket(),
			storage.indexPath(generation),
			ObjectStorageSync.DEFAULT_GRACE,
			createdAt,
			copyCreatedAt -> isRegistered(generation, copyCreatedAt)
		);
	}

	/**
	 * Read the registry the bucket holds, and tell whether it names the
	 * generation as created at the given time. Read from the bucket rather
	 * than from the copy a node holds, as the question is asked when a
	 * manifest is gone: that is when a node's copy can be a refresh behind a
	 * delete.
	 */
	private boolean isRegistered(IndexName generation, Instant createdAt) throws IOException {
		var read = new ObjectStorageRegistryStorage(storage).read(null);
		if(!(read instanceof RegistryStorage.Read.Loaded loaded)) {
			return false;
		}

		return RegistryCodec.fromStored(loaded.indexes())
			.detectOptional(entry -> entry.name().equals(generation.index()))
			.flatMap(entry -> entry.generation(generation.generation()))
			.map(found -> createdAt == null
				|| found.createdAt() == null
				|| found.createdAt().toEpochMilli() == createdAt.toEpochMilli())
			.orElse(false);
	}

	@Override
	public boolean hasRemote() {
		return true;
	}

	@Override
	public OptionalLong remoteVersion(IndexName generation) throws IOException {
		var request = GetObjectRequest.builder()
			.bucket(storage.bucket())
			.key(storage.indexPath(generation) + "/" + LocalCopy.MANIFEST_FILE)
			.build();

		/*
		 * A single attempt without the retries a synchronization gets: this
		 * feeds a hint, and a caller that could not learn the version now asks
		 * again on its next pass.
		 */
		try(var response = storage.client().getObject(request)) {
			var manifest = Manifest.parseFrom(response.readAllBytes());
			return manifest.hasVersion()
				? OptionalLong.of(manifest.getVersion())
				: OptionalLong.empty();
		} catch(S3Exception e) {
			if(e.statusCode() == 404) {
				return OptionalLong.empty();
			}

			throw new IOException(
				"Unable to read the manifest of " + generation + "; " + e.getMessage(),
				e
			);
		} catch(IOException e) {
			throw e;
		} catch(Exception e) {
			throw new IOException(
				"Unable to read the manifest of " + generation + "; " + e.getMessage(),
				e
			);
		}
	}
}
