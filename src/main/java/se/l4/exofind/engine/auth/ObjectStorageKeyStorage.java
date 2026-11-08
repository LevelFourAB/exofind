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
				if(ObjectStorage.isUnchanged(e, knownVersion)) {
					return new Read.Unchanged();
				}

				/*
				 * Same bytes under another version. The known version would
				 * refuse every write, so fetch the one the storage holds.
				 */
				return read(null);
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

			/*
			 * A server error does not say that the write was refused. A proxy
			 * in front of the storage answers 504 when it stops waiting, and
			 * the storage can finish the write after that.
			 */
			return resolveUnanswered(keys, expectedVersion, e);
		} catch(Exception e) {
			/*
			 * The storage may drop the connection after it has taken the
			 * write, which arrives as a connection failure and not as the
			 * answer to it.
			 */
			return resolveUnanswered(keys, expectedVersion, e);
		}
	}

	/**
	 * Read the keys back to find out what became of a write whose answer
	 * does not tell. Exactly what was written means the write went through.
	 * A version other than the one the write was conditioned on means another
	 * node wrote first. The version the write was conditioned on means the
	 * write never arrived and may be made again.
	 *
	 * <p>Without this a key that was in fact created is reported as a
	 * failure, and its credential works although the caller never saw it. A
	 * rotate that landed is also reported as a failure, and the old
	 * credential stops working.
	 *
	 * @return
	 *   the version the keys are stored under when the write went through,
	 *   or {@code null} when another node wrote first
	 * @throws IOException
	 *   if the write did not arrive, or the keys could not be read back
	 */
	private String resolveUnanswered(
		KeyStore keys,
		String expectedVersion,
		Exception failure
	) throws IOException {
		var current = tryRead();
		if(current != null) {
			if(current.keys().equals(keys)) {
				return current.version();
			}

			if(!Objects.equals(current.version(), expectedVersion)) {
				return null;
			}
		}

		throw new IOException("Unable to write the keys; " + failure.getMessage(), failure);
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
