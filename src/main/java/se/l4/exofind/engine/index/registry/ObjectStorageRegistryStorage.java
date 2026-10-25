package se.l4.exofind.engine.index.registry;

import java.io.IOException;
import java.util.Objects;

import com.google.protobuf.InvalidProtocolBufferException;

import se.l4.exofind.engine.index.state.ObjectStorageSync;
import se.l4.exofind.engine.storage.ObjectStorage;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * RegistryStorage held as one object in the same bucket the indexes live in,
 * so it works wherever they do without needing any other infrastructure.
 *
 * <p>The object is replaced conditionally on its entity tag, the same way the
 * manifests, the indexer lease and the keys are. That is the whole of what
 * keeps two nodes from losing one another's changes: there is one object and
 * no files beside it, so nothing here needs the indexer role or an epoch to be
 * safe.
 *
 * <p>Reading it is one conditional request that answers with nothing when the
 * registry has not moved, which is what lets a node learn about every index in
 * the deployment on an interval without the cost growing with how many there
 * are.
 *
 * <p>The object lives next to the indexes rather than inside one, where nothing
 * that sweeps unreferenced index files can reach it.
 */
public class ObjectStorageRegistryStorage implements RegistryStorage {
	/**
	 * Object the registry is stored in, under the configured prefix.
	 */
	private static final String REGISTRY_NAME = "registry/indexes.ef.bin";

	private final S3Client client;
	private final String bucket;
	private final String key;

	public ObjectStorageRegistryStorage(ObjectStorage storage) {
		this.client = storage.client();
		this.bucket = storage.bucket();
		this.key = storage.rootObject(REGISTRY_NAME);
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
			var contents = response.readAllBytes();
			var version = ObjectStorageSync.quoteETag(response.response().eTag());

			try {
				return new Read.Loaded(IndexRegistryStore.parseFrom(contents), version);
			} catch(InvalidProtocolBufferException e) {
				return new Read.Corrupt(version);
			}
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

			throw new IOException("Unable to read the index registry; " + e.getMessage(), e);
		} catch(Exception e) {
			throw new IOException("Unable to read the index registry; " + e.getMessage(), e);
		}
	}

	@Override
	public String write(IndexRegistryStore indexes, String expectedVersion) throws IOException {
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
				RequestBody.fromBytes(indexes.toByteArray())
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
			return resolveUnanswered(indexes, expectedVersion, e);
		} catch(Exception e) {
			/*
			 * The storage may drop the connection after it has taken the
			 * write, which arrives as a connection failure rather than as the
			 * answer to it.
			 */
			return resolveUnanswered(indexes, expectedVersion, e);
		}
	}

	/**
	 * Read the registry back to find out what became of a write whose
	 * answer does not tell. Exactly what was written means the write went
	 * through, a version other than the one it was conditioned on means
	 * another node got there first, and the version it was conditioned on
	 * means the write never arrived and may be made again.
	 *
	 * <p>Without this a change that landed is reported as a failure. The
	 * caller then skips the step that follows the change: a create does not
	 * clear what a delete of the name left, and a delete leaves no removal
	 * mark. A retry is answered with the name already existing, or with the
	 * index being gone.
	 *
	 * @param indexes
	 *   what the write tried to store
	 * @param expectedVersion
	 *   version the write was conditioned on, or {@code null} for a write
	 *   conditioned on there being no registry
	 * @param failure
	 *   what the write was answered with
	 * @return
	 *   the version the registry is stored under when the write went
	 *   through, or {@code null} when another node wrote first
	 * @throws IOException
	 *   if the write did not arrive, or the registry could not be read back
	 */
	private String resolveUnanswered(
		IndexRegistryStore indexes,
		String expectedVersion,
		Exception failure
	) throws IOException {
		var current = tryRead();
		if(current != null) {
			if(current.indexes().equals(indexes)) {
				return current.version();
			}

			if(!Objects.equals(current.version(), expectedVersion)) {
				return null;
			}
		}

		throw new IOException(
			"Unable to write the index registry; " + failure.getMessage(),
			failure
		);
	}

	/**
	 * Read the registry back for telling a dropped answer apart from a write
	 * that never landed, where failing a second time only means the first
	 * failure stands.
	 *
	 * @return
	 *   what the storage holds, or {@code null} when it holds nothing, holds
	 *   something that cannot be read, or could not be reached
	 */
	private Read.Loaded tryRead() {
		try {
			return read(null) instanceof Read.Loaded loaded ? loaded : null;
		} catch(IOException e) {
			return null;
		}
	}
}
