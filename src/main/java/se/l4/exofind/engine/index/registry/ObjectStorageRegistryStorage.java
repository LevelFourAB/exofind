package se.l4.exofind.engine.index.registry;

import java.io.IOException;

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
				return new Read.Unchanged();
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
			if(e.statusCode() == 412) {
				return null;
			}

			throw new IOException("Unable to write the index registry; " + e.getMessage(), e);
		} catch(Exception e) {
			throw new IOException("Unable to write the index registry; " + e.getMessage(), e);
		}
	}
}
