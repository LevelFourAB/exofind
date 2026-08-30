package se.l4.exofind.engine.index.settings;

import java.io.IOException;
import java.util.Objects;

import se.l4.exofind.engine.index.state.ObjectStorageSync;
import se.l4.exofind.engine.storage.ObjectStorage;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * SearchSettingsStorage held as one object per index in the same bucket the
 * indexes live in, so it works wherever they do without needing any other
 * infrastructure.
 *
 * <p>An object is replaced conditionally on its entity tag, the same way the
 * manifests and the keys are. That is the whole of what keeps two nodes from
 * losing one another's changes: there is one object per index and no files
 * beside it, so nothing here needs the indexer role or an epoch to be safe.
 *
 * <p>The object lives under the prefix of its index - the prefix owns
 * everything about one index - but above the generations, where the sweep of
 * unreferenced generation files cannot reach it and removing a generation
 * leaves it standing.
 */
public class ObjectStorageSearchSettingsStorage implements SearchSettingsStorage {
	/**
	 * Name the settings of an index are stored under, inside its prefix. Never
	 * mistakable for a generation, whose names cannot contain a dot.
	 */
	private static final String SETTINGS_NAME = "settings.ef.bin";

	private final S3Client client;
	private final String bucket;
	private final String indexesPath;

	public ObjectStorageSearchSettingsStorage(ObjectStorage storage) {
		this.client = storage.client();
		this.bucket = storage.bucket();
		this.indexesPath = storage.indexesPath();
	}

	@Override
	public boolean isAvailable() {
		return true;
	}

	private String keyOf(String index) {
		return indexesPath + "/" + index + "/" + SETTINGS_NAME;
	}

	@Override
	public Read read(String index, String knownVersion) throws IOException {
		var request = GetObjectRequest.builder()
			.bucket(bucket)
			.key(keyOf(index));

		if(knownVersion != null) {
			request.ifNoneMatch(knownVersion);
		}

		try(var response = client.getObject(request.build())) {
			return new Read.Loaded(
				SearchSettingsStore.parseFrom(response.readAllBytes()),
				ObjectStorageSync.quoteETag(response.response().eTag())
			);
		} catch(S3Exception e) {
			if(e.statusCode() == 304) {
				return new Read.Unchanged();
			}

			if(e.statusCode() == 404) {
				return new Read.Absent();
			}

			throw new IOException("Unable to read the search settings; " + e.getMessage(), e);
		} catch(Exception e) {
			throw new IOException("Unable to read the search settings; " + e.getMessage(), e);
		}
	}

	@Override
	public String write(String index, SearchSettingsStore settings, String expectedVersion)
		throws IOException
	{
		var request = PutObjectRequest.builder()
			.bucket(bucket)
			.key(keyOf(index))
			.contentType("application/octet-stream");

		if(expectedVersion != null) {
			request.ifMatch(expectedVersion);
		} else {
			request.ifNoneMatch("*");
		}

		try {
			var response = client.putObject(
				request.build(),
				RequestBody.fromBytes(settings.toByteArray())
			);

			return ObjectStorageSync.quoteETag(response.eTag());
		} catch(S3Exception e) {
			if(ObjectStorage.isConditionalWriteLost(e)) {
				return null;
			}

			throw new IOException("Unable to write the search settings; " + e.getMessage(), e);
		} catch(Exception e) {
			/*
			 * The storage may drop the connection after it has taken the
			 * write, which arrives as a connection failure rather than as the
			 * answer to it. Read the settings back to tell what happened:
			 * exactly what was written means the write went through, a version
			 * other than the one it was conditioned on means another node got
			 * there first, and the version it was conditioned on means the
			 * write never arrived and may be made again.
			 */
			var current = tryRead(index);
			if(current != null) {
				if(current.settings().equals(settings)) {
					return current.version();
				}

				if(!Objects.equals(current.version(), expectedVersion)) {
					return null;
				}
			}

			throw new IOException("Unable to write the search settings; " + e.getMessage(), e);
		}
	}

	/**
	 * Read the settings back for telling a dropped answer apart from a write
	 * that never landed, where failing a second time only means the first
	 * failure stands.
	 *
	 * @return
	 *   what the storage holds, or {@code null} when it holds nothing or could
	 *   not be reached
	 */
	private Read.Loaded tryRead(String index) {
		try {
			return read(index, null) instanceof Read.Loaded loaded ? loaded : null;
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
			throw new IOException("Unable to remove the search settings; " + e.getMessage(), e);
		}
	}
}
