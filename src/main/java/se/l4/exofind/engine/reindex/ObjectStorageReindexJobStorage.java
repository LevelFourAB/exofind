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
 *
 * <p>A marker object under a second prefix tracks which jobs have not
 * finished, so {@link #listUnfinished()} costs one listing plus a read per
 * running job. See {@link #ACTIVE_PATH}.
 */
public class ObjectStorageReindexJobStorage implements ReindexJobStorage {
	/**
	 * Prefix the records live under, inside the configured prefix next to the
	 * indexes - where nothing that sweeps unreferenced index files can reach
	 * them. One prefix for all of them, so the fleet-wide listing is one
	 * prefix listing.
	 *
	 * <p>The records have a prefix of their own under {@code jobs/reindex/}
	 * so that {@link #ACTIVE_PATH} can have one beside it. An index is free to
	 * be named {@code active}, and holding the records directly under
	 * {@code jobs/reindex/} would put that index's record on the prefix the
	 * markers list.
	 */
	private static final String JOBS_PATH = "jobs/reindex/records";

	/**
	 * Prefix holding one empty marker object per job that has not finished,
	 * using the same name as the job's record.
	 *
	 * <p>Every indexer candidate looks for jobs to resume a few times a
	 * minute, and a record is kept after its job ends. Reading the phase out
	 * of each record would cost one read per job the deployment has ever run,
	 * and that number never falls. The markers cost one listing plus a read
	 * per job that is still running.
	 *
	 * <p>A marker is a hint. {@link #listUnfinished()} deletes one whose
	 * record has finished or was never written. A running job can also lose
	 * its marker: the delete that ends one job and the write that accepts the
	 * next job for the same index are separate requests and can arrive out of
	 * order. A caller that must not miss a job reads {@link #list()} as well.
	 */
	private static final String ACTIVE_PATH = "jobs/reindex/active";

	private final S3Client client;
	private final String bucket;
	private final String prefix;
	private final String activePrefix;

	public ObjectStorageReindexJobStorage(ObjectStorage storage) {
		this.client = storage.client();
		this.bucket = storage.bucket();
		this.prefix = storage.rootObject(JOBS_PATH) + "/";
		this.activePrefix = storage.rootObject(ACTIVE_PATH) + "/";
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
		/*
		 * The marker goes in before the record. An accept that fails halfway
		 * then leaves a marker with no record, which the next listing deletes,
		 * and never a running job with no marker.
		 */
		if(ReindexPhase.isPending(record)) {
			markActive(index);
		}

		var version = writeRecord(index, record, expectedVersion);

		if(version != null && ReindexPhase.isFinished(record)) {
			clearActive(index);
		}

		return version;
	}

	private String writeRecord(String index, ReindexJobStore record, String expectedVersion)
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

		clearActive(index);
	}

	/**
	 * Write the marker saying the index has a job to resume. Writing one that
	 * is already there changes nothing.
	 *
	 * @throws IOException
	 *   if the storage could not be reached
	 */
	private void markActive(String index) throws IOException {
		try {
			client.putObject(
				PutObjectRequest.builder()
					.bucket(bucket)
					.key(activeKeyOf(index))
					.contentType("application/octet-stream")
					.build(),
				RequestBody.empty()
			);
		} catch(Exception e) {
			throw new IOException(
				"Unable to record the reindex as running; " + e.getMessage(),
				e
			);
		}
	}

	/**
	 * Remove the marker of an index that has no job to resume. Best effort: a
	 * marker left behind is removed by the next {@link #listUnfinished()}.
	 */
	private void clearActive(String index) {
		deleteQuietly(activeKeyOf(index));
	}

	private void deleteQuietly(String key) {
		try {
			client.deleteObject(
				DeleteObjectRequest.builder()
					.bucket(bucket)
					.key(key)
					.build()
			);
		} catch(Exception e) {
			// The next listUnfinished() pays one read and tries again
		}
	}

	@Override
	public ListIterable<Stored> listUnfinished() throws IOException {
		var found = Lists.mutable.<Stored>empty();

		try {
			var pages = client.listObjectsV2Paginator(
				ListObjectsV2Request.builder()
					.bucket(bucket)
					.prefix(activePrefix)
					.build()
			);

			for(var page : pages) {
				for(var object : page.contents()) {
					/*
					 * The name comes off the key instead of going through
					 * IndexName, so a stray object under the prefix leads to a
					 * read that finds nothing and deletes it, instead of
					 * throwing and stopping the listing.
					 */
					var name = object.key().substring(activePrefix.length());
					var stored = readKey(prefix + name).orElse(null);

					if(stored == null || ReindexPhase.isFinished(stored.record())) {
						/*
						 * The job ended, or its record was never written.
						 * Nothing resumes it, and while the marker is there
						 * every later listing pays the same read.
						 */
						deleteQuietly(object.key());
						continue;
					}

					found.add(stored);
				}
			}
		} catch(IOException e) {
			throw e;
		} catch(Exception e) {
			throw new IOException(
				"Unable to list the running reindexes; " + e.getMessage(),
				e
			);
		}

		return found;
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

	/**
	 * The key of the marker for one index, using the same name as its record.
	 */
	private String activeKeyOf(String index) {
		return activePrefix + IndexName.of(index).toString();
	}
}
