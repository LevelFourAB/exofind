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
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * IndexRemovals over the bucket a deployment in object mode keeps everything
 * in.
 *
 * <p>A mark is one object, {@link #MARK_FILE}, directly under the prefix it
 * marks. Removing a prefix lists everything under it and deletes in batches;
 * the listing is paged and can miss an object written while it runs, which
 * a later sweep picks up as long as the mark stands.
 *
 * <p>A sweep runs next to nodes that can create the name again, and delete
 * it again, between any two of its requests. A new index writes some keys
 * the old one used, such as its manifest. So a sweep removes an object only
 * while its ETag is the one the listing saw, and removes the mark only while
 * its ETag is the one the sweep started from. A storage that ignores these
 * conditions removes unconditionally. An object written again with the same
 * contents keeps its ETag, so a recreate that writes settings identical to
 * the old ones between a check and a batch can still lose them.
 */
public class ObjectStorageIndexRemovals implements IndexRemovals {
	private static final Log logger = Log.of(ObjectStorageIndexRemovals.class);

	/**
	 * Most keys one delete request may name, which is what the S3 API allows.
	 */
	private static final int DELETE_BATCH = 1000;

	/**
	 * Error code of an object a batch delete kept because its condition did
	 * not hold.
	 */
	private static final String PRECONDITION_FAILED = "PreconditionFailed";

	/**
	 * Error code of an object a batch delete did not find.
	 */
	private static final String NO_SUCH_KEY = "NoSuchKey";

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
		var markVersion = versionOf(markKey);
		if(markVersion.isEmpty()) {
			return false;
		}

		var objects = listObjects(prefixOf(target) + "/", markKey);

		for(var batch : batches(objects)) {
			if(!markVersion.equals(versionOf(markKey))) {
				logger.atInfo()
					.addKeyValue("index", target.toString())
					.log("Removal mark is gone or replaced, stopping the removal");

				return false;
			}

			if(!deleteObjects(batch, true)) {
				logger.atInfo()
					.addKeyValue("index", target.toString())
					.log("Objects were written again after the listing, stopping the removal");

				return false;
			}
		}

		return deleteMark(target, markKey, markVersion.get());
	}

	/**
	 * Remove the mark a sweep started from. A mark with another ETag was
	 * written by a later delete, and it is the only thing that says the
	 * objects of that delete go, so it stays.
	 *
	 * @return
	 *   whether the mark was removed
	 */
	private boolean deleteMark(IndexName target, String markKey, String version) throws IOException {
		try {
			client.deleteObject(
				DeleteObjectRequest.builder()
					.bucket(bucket)
					.key(markKey)
					.ifMatch(version)
					.build()
			);

			return true;
		} catch(S3Exception e) {
			if(e.statusCode() == 404 || e.statusCode() == 412) {
				logger.atInfo()
					.addKeyValue("index", target.toString())
					.log("Removal mark is gone or replaced, leaving it");

				return false;
			}

			throw new IOException("Unable to remove " + markKey + "; " + e.getMessage(), e);
		} catch(SdkException e) {
			throw new IOException("Unable to remove " + markKey + "; " + e.getMessage(), e);
		}
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
		var objects = listObjects(prefixOf(target) + "/", markKey);

		var served = objects.select(ObjectStorageIndexRemovals::isServed);
		var rest = objects.reject(ObjectStorageIndexRemovals::isServed);

		for(var batch : batches(served)) {
			deleteObjects(batch, false);
		}

		deleteObject(markKey);

		for(var batch : batches(rest)) {
			deleteObjects(batch, false);
		}

		logger.atInfo()
			.addKeyValue("index", target.toString())
			.addKeyValue("objects", objects.size())
			.log("Removed what a deleted index left in the storage, ahead of creating it again");
	}

	/**
	 * Whether an object is one a node serves from or a repair registers -
	 * a manifest or the settings - rather than a file they refer to. These
	 * go first, so that an interrupted removal leaves nothing behind that
	 * reads as an index.
	 */
	private static boolean isServed(S3Object object) {
		var key = object.key();
		return key.endsWith("/" + LocalCopy.MANIFEST_FILE)
			|| key.endsWith("/" + ObjectStorageSearchSettingsStorage.SETTINGS_NAME);
	}

	/**
	 * Every object under a prefix except the mark, served objects first.
	 */
	private MutableList<S3Object> listObjects(String prefix, String markKey) throws IOException {
		var objects = Lists.mutable.<S3Object>empty();

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
						objects.add(object);
					}
				}
			}
		} catch(SdkException e) {
			throw new IOException("Unable to list " + prefix + "; " + e.getMessage(), e);
		}

		return objects.select(ObjectStorageIndexRemovals::isServed)
			.withAll(objects.reject(ObjectStorageIndexRemovals::isServed));
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

	private static <T> List<List<T>> batches(ListIterable<T> items) {
		var batches = new ArrayList<List<T>>();
		var all = items.toList();

		for(int i = 0; i < all.size(); i += DELETE_BATCH) {
			batches.add(all.subList(i, Math.min(i + DELETE_BATCH, all.size())));
		}

		return batches;
	}

	/**
	 * The version of an object, as a conditional request names it, or empty
	 * when the object does not exist.
	 */
	private Optional<String> versionOf(String key) throws IOException {
		try {
			var response = client.headObject(
				HeadObjectRequest.builder()
					.bucket(bucket)
					.key(key)
					.build()
			);

			return Optional.ofNullable(ObjectStorageSync.quoteETag(response.eTag()));
		} catch(S3Exception e) {
			if(e.statusCode() == 404) {
				return Optional.empty();
			}

			throw new IOException("Unable to read " + key + "; " + e.getMessage(), e);
		} catch(SdkException e) {
			throw new IOException("Unable to read " + key + "; " + e.getMessage(), e);
		}
	}

	private boolean exists(String key) throws IOException {
		return versionOf(key).isPresent();
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

	/**
	 * Remove a batch of listed objects.
	 *
	 * @param asListed
	 *   whether to remove each object only while its ETag is the one the
	 *   listing saw, so that an object written again since stays
	 * @return
	 *   whether every object went, {@code false} when the storage kept one
	 *   because it was written again since the listing
	 */
	private boolean deleteObjects(List<S3Object> objects, boolean asListed) throws IOException {
		var identifiers = objects.stream()
			.map(object -> {
				var identifier = ObjectIdentifier.builder().key(object.key());
				if(asListed) {
					identifier.eTag(object.eTag());
				}

				return identifier.build();
			})
			.toList();

		try {
			var response = client.deleteObjects(
				DeleteObjectsRequest.builder()
					.bucket(bucket)
					.delete(Delete.builder().objects(identifiers).quiet(true).build())
					.build()
			);

			if(!response.hasErrors()) {
				return true;
			}

			var kept = false;
			var failed = Lists.mutable.<S3Error>empty();
			for(var error : response.errors()) {
				switch(error.code()) {
					case PRECONDITION_FAILED -> kept = true;
					case NO_SUCH_KEY -> {
						// Gone already, which is what was asked for
					}
					default -> failed.add(error);
				}
			}

			if(!failed.isEmpty()) {
				var first = failed.getFirst();
				throw new IOException(
					"Unable to remove " + failed.size() + " objects, first is "
						+ first.key() + "; " + first.message()
				);
			}

			return !kept;
		} catch(SdkException e) {
			throw new IOException("Unable to remove objects; " + e.getMessage(), e);
		}
	}
}
