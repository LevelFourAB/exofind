package se.l4.exofind.engine.auth;

import java.io.IOException;
import java.util.Objects;

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
			/*
			 * The storage may drop the connection after it has taken the
			 * write, which arrives as a connection failure rather than as the
			 * answer to it. Read the keys back to tell what happened: exactly
			 * what was written means the write went through, a version other
			 * than the one it was conditioned on means another node got there
			 * first, and the version it was conditioned on means the write
			 * never arrived and may be made again.
			 *
			 * Without this a key that was in fact created is reported as a
			 * failure, leaving a credential that works and that whoever asked
			 * for it never saw.
			 */
			var current = tryRead();
			if(current != null) {
				if(current.keys().equals(keys)) {
					return current.version();
				}

				if(!Objects.equals(current.version(), expectedVersion)) {
					return null;
				}
			}

			throw new IOException("Unable to write the keys; " + e.getMessage(), e);
		}
	}

	/**
	 * Read the keys back for telling a dropped answer apart from a write that
	 * never landed, where failing a second time only means the first failure
	 * stands.
	 *
	 * @return
	 *   what the storage holds, or {@code null} when it holds nothing or could
	 *   not be reached
	 */
	private Read.Loaded tryRead() {
		try {
			return read(null) instanceof Read.Loaded loaded ? loaded : null;
		} catch(IOException e) {
			return null;
		}
	}
}
