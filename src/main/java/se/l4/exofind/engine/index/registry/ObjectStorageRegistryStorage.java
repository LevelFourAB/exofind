package se.l4.exofind.engine.index.registry;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;

import com.google.protobuf.InvalidProtocolBufferException;

import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.state.IndexRemovals;
import se.l4.exofind.engine.index.state.LocalCopy;
import se.l4.exofind.engine.index.state.ObjectStorageIndexRemovals;
import se.l4.exofind.engine.index.state.ObjectStorageSync;
import se.l4.exofind.engine.storage.ObjectStorage;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
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
	private final ObjectStorage storage;
	private final IndexRemovals removals;

	public ObjectStorageRegistryStorage(ObjectStorage storage) {
		this.client = storage.client();
		this.bucket = storage.bucket();
		this.key = storage.rootObject(REGISTRY_NAME);
		this.storage = storage;
		this.removals = new ObjectStorageIndexRemovals(storage);
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
	 * {@inheritDoc}
	 *
	 * <p>Uses the same rules as {@link ObjectStorageRegistryAudit#repair}, so
	 * that storage that this finds is storage that a repair registers. A
	 * bucket that holds no index costs one listing. The search stops at the
	 * first generation it finds.
	 */
	@Override
	public Optional<IndexName> findRegistrableGeneration() throws IOException {
		var indexesPrefix = storage.indexesPath() + "/";

		try {
			for(var index : listPrefixes(indexesPrefix)) {
				if(!IndexName.VALID_INDEX_PATTERN.matcher(index).matches()) {
					continue;
				}

				var generations = listPrefixes(indexesPrefix + index + "/")
					.select(name -> IndexName.VALID_GENERATION_PATTERN.matcher(name).matches());

				if(generations.isEmpty() || removals.markedAt(IndexName.of(index)).isPresent()) {
					continue;
				}

				for(var generation : generations) {
					var name = IndexName.of(index, generation);
					if(hasManifest(name) && removals.markedAt(name).isEmpty()) {
						return Optional.of(name);
					}
				}
			}
		} catch(SdkException e) {
			throw new IOException(
				"Unable to list the indexes in the storage; " + e.getMessage(),
				e
			);
		}

		return Optional.empty();
	}

	/**
	 * The names directly under a prefix, read from a delimited listing.
	 */
	private MutableList<String> listPrefixes(String prefix) {
		var names = Lists.mutable.<String>empty();

		var pages = client.listObjectsV2Paginator(
			ListObjectsV2Request.builder()
				.bucket(bucket)
				.prefix(prefix)
				.delimiter("/")
				.build()
		);

		for(var page : pages) {
			for(var common : page.commonPrefixes()) {
				var name = common.prefix();
				names.add(name.substring(prefix.length(), name.length() - 1));
			}
		}

		return names;
	}

	private boolean hasManifest(IndexName generation) {
		try {
			client.headObject(
				HeadObjectRequest.builder()
					.bucket(bucket)
					.key(storage.indexPath(generation) + "/" + LocalCopy.MANIFEST_FILE)
					.build()
			);

			return true;
		} catch(S3Exception e) {
			if(e.statusCode() == 404) {
				return false;
			}

			throw e;
		}
	}

	/**
	 * Read the registry back to find out what became of a write whose
	 * answer does not tell. Exactly what was written means the write went
	 * through, and the version it was conditioned on means the write never
	 * arrived and may be made again.
	 *
	 * <p>Anything else does not tell. Another node may have written the
	 * registry after this write landed, or instead of it, and a registry that
	 * cannot be read back says nothing. Such a write is reported as
	 * {@link RegistryWriteUnknownException}, so that the caller looks for its
	 * own change in the registry instead of taking the write as refused.
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
	 *   through
	 * @throws RegistryWriteUnknownException
	 *   if the registry read back does not tell whether the write landed
	 * @throws IOException
	 *   if the write did not arrive
	 */
	private String resolveUnanswered(
		IndexRegistryStore indexes,
		String expectedVersion,
		Exception failure
	) throws IOException {
		Read current;
		try {
			current = read(null);
		} catch(IOException e) {
			failure.addSuppressed(e);
			throw unknown(failure);
		}

		if(current instanceof Read.Loaded loaded && loaded.indexes().equals(indexes)) {
			return loaded.version();
		}

		if(neverArrived(current, expectedVersion)) {
			throw new IOException(
				"Unable to write the index registry; " + failure.getMessage(),
				failure
			);
		}

		throw unknown(failure);
	}

	/**
	 * Whether the registry read back is still what the write was conditioned
	 * on, so that the write did not land.
	 */
	private static boolean neverArrived(Read current, String expectedVersion) {
		return switch(current) {
			case Read.Loaded loaded -> Objects.equals(loaded.version(), expectedVersion);
			case Read.Corrupt corrupt -> Objects.equals(corrupt.version(), expectedVersion);
			case Read.Absent absent -> expectedVersion == null;
			case Read.Unchanged unchanged -> false;
		};
	}

	private static RegistryWriteUnknownException unknown(Exception failure) {
		return new RegistryWriteUnknownException(
			"The index registry was written without an answer, and reading it back does not tell"
				+ " whether the write landed; " + failure.getMessage(),
			failure
		);
	}
}
