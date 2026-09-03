package se.l4.exofind.engine.index.state;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;

import com.google.protobuf.InvalidProtocolBufferException;

import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.IndexStorageHeldException;
import se.l4.exofind.engine.index.settings.ObjectStorageSearchSettingsStorage;
import se.l4.exofind.engine.logging.Log;
import se.l4.exofind.engine.storage.ObjectStorage;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * IndexRemovals over the bucket a deployment in object mode keeps everything
 * in.
 *
 * <p>A mark is one object, {@link #MARK_FILE}, directly under the prefix it
 * marks. Removing a prefix lists everything under it and deletes in batches;
 * the listing is paged and can miss an object written while it runs, which
 * a later sweep picks up as long as the mark stands.
 */
public class ObjectStorageIndexRemovals implements IndexRemovals {
	private static final Log logger = Log.of(ObjectStorageIndexRemovals.class);

	/**
	 * Most keys one delete request may name, which is what the S3 API allows.
	 */
	private static final int DELETE_BATCH = 1000;

	private final S3Client client;
	private final String bucket;
	private final ObjectStorage storage;

	public ObjectStorageIndexRemovals(ObjectStorage storage) {
		this.client = storage.client();
		this.bucket = storage.bucket();
		this.storage = storage;
	}

	/**
	 * The prefix a mark stands over, without a trailing separator.
	 */
	private String prefixOf(IndexName target) {
		return target.isPinned()
			? storage.indexPath(target)
			: storage.indexPath(target.index());
	}

	private String markKeyOf(IndexName target) {
		return prefixOf(target) + "/" + MARK_FILE;
	}

	@Override
	public void mark(IndexName target) throws IOException {
		var mark = RemovalMark.newBuilder()
			.setRemovedAt(Instant.now().toEpochMilli())
			.build();

		try {
			client.putObject(
				PutObjectRequest.builder()
					.bucket(bucket)
					.key(markKeyOf(target))
					.contentType("application/octet-stream")
					.build(),
				RequestBody.fromBytes(mark.toByteArray())
			);
		} catch(SdkException e) {
			throw new IOException(
				"Unable to mark " + target + " as removed; " + e.getMessage(), e
			);
		}
	}

	@Override
	public boolean unmark(IndexName target) throws IOException {
		if(markedAt(target).isEmpty()) {
			return false;
		}

		deleteObject(markKeyOf(target));
		return true;
	}

	@Override
	public Optional<Instant> markedAt(IndexName target) throws IOException {
		var request = GetObjectRequest.builder()
			.bucket(bucket)
			.key(markKeyOf(target))
			.build();

		try(var response = client.getObject(request)) {
			var mark = RemovalMark.parseFrom(response.readAllBytes());

			/*
			 * A mark that does not say when falls back to when the object was
			 * written, which is the same moment as far as a grace period is
			 * concerned.
			 */
			return Optional.of(
				mark.hasRemovedAt()
					? Instant.ofEpochMilli(mark.getRemovedAt())
					: response.response().lastModified()
			);
		} catch(S3Exception e) {
			if(e.statusCode() == 404) {
				return Optional.empty();
			}

			throw new IOException(
				"Unable to read the removal mark of " + target + "; " + e.getMessage(), e
			);
		} catch(InvalidProtocolBufferException e) {
			throw new IOException(
				"The removal mark of " + target + " can not be read; " + e.getMessage(), e
			);
		} catch(SdkException e) {
			throw new IOException(
				"Unable to read the removal mark of " + target + "; " + e.getMessage(), e
			);
		}
	}

	@Override
	public ListIterable<Mark> listMarks(Predicate<IndexName> wanted) throws IOException {
		var marks = Lists.mutable.<Mark>empty();
		var indexesPrefix = storage.indexesPath() + "/";

		try {
			for(var index : listPrefixes(indexesPrefix)) {
				if(!IndexName.VALID_INDEX_PATTERN.matcher(index).matches()) {
					continue;
				}

				var whole = IndexName.of(index);
				if(wanted.test(whole) && readMark(whole, marks)) {
					// The whole index goes, so its generations need no look
					continue;
				}

				for(var generation : listPrefixes(storage.indexPath(index) + "/")) {
					if(!IndexName.VALID_GENERATION_PATTERN.matcher(generation).matches()) {
						continue;
					}

					var target = IndexName.of(index, generation);
					if(wanted.test(target)) {
						readMark(target, marks);
					}
				}
			}
		} catch(SdkException e) {
			throw new IOException("Unable to list the indexes; " + e.getMessage(), e);
		}

		return marks;
	}

	/**
	 * Read one mark into the list, when there is one. A mark that can not be
	 * read is passed over and logged rather than failing the listing: it
	 * keeps its prefix from being removed, which is the safe side, and the
	 * warning says where to look.
	 *
	 * @return
	 *   whether a mark was found
	 */
	private boolean readMark(IndexName target, MutableList<Mark> marks) {
		try {
			var removedAt = markedAt(target);
			if(removedAt.isEmpty()) {
				return false;
			}

			marks.add(new Mark(target, removedAt.get()));
			return true;
		} catch(IOException e) {
			logger.atWarn()
				.addKeyValue("index", target.toString())
				.setCause(e)
				.log("Could not read a removal mark, leaving its prefix alone; " + e.getMessage());

			return false;
		}
	}

	@Override
	public boolean remove(IndexName target) throws IOException {
		var markKey = markKeyOf(target);
		var keys = listKeys(prefixOf(target) + "/", markKey);

		for(var batch : batches(keys)) {
			if(!exists(markKey)) {
				logger.atInfo()
					.addKeyValue("index", target.toString())
					.log("Removal mark is gone, stopping the removal");

				return false;
			}

			deleteObjects(batch);
		}

		deleteObject(markKey);
		return true;
	}

	@Override
	public void prepareForIndex(String index) throws IOException {
		var whole = IndexName.of(index);
		if(markedAt(whole).isPresent()) {
			clear(whole);
		}
	}

	@Override
	public void prepareForGeneration(IndexName generation) throws IOException {
		if(markedAt(generation).isPresent()) {
			clear(generation);
			return;
		}

		if(exists(storage.indexPath(generation) + "/" + LocalCopy.MANIFEST_FILE)) {
			throw new IndexStorageHeldException(generation);
		}
	}

	/**
	 * Remove a marked prefix for a creation, taking the mark out as soon as
	 * nothing under it can be served - so that a sweep that is removing the
	 * same prefix stops at its next batch rather than run on next to the new
	 * generation.
	 */
	private void clear(IndexName target) throws IOException {
		var markKey = markKeyOf(target);
		var keys = listKeys(prefixOf(target) + "/", markKey);

		var served = keys.select(ObjectStorageIndexRemovals::isServed);
		var rest = keys.reject(ObjectStorageIndexRemovals::isServed);

		for(var batch : batches(served)) {
			deleteObjects(batch);
		}

		deleteObject(markKey);

		for(var batch : batches(rest)) {
			deleteObjects(batch);
		}

		logger.atInfo()
			.addKeyValue("index", target.toString())
			.addKeyValue("objects", keys.size())
			.log("Removed what a deleted index left in the storage, ahead of creating it again");
	}

	/**
	 * Whether an object is one a node serves from or a repair registers -
	 * a manifest or the settings - rather than a file they refer to. These
	 * go first, so that an interrupted removal leaves nothing behind that
	 * reads as an index.
	 */
	private static boolean isServed(String key) {
		return key.endsWith("/" + LocalCopy.MANIFEST_FILE)
			|| key.endsWith("/" + ObjectStorageSearchSettingsStorage.SETTINGS_NAME);
	}

	/**
	 * Every key under a prefix except the mark, served objects first.
	 */
	private MutableList<String> listKeys(String prefix, String markKey) throws IOException {
		var keys = Lists.mutable.<String>empty();

		try {
			var pages = client.listObjectsV2Paginator(
				ListObjectsV2Request.builder()
					.bucket(bucket)
					.prefix(prefix)
					.build()
			);

			for(var page : pages) {
				for(var object : page.contents()) {
					if(!object.key().equals(markKey)) {
						keys.add(object.key());
					}
				}
			}
		} catch(SdkException e) {
			throw new IOException("Unable to list " + prefix + "; " + e.getMessage(), e);
		}

		return keys.select(ObjectStorageIndexRemovals::isServed)
			.withAll(keys.reject(ObjectStorageIndexRemovals::isServed));
	}

	/**
	 * The names directly under a prefix, read off a delimited listing.
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

	private static List<List<String>> batches(ListIterable<String> keys) {
		var batches = new ArrayList<List<String>>();
		var all = keys.toList();

		for(int i = 0; i < all.size(); i += DELETE_BATCH) {
			batches.add(all.subList(i, Math.min(i + DELETE_BATCH, all.size())));
		}

		return batches;
	}

	private boolean exists(String key) throws IOException {
		try {
			client.headObject(
				HeadObjectRequest.builder()
					.bucket(bucket)
					.key(key)
					.build()
			);

			return true;
		} catch(S3Exception e) {
			if(e.statusCode() == 404) {
				return false;
			}

			throw new IOException("Unable to read " + key + "; " + e.getMessage(), e);
		} catch(SdkException e) {
			throw new IOException("Unable to read " + key + "; " + e.getMessage(), e);
		}
	}

	private void deleteObject(String key) throws IOException {
		try {
			client.deleteObject(
				DeleteObjectRequest.builder()
					.bucket(bucket)
					.key(key)
					.build()
			);
		} catch(SdkException e) {
			throw new IOException("Unable to remove " + key + "; " + e.getMessage(), e);
		}
	}

	private void deleteObjects(List<String> keys) throws IOException {
		var identifiers = keys.stream()
			.map(key -> ObjectIdentifier.builder().key(key).build())
			.toList();

		try {
			var response = client.deleteObjects(
				DeleteObjectsRequest.builder()
					.bucket(bucket)
					.delete(Delete.builder().objects(identifiers).quiet(true).build())
					.build()
			);

			if(response.hasErrors() && !response.errors().isEmpty()) {
				var first = response.errors().getFirst();
				throw new IOException(
					"Unable to remove " + response.errors().size() + " objects, first is "
						+ first.key() + "; " + first.message()
				);
			}
		} catch(SdkException e) {
			throw new IOException("Unable to remove objects; " + e.getMessage(), e);
		}
	}
}
