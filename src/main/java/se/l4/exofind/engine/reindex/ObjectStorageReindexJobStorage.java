package se.l4.exofind.engine.reindex;

import java.io.IOException;
import java.util.Optional;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ListIterable;

import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.state.ObjectStorageSync;
import se.l4.exofind.engine.storage.ObjectStorage;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * ReindexJobStorage held in the bucket the indexes live in, one object per
 * index under a common prefix, replaced conditionally on its tag the way the
 * manifests are.
 */
public class ObjectStorageReindexJobStorage implements ReindexJobStorage {
	/**
	 * Prefix the records live under, directly under the configured prefix
	 * next to the indexes - where nothing that sweeps unreferenced index
	 * files can reach them. One prefix for all of them, so the fleet-wide
	 * listing is one prefix listing.
	 */
	private static final String JOBS_PATH = "jobs/reindex/";

	private final S3Client client;
	private final String bucket;
	private final String prefix;

	public ObjectStorageReindexJobStorage(ObjectStorage storage) {
		this.client = storage.client();
		this.bucket = storage.bucket();
		this.prefix = storage.rootObject(JOBS_PATH.substring(0, JOBS_PATH.length() - 1)) + "/";
	}

	@Override
	public Optional<Stored> read(String index) throws IOException {
		return readKey(keyOf(index));
	}

	private Optional<Stored> readKey(String key) throws IOException {
		try(
			var response = client.getObject(
				GetObjectRequest.builder()
					.bucket(bucket)
					.key(key)
					.build()
			)
		) {
			return Optional.of(new Stored(
				ReindexJobStore.parseFrom(response.readAllBytes()),
				ObjectStorageSync.quoteETag(response.response().eTag())
			));
		} catch(S3Exception e) {
			if(e.statusCode() == 404) {
				return Optional.empty();
			}

			throw new IOException("Unable to read reindex record; " + e.getMessage(), e);
		} catch(Exception e) {
			throw new IOException("Unable to read reindex record; " + e.getMessage(), e);
		}
	}

	@Override
	public String write(String index, ReindexJobStore record, String expectedVersion)
		throws IOException {
		var requestBuilder = PutObjectRequest.builder()
			.bucket(bucket)
			.key(keyOf(index))
			.contentType("application/octet-stream");

		if(expectedVersion != null) {
			requestBuilder.ifMatch(expectedVersion);
		} else {
			requestBuilder.ifNoneMatch("*");
		}

		try {
			var response = client.putObject(
				requestBuilder.build(),
				RequestBody.fromBytes(record.toByteArray())
			);

			return ObjectStorageSync.quoteETag(response.eTag());
		} catch(S3Exception e) {
			if(ObjectStorage.isConditionalWriteLost(e)) {
				return null;
			}

			throw new IOException("Unable to write reindex record; " + e.getMessage(), e);
		} catch(Exception e) {
			/*
			 * The storage may drop the connection while refusing a conditional
			 * write, which arrives as a connection failure rather than the
			 * refusal. Read the record back to tell what happened: exactly what
			 * was written means the write went through, anything else stands
			 * for a refusal or a write that never arrived.
			 */
			var current = tryRead(index);
			if(current != null) {
				if(current.record().equals(record)) {
					return current.version();
				}

				if(!current.version().equals(expectedVersion)) {
					return null;
				}
			}

			throw new IOException("Unable to write reindex record; " + e.getMessage(), e);
		}
	}

	private Stored tryRead(String index) {
		try {
			return read(index).orElse(null);
		} catch(IOException e) {
			return null;
		}
	}

	@Override
	public void delete(String index) throws IOException {
		try {
			client.deleteObject(
				DeleteObjectRequest.builder()
					.bucket(bucket)
					.key(keyOf(index))
					.build()
			);
		} catch(Exception e) {
			throw new IOException("Unable to remove reindex record; " + e.getMessage(), e);
		}
	}

	@Override
	public ListIterable<Stored> list() throws IOException {
		var found = Lists.mutable.<Stored>empty();

		try {
			var pages = client.listObjectsV2Paginator(
				ListObjectsV2Request.builder()
					.bucket(bucket)
					.prefix(prefix)
					.build()
			);

			for(var page : pages) {
				for(var object : page.contents()) {
					readKey(object.key()).ifPresent(found::add);
				}
			}
		} catch(IOException e) {
			throw e;
		} catch(Exception e) {
			throw new IOException("Unable to list reindex records; " + e.getMessage(), e);
		}

		return found;
	}

	/**
	 * The key one index's record lives under. The name is validated as an
	 * index name, so a record can never land outside the prefix.
	 */
	private String keyOf(String index) {
		return prefix + IndexName.of(index).toString();
	}
}
