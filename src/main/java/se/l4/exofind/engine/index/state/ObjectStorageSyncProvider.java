package se.l4.exofind.engine.index.state;

import java.io.IOException;
import java.nio.file.Path;
import java.util.OptionalLong;

import se.l4.exofind.engine.index.IndexName;
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
		return new ObjectStorageSync(
			storage.client(),
			generation.toString(),
			dataPath,
			storage.bucket(),
			storage.indexPath(generation)
		);
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
