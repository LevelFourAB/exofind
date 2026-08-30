package se.l4.exofind.engine.auth;

import java.io.IOException;

import se.l4.exofind.engine.index.state.ObjectStorageSync;
import se.l4.exofind.engine.storage.ObjectStorage;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * KeyStorage held as one object in the same bucket the indexes live in, so it
 * works wherever they do without needing any other infrastructure.
 *
 * <p>The object is replaced conditionally on its entity tag, the same way the
 * manifests and the indexer lease are. That is the whole of what keeps two
 * nodes from losing one another's changes: there is one object and no files
 * beside it, so nothing here needs the indexer role or an epoch to be safe.
 *
 * <p>The object lives next to the indexes rather than inside one, where nothing
 * that sweeps unreferenced index files can reach it.
 */
public class ObjectStorageKeyStorage implements KeyStorage {
	/**
	 * Object the keys are stored in, under the configured prefix.
	 */
	private static final String KEYS_NAME = "auth/keys.ef.bin";

	private final S3Client client;
	private final String bucket;
	private final String key;

	public ObjectStorageKeyStorage(ObjectStorage storage) {
		this.client = storage.client();
		this.bucket = storage.bucket();
		this.key = storage.rootObject(KEYS_NAME);
	}

	@Override
	public boolean isAvailable() {
		return true;
	}

	@Override
	public Read read(String knownVersion) throws IOException {
		var request = GetObjectRequest.builder()
			.bucket(bucket)
			.key(key);

		if(knownVersion != null) {
			request.ifNoneMatch(knownVersion);
		}

		try(var response = client.getObject(request.build())) {
			return new Read.Loaded(
				KeyStore.parseFrom(response.readAllBytes()),
				ObjectStorageSync.quoteETag(response.response().eTag())
			);
		} catch(S3Exception e) {
			if(e.statusCode() == 304) {
				return new Read.Unchanged();
			}

			if(e.statusCode() == 404) {
				return new Read.Absent();
			}

			throw new IOException("Unable to read the keys; " + e.getMessage(), e);
		} catch(Exception e) {
			throw new IOException("Unable to read the keys; " + e.getMessage(), e);
		}
	}

	@Override
	public String write(KeyStore keys, String expectedVersion) throws IOException {
		var request = PutObjectRequest.builder()
			.bucket(bucket)
			.key(key)
			.contentType("application/octet-stream");

		if(expectedVersion != null) {
			request.ifMatch(expectedVersion);
		} else {
			request.ifNoneMatch("*");
		}

		try {
			var response = client.putObject(
				request.build(),
				RequestBody.fromBytes(keys.toByteArray())
			);

			return ObjectStorageSync.quoteETag(response.eTag());
		} catch(S3Exception e) {
			if(ObjectStorage.isConditionalWriteLost(e)) {
				return null;
			}

			throw new IOException("Unable to write the keys; " + e.getMessage(), e);
		} catch(Exception e) {
			throw new IOException("Unable to write the keys; " + e.getMessage(), e);
		}
	}
}
