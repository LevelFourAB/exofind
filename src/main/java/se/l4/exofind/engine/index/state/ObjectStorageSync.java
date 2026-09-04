package se.l4.exofind.engine.index.state;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32C;

import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.IOUtils;

import se.l4.exofind.engine.index.LuceneCompatibility;
import se.l4.exofind.engine.logging.Log;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * ObjectStorageSync is a {@link StateSync} implementation that synchronizes
 * the state of a directory with an S3 compatible object storage.
 */
public class ObjectStorageSync implements StateSync {
	private static final Log logger = Log.of(ObjectStorageSync.class);

	private static final String MANIFEST_NAME = LocalCopy.MANIFEST_FILE;

	/**
	 * Name the manifest is written under before being moved into place. The
	 * manifest is only ever replaced by a rename of a file already on disk, so
	 * neither a process that stops nor a machine that loses power can leave a
	 * half written one under the name the local copy is read from.
	 */
	private static final String MANIFEST_TEMP_NAME = MANIFEST_NAME + ".tmp";

	/**
	 * Suffix used while a file is being downloaded. The suffix is distinct
	 * enough that it can never collide with a file belonging to the index,
	 * which lets a leftover from an interrupted download be recognized as
	 * garbage.
	 */
	private static final String DOWNLOAD_SUFFIX = ".download.tmp";

	/**
	 * Number of times a request to the object storage is attempted before it is
	 * turned into an error.
	 */
	private static final int MAX_ATTEMPTS = 3;

	/**
	 * Time to wait before the second attempt at a request, doubled for every
	 * attempt after that.
	 */
	private static final Duration RETRY_DELAY = Duration.ofMillis(100);

	/**
	 * How old an unreferenced remote object has to be before the sweep may
	 * remove it, which doubles as how often the sweep is worth running. A
	 * younger object may belong to a push that is still underway.
	 */
	private static final Duration DEFAULT_ORPHAN_GRACE = Duration.ofHours(1);

	/**
	 * Errors that describe a temporary condition in the object storage, such as
	 * throttling or a node being replaced. The same request may very well
	 * succeed a moment later.
	 */
	private static final Set<String> TRANSIENT_ERROR_CODES = Set.of(
		"InternalError",
		"RequestTimeout",
		"ServiceUnavailable",
		"SlowDown"
	);

	private final S3Client client;

	private final String index;
	private final Path localPath;
	private final String remoteBucket;
	private final String remotePrefix;

	private final Lock lock;

	/**
	 * Objects that are no longer referenced by any manifest but that could not
	 * be removed from the remote yet. Removal is retried on the next push.
	 */
	private final Set<String> pendingRemoteDeletes;

	private final Duration orphanGrace;

	/*
	 * Replaced only while the lock is held, but read without it by
	 * luceneCreatedMajor, so that asking for the status of an index does not
	 * wait behind a push.
	 */
	private volatile Manifest lastSyncedManifest;

	/**
	 * Tag the remote manifest carried when this node last saw it, always in
	 * quoted form. Replacing the manifest is conditional on the tag still
	 * matching, which is what makes two nodes pushing at the same time resolve
	 * to one winner. {@code null} when the remote has not been seen since this
	 * instance was created, or is believed to hold no manifest at all.
	 */
	private String lastSyncedManifestETag;

	/**
	 * Epoch this instance has claimed for its writes, or {@code -1} while it
	 * has not written anything yet. Claimed once, before the first upload.
	 */
	private long sessionEpoch;

	/**
	 * When the sweep for orphaned remote objects last ran, seeded at a point
	 * inside the grace period for an instance that has not swept yet.
	 */
	private long lastSweepNanos;

	public ObjectStorageSync(
		S3Client client,
		String index,
		Path localPath,
		String remoteBucket,
		String remotePrefix
	) {
		this(client, index, localPath, remoteBucket, remotePrefix, DEFAULT_ORPHAN_GRACE);
	}

	public ObjectStorageSync(
		S3Client client,
		String index,
		Path localPath,
		String remoteBucket,
		String remotePrefix,
		Duration orphanGrace
	) {
		this.client = client;
		this.index = index;
		this.localPath = localPath;
		this.remoteBucket = remoteBucket;
		this.remotePrefix = remotePrefix;
		this.orphanGrace = orphanGrace;

		this.lock = new ReentrantLock();
		this.pendingRemoteDeletes = new LinkedHashSet<>();

		this.lastSyncedManifest = loadFromDisk();
		this.lastSyncedManifestETag = null;
		this.sessionEpoch = -1;
		this.lastSweepNanos = System.nanoTime() - startingSweepAge(orphanGrace);
	}

	/**
	 * How long ago a new instance counts its last sweep as having run.
	 *
	 * <p>The point is picked at random inside the grace period, so the first
	 * sweeps of the indexes a node holds fall at different times. Treating a
	 * new instance as never having swept puts all of them on the first push
	 * after the node starts, and a node holding hundreds of indexes then
	 * lists every one of them at once. Nothing is lost by waiting: a sweep
	 * may only remove objects older than the grace period.
	 *
	 * @param orphanGrace
	 *   how long an unreferenced object is left alone
	 * @return
	 *   the age in nanoseconds, and zero for a grace period that is not
	 *   positive, where every push sweeps anyway
	 */
	private static long startingSweepAge(Duration orphanGrace) {
		var nanos = orphanGrace.toNanos();
		return nanos > 0 ? ThreadLocalRandom.current().nextLong(nanos) : 0;
	}

	private Manifest loadFromDisk() {
		if(!Files.exists(localPath.resolve(MANIFEST_NAME))) {
			return Manifest.getDefaultInstance();
		}

		try(var in = Files.newInputStream(localPath.resolve(MANIFEST_NAME))) {
			return Manifest.parseFrom(in);
		} catch(IOException e) {
			logger.atWarn()
				.addKeyValue("index", index)
				.setCause(e)
				.log("Could not load manifest from disk");

			return Manifest.getDefaultInstance();
		}
	}

	@Override
	public void push(Set<String> files) throws IOException {
		lock.lock();
		try {
			var previousManifest = lastSyncedManifest;
			var manifest = createManifest(files, previousManifest);

			/*
			 * The manifest describes everything that is synchronized, so it is
			 * what decides whether there is anything to do. The segment number
			 * only moves when Lucene commits, and a definition can be replaced
			 * without that happening.
			 */
			if(manifest.getFilesList().equals(previousManifest.getFilesList())) {
				logger.atInfo()
					.addKeyValue("index", index)
					.addKeyValue("version", previousManifest.getVersion())
					.log("No changes to push");

				return;
			}

			/*
			 * Confirm the remote manifest is still the one this push builds on
			 * before anything is uploaded. The conditional write of the
			 * manifest is what actually decides the push, but that happens
			 * last - checking first keeps a node that has already lost from
			 * uploading files nobody will reference.
			 *
			 * What comes back is what the remote is known to hold, which is
			 * what the uploads below skip against. It is not the baseline this
			 * push was built from when the remote no longer holds a manifest:
			 * there is nothing left to have uploaded the files to, whatever
			 * this node last synchronized.
			 */
			var baseline = ensurePushBaseline(previousManifest);

			/*
			 * The epoch is claimed before the first upload of this session, so
			 * a node whose claim is refused never uploads at all, and one
			 * whose claim went through writes under a prefix no other session
			 * can be writing to.
			 */
			if(sessionEpoch < 0) {
				claimEpoch(baseline);
			}

			manifest = manifest.toBuilder()
				.setVersion(lastSyncedManifest.getVersion() + 1)
				.build();

			logger.atInfo()
				.addKeyValue("index", index)
				.addKeyValue("latestSegment", manifest.getLatestSegment())
				.addKeyValue("version", manifest.getVersion())
				.addKeyValue("epoch", manifest.getEpoch())
				.log("Pushing changes to remote");

			var currentFiles = new HashMap<String, ManifestFile>();
			for(var file : baseline.getFilesList()) {
				currentFiles.put(file.getName(), file);
			}

			// Upload all the changed files
			for(var file : manifest.getFilesList()) {
				if(isUnchanged(currentFiles.get(file.getName()), file)) {
					continue;
				}

				logger.atInfo()
					.addKeyValue("index", index)
					.addKeyValue("file", file.getName())
					.addKeyValue("key", keyOf(file))
					.log("Uploading file");

				var localFileName = localPath.resolve(file.getName());
				putObject(
					remotePrefix + "/" + keyOf(file),
					file.getSize(),
					() -> Files.newInputStream(localFileName)
				);
			}

			/*
			 * The remote manifest is what makes the uploaded files visible to
			 * other nodes, so it is uploaded before the local copy is updated.
			 * Writing the local manifest first would make an interrupted push
			 * look complete after a restart, leaving the remote permanently
			 * behind.
			 */
			putManifest(manifest);

			writeManifest(manifest);
			this.lastSyncedManifest = manifest;

			deleteObsoleteRemoteObjects(baseline, manifest);
			maybeSweepRemote(manifest);
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Claim the epoch this session writes under, by rewriting the manifest
	 * with the next one. The claim is conditional like any manifest write, so
	 * when two nodes race for it one is refused - and since a session claims
	 * before its first upload, the loser walks away without having overwritten
	 * anything at all.
	 *
	 * @param baseline
	 *   what the remote is known to hold, which is what the claim is written
	 *   on top of. Naming files the remote does not have would publish a
	 *   manifest a reader cannot follow for as long as the claim stands
	 * @throws SyncConflictException
	 *   if another node changed the remote manifest first
	 * @throws IOException
	 *   if the claim could not be written
	 */
	private void claimEpoch(Manifest baseline) throws IOException {
		var claimed = baseline.toBuilder()
			.setVersion(baseline.getVersion() + 1)
			.setEpoch(baseline.getEpoch() + 1)
			.build();

		logger.atInfo()
			.addKeyValue("index", index)
			.addKeyValue("epoch", claimed.getEpoch())
			.addKeyValue("version", claimed.getVersion())
			.log("Claiming writer epoch");

		putManifest(claimed);

		writeManifest(claimed);
		this.lastSyncedManifest = claimed;
		this.sessionEpoch = claimed.getEpoch();
	}

	@Override
	public boolean pull() throws IOException {
		lock.lock();
		try {
			/*
			 * Pull the latest manifest and compare it with the last synced one.
			 * It is kept in memory until every file it references has been
			 * downloaded, the copy on disk has to keep describing what is
			 * actually present locally.
			 */
			var requestBuilder = GetObjectRequest.builder()
				.bucket(remoteBucket)
				.key(remotePrefix + "/" + MANIFEST_NAME);

			if(lastSyncedManifestETag != null) {
				requestBuilder.ifNoneMatch(lastSyncedManifestETag);
			}

			var pulled = getObject(
				requestBuilder.build(),
				response -> new PulledManifest(
					Manifest.parseFrom(response.readAllBytes()),
					response.response().eTag()
				)
			);

			if(pulled == null) {
				// Nothing to do, manifest does not exist or is up to date
				return false;
			}

			var manifest = pulled.manifest();

			if(manifest.equals(lastSyncedManifest)) {
				logger.atDebug()
					.addKeyValue("index", index)
					.addKeyValue("version", manifest.getVersion())
					.log("Manifest is up to date");

				// Remember the tag so the next poll can be answered with a 304
				this.lastSyncedManifestETag = quoteETag(pulled.eTag());
				return false;
			}

			/*
			 * Checked before anything is downloaded. The files of an index this
			 * build can not open are of no use here, and the manifest is enough
			 * to tell, so the transfer is skipped along with the pull.
			 */
			if(
				manifest.hasLuceneCreatedMajor()
					&& !LuceneCompatibility.of(manifest.getLuceneCreatedMajor()).isReadable()
			) {
				throw new SyncIncompatibleException(
					"Index was created with Lucene "
						+ manifest.getLuceneCreatedMajor()
						+ ".x, which this build can no longer read",
					manifest.getLuceneCreatedMajor()
				);
			}

			logger.atInfo()
				.addKeyValue("index", index)
				.addKeyValue("remoteVersion", manifest.getVersion())
				.addKeyValue("localVersion", lastSyncedManifest.getVersion())
				.log("Index updates are available, synchronizing");

			var currentFiles = new HashMap<String, ManifestFile>();
			for(var file : lastSyncedManifest.getFilesList()) {
				currentFiles.put(file.getName(), file);
			}

			var renamedInto = new LinkedHashSet<Path>();
			for(var file : manifest.getFilesList()) {
				var localFile = resolveLocal(file.getName());

				if(
					isUnchanged(currentFiles.get(file.getName()), file)
						&& Files.exists(localFile)
				) {
					continue;
				}

				downloadFile(file, localFile);
				renamedInto.add(localFile.getParent());
			}

			/*
			 * The names the downloads were moved to are made to survive a power
			 * loss before the manifest that lists them is, so that the two can
			 * never come back in the other order. Each file reached the disk as
			 * it was downloaded; what is left is the renames, which live in the
			 * directories rather than in the files.
			 */
			for(var directory : renamedInto) {
				IOUtils.fsync(directory, true);
			}

			/*
			 * Every file is in place, so the manifest now describes the local
			 * directory. It is written before anything is removed, a file that
			 * the manifest does not mention can always be downloaded again
			 * while a file the manifest claims is present cannot.
			 */
			writeManifest(manifest);
			this.lastSyncedManifest = manifest;
			this.lastSyncedManifestETag = quoteETag(pulled.eTag());

			/*
			 * The pulled state was written by someone else, who may have
			 * carried files of this session's epoch forward into it. Writing
			 * more under that epoch could replace a file the adopted manifest
			 * references, so the next push claims a fresh one.
			 */
			this.sessionEpoch = -1;

			deleteObsoleteLocalFiles(manifest);
		} finally {
			lock.unlock();
		}

		return true;
	}

	@Override
	public OptionalLong syncedVersion() {
		var manifest = lastSyncedManifest;

		return manifest.hasVersion()
			? OptionalLong.of(manifest.getVersion())
			: OptionalLong.empty();
	}

	@Override
	public OptionalInt luceneCreatedMajor() {
		var manifest = lastSyncedManifest;

		return manifest.hasLuceneCreatedMajor()
			? OptionalInt.of(manifest.getLuceneCreatedMajor())
			: OptionalInt.empty();
	}

	/**
	 * The manifest as it was pulled from the remote, together with the tag it
	 * was served under.
	 */
	private record PulledManifest(Manifest manifest, String eTag) {
	}

	/**
	 * Fetch the manifest the remote holds right now.
	 *
	 * @return
	 *   the manifest and its tag, or {@code null} when the remote holds none
	 * @throws IOException
	 */
	private PulledManifest fetchRemoteManifest() throws IOException {
		return getObject(
			GetObjectRequest.builder()
				.bucket(remoteBucket)
				.key(remotePrefix + "/" + MANIFEST_NAME)
				.build(),
			response -> new PulledManifest(
				Manifest.parseFrom(response.readAllBytes()),
				response.response().eTag()
			)
		);
	}

	/**
	 * Make sure the remote manifest is still the one a push builds on, and say
	 * what the remote is known to hold.
	 *
	 * @param baseline
	 *   manifest describing what this node believes the remote holds
	 * @return
	 *   {@code baseline} while the remote still holds it, and a manifest
	 *   listing no files when the remote holds no manifest at all
	 * @throws SyncConflictException
	 *   if the remote holds a manifest this node has never synchronized
	 * @throws IOException
	 *   if the remote could not be asked
	 */
	private Manifest ensurePushBaseline(Manifest baseline) throws IOException {
		if(lastSyncedManifestETag == null) {
			/*
			 * This node has not seen the remote manifest since it started.
			 * Fetching what is there is what tells a first write apart from a
			 * manifest another node put there, and a restarted node finding
			 * its own last push recovers the tag to keep pushing under.
			 */
			var pulled = fetchRemoteManifest();

			if(pulled == null) {
				// The remote holds no manifest, the push is the first write
				return emptyBaseline(baseline);
			}

			if(pulled.manifest().equals(baseline)) {
				this.lastSyncedManifestETag = quoteETag(pulled.eTag());
				return baseline;
			}

			throw new SyncConflictException(
				"Remote manifest for " + index + " is at version "
					+ pulled.manifest().getVersion()
					+ " which this node has never synchronized; pull before pushing"
			);
		}

		var etag = statManifestETag();
		if(etag == null) {
			/*
			 * The manifest this node synchronized against is gone from the
			 * remote. There is nothing left to overwrite, so the push goes on
			 * as a first write and recreates it.
			 */
			this.lastSyncedManifestETag = null;
			return emptyBaseline(baseline);
		}

		if(!etag.equals(lastSyncedManifestETag)) {
			throw new SyncConflictException(
				"Remote manifest for " + index
					+ " was replaced by another node; pull before pushing"
			);
		}

		return baseline;
	}

	/**
	 * What a push builds on when the remote holds no manifest. Nothing is
	 * known to be there, so every file is uploaded rather than skipped for
	 * matching what this node last synchronized - a manifest listing files
	 * that were never uploaded is one no reader can ever follow, and only
	 * whoever removed the manifest could tell that the objects went with it.
	 *
	 * <p>The version and the epoch carry over rather than starting again. A
	 * version that went backwards would leave readers skipping the manifest
	 * for standing below the one they were told about, and an epoch reused
	 * from an earlier session would write the keys of files that session
	 * uploaded.
	 */
	private static Manifest emptyBaseline(Manifest baseline) {
		return baseline.toBuilder()
			.clearFiles()
			.build();
	}

	/**
	 * Write the manifest to the remote, conditionally on the remote still
	 * holding what this node last synchronized. A node that has seen the
	 * remote manifest demands that exact version still be there, one that
	 * knows of none demands there be none - either way the storage decides in
	 * the write itself, which no check beforehand can.
	 *
	 * @param manifest
	 * @throws SyncConflictException
	 *   if the remote manifest changed since this node last saw it
	 * @throws IOException
	 *   if the manifest could not be written
	 */
	private void putManifest(Manifest manifest) throws IOException {
		var bytes = manifest.toByteArray();

		var requestBuilder = PutObjectRequest.builder()
			.bucket(remoteBucket)
			.key(remotePrefix + "/" + MANIFEST_NAME)
			.contentType("application/octet-stream");

		if(lastSyncedManifestETag != null) {
			requestBuilder.ifMatch(lastSyncedManifestETag);
		} else {
			requestBuilder.ifNoneMatch("*");
		}

		var request = requestBuilder.build();

		var etag = withRetries("upload", MANIFEST_NAME, () -> {
			try {
				var response = client.putObject(request, RequestBody.fromBytes(bytes));

				return quoteETag(response.eTag());
			} catch(S3Exception e) {
				if(e.statusCode() == 412) {
					return resolveRefusedManifest(manifest);
				}

				/*
				 * A storage that could not decide the condition - the conflict
				 * it reports rather than the refusal, see
				 * ObjectStorage.isConditionalWriteLost - is left to the retries.
				 * Concluding a conflict here is what costs this node the
				 * documents it has not pushed, and a write that did not happen
				 * is no reason to give them up: the attempt that follows is
				 * refused outright if another writer really did take over.
				 */
				throw e;
			}
		});

		this.lastSyncedManifestETag = etag;
	}

	/**
	 * Work out what a refused conditional write of the manifest means. A write
	 * that succeeded with a lost response is attempted again, and that attempt
	 * is refused by its own earlier write - when the remote holds exactly what
	 * was being pushed, the push has in fact happened. Anything else there was
	 * written by another node.
	 *
	 * @param pushed
	 *   the manifest this node tried to write
	 * @return
	 *   the tag the remote serves the pushed manifest under
	 * @throws SyncConflictException
	 *   if the remote holds something other than the pushed manifest
	 * @throws IOException
	 */
	private String resolveRefusedManifest(Manifest pushed) throws IOException {
		var pulled = fetchRemoteManifest();

		if(pulled != null && pulled.manifest().equals(pushed)) {
			return quoteETag(pulled.eTag());
		}

		throw new SyncConflictException(
			"Remote manifest for " + index + " was replaced by another node"
				+ (pulled == null
					? ""
					: ", remote is at version " + pulled.manifest().getVersion()
						+ " and this node tried to write version " + pushed.getVersion())
		);
	}

	/**
	 * Get the tag the remote manifest is currently served under.
	 *
	 * @return
	 *   the tag in quoted form, or {@code null} when the remote holds no
	 *   manifest
	 * @throws IOException
	 */
	private String statManifestETag() throws IOException {
		return withRetries("stat", MANIFEST_NAME, () -> {
			try {
				var response = client.headObject(
					HeadObjectRequest.builder()
						.bucket(remoteBucket)
						.key(remotePrefix + "/" + MANIFEST_NAME)
						.build()
				);

				return quoteETag(response.eTag());
			} catch(S3Exception e) {
				if(e.statusCode() == 404) {
					return null;
				}

				throw e;
			}
		});
	}

	/**
	 * Bring an entity tag to the quoted form it is compared and sent in.
	 * Different responses report the same tag with and without quotes, so one
	 * form has to be settled on before two of them can be compared.
	 */
	public static String quoteETag(String etag) {
		if(etag == null || etag.isEmpty()) {
			return null;
		}

		return etag.startsWith("\"") ? etag : '"' + etag + '"';
	}

	/**
	 * Download a single file referenced by a manifest into the local directory.
	 *
	 * @param file
	 *   entry the manifest holds for the file
	 * @param localFile
	 *   path the file is stored under locally
	 * @throws IOException
	 *   if the file is missing from the remote or could not be downloaded
	 */
	private void downloadFile(ManifestFile file, Path localFile) throws IOException {
		Files.createDirectories(localFile.getParent());

		/*
		 * The file is downloaded next to the name it will end up under and then
		 * moved into place. Readers keep the file they opened even if it is
		 * replaced, and an interrupted download leaves a temporary file behind
		 * instead of a half written index file.
		 */
		var tempFile = localFile.resolveSibling(localFile.getFileName() + DOWNLOAD_SUFFIX);
		try {
			var downloaded = getObject(
				GetObjectRequest.builder()
					.bucket(remoteBucket)
					.key(remotePrefix + "/" + keyOf(file))
					.build(),
				response -> {
					Files.copy(response, tempFile, StandardCopyOption.REPLACE_EXISTING);
					return Boolean.TRUE;
				}
			);

			if(downloaded == null) {
				throw new IOException(
					"Manifest references " + file.getName() + " at " + keyOf(file)
						+ " but it is missing from object storage"
				);
			}

			/*
			 * On the disk before it is given the name the manifest knows it
			 * by. The manifest written at the end of the pull says every file
			 * it lists is present, and a file whose contents never left the
			 * page cache would make that a lie after a machine loses power -
			 * the name would be there and the contents would not.
			 */
			IOUtils.fsync(tempFile, false);

			moveIntoPlace(tempFile, localFile);
		} catch(IOException e) {
			try {
				Files.deleteIfExists(tempFile);
			} catch(IOException e2) {
				e.addSuppressed(e2);
			}

			throw e;
		}
	}

	/**
	 * Remove local files that the given manifest does not reference. Files are
	 * only removed after the manifest has been written, so anything removed
	 * here is already known to be replaceable.
	 *
	 * Removing a file that a reader still has open is safe on the platforms
	 * this runs on, the reader keeps reading the file it opened. Where it is
	 * not, the removal simply fails and is picked up by the next pull that has
	 * changes.
	 */
	private void deleteObsoleteLocalFiles(Manifest manifest) {
		var referenced = new HashSet<String>();
		for(var file : manifest.getFilesList()) {
			referenced.add(file.getName());
		}

		List<Path> paths;
		try(var files = Files.walk(localPath)) {
			paths = files
				.filter(f -> Files.isRegularFile(f) && !isProtectedFile(f))
				.toList();
		} catch(IOException e) {
			logger.atWarn()
				.addKeyValue("index", index)
				.setCause(e)
				.log("Could not list local files, obsolete files are kept");
			return;
		}

		for(var path : paths) {
			if(referenced.contains(remoteName(path))) {
				continue;
			}

			logger.atDebug()
				.addKeyValue("index", index)
				.addKeyValue("file", remoteName(path))
				.log("Removing local file that is no longer part of the index");

			try {
				Files.deleteIfExists(path);
			} catch(IOException e) {
				logger.atDebug()
					.addKeyValue("index", index)
					.addKeyValue("file", remoteName(path))
					.setCause(e)
					.log("Could not remove local file, retrying on the next pull");
			}
		}
	}

	/**
	 * Remove objects that the previous manifest referenced but the new one does
	 * not, as nothing will ask for them again.
	 *
	 * A node that started pulling the previous manifest may still be
	 * downloading one of these objects and see it disappear. That fails its
	 * pull, which is retried against the manifest that replaced it, so the only
	 * cost is a round of downloads.
	 *
	 * Failures are not fatal, the push itself has already succeeded at this
	 * point. The objects are remembered and removed by a later push instead.
	 */
	private void deleteObsoleteRemoteObjects(Manifest previous, Manifest current) {
		var referenced = new HashSet<String>();
		for(var file : current.getFilesList()) {
			referenced.add(keyOf(file));
		}

		for(var file : previous.getFilesList()) {
			if(!referenced.contains(keyOf(file))) {
				pendingRemoteDeletes.add(keyOf(file));
			}
		}

		var keys = pendingRemoteDeletes.iterator();
		while(keys.hasNext()) {
			var key = keys.next();
			if(referenced.contains(key)) {
				// The file came back, it is part of the index again
				keys.remove();
				continue;
			}

			logger.atDebug()
				.addKeyValue("index", index)
				.addKeyValue("key", key)
				.log("Removing remote object that is no longer part of the index");

			try {
				removeObject(remotePrefix + "/" + key);
				keys.remove();
			} catch(IOException e) {
				logger.atWarn()
					.addKeyValue("index", index)
					.addKeyValue("key", key)
					.setCause(e)
					.log("Could not remove remote object, retrying on the next push");
			}
		}
	}

	/**
	 * Run the sweep for orphaned remote objects when enough time has passed
	 * since it last ran. Listing the whole index is not worth doing on every
	 * push, and nothing younger than the grace period may be removed anyway.
	 */
	private void maybeSweepRemote(Manifest manifest) {
		if(System.nanoTime() - lastSweepNanos < orphanGrace.toNanos()) {
			return;
		}

		lastSweepNanos = System.nanoTime();
		sweepRemoteOrphans(manifest);
	}

	/**
	 * Remove objects under this index's prefix that no manifest references
	 * anymore. The per-push diff removes what a push stops referencing, but
	 * the uploads of a writer session that died before its manifest was
	 * accepted are referenced by nothing and seen by nobody - listing the
	 * remote is the only way to find them.
	 *
	 * Only objects older than the grace period are touched, a younger one may
	 * belong to a push that is still underway.
	 *
	 * Failures are not fatal, whatever could not be listed or removed is
	 * picked up by a later sweep.
	 */
	private void sweepRemoteOrphans(Manifest manifest) {
		var referenced = new HashSet<String>();
		referenced.add(MANIFEST_NAME);
		// A delete's mark over this generation is not this writer's to remove
		referenced.add(IndexRemovals.MARK_FILE);
		for(var file : manifest.getFilesList()) {
			referenced.add(keyOf(file));
		}

		var cutoff = Instant.now().minus(orphanGrace);
		var prefix = remotePrefix + "/";

		try {
			var objects = client.listObjectsV2Paginator(
				b -> b.bucket(remoteBucket).prefix(prefix)
			).contents();

			for(var item : objects) {
				var key = item.key().substring(prefix.length());

				if(referenced.contains(key)) {
					continue;
				}

				if(item.lastModified() != null && item.lastModified().isAfter(cutoff)) {
					continue;
				}

				logger.atInfo()
					.addKeyValue("index", index)
					.addKeyValue("key", key)
					.log("Removing orphaned remote object");

				removeObject(prefix + key);
				pendingRemoteDeletes.remove(key);
			}
		} catch(Exception e) {
			logger.atWarn()
				.addKeyValue("index", index)
				.setCause(e)
				.log("Could not sweep for orphaned remote objects; " + e.getMessage());
		}
	}

	/**
	 * Create a manifest for the given files.
	 *
	 * Files are listed in name order so that two manifests describing the same
	 * index compare equal, which is what tells a push that there is nothing to
	 * do.
	 *
	 * @param files
	 *   names of the files the index consists of
	 * @param previous
	 *   manifest describing the index as it was last synchronized, checksums
	 *   are carried over from it where they still apply
	 * @return
	 * @throws IOException
	 *   if one of the files could not be read, which includes it having been
	 *   removed while the manifest was being built
	 */
	private Manifest createManifest(Set<String> files, Manifest previous) throws IOException {
		var previousFiles = new HashMap<String, ManifestFile>();
		for(var file : previous.getFilesList()) {
			previousFiles.put(file.getName(), file);
		}

		var unchangedSince = manifestWrittenAt();

		/*
		 * The epoch the claim is going to arrive at, when this session has not
		 * claimed one yet. The claim is conditional on the manifest being
		 * unchanged, so if it goes through it lands exactly here, and if it
		 * does not the manifest built now is discarded along with the push.
		 */
		var epoch = sessionEpoch >= 0 ? sessionEpoch : previous.getEpoch() + 1;

		var generation = SegmentInfos.getLastCommitGeneration(files.toArray(String[]::new));

		var builder = Manifest.newBuilder()
			.setLatestSegment(generation)
			.setEpoch(epoch);

		recordLuceneVersion(builder, generation, previous);

		for(var name : new TreeSet<>(files)) {
			var path = resolveLocal(name);
			var attributes = Files.readAttributes(path, BasicFileAttributes.class);

			var file = ManifestFile.newBuilder()
				.setName(name)
				.setSize(attributes.size())
				.setChecksum(
					checksumOf(previousFiles.get(name), path, attributes, unchangedSince)
				);

			/*
			 * A file whose contents are what the previous manifest describes
			 * keeps the key it already has, wherever an earlier epoch put it.
			 * Anything else is going to be uploaded, which happens under the
			 * epoch of this session so that no other session can be writing
			 * the same key.
			 */
			var previousFile = previousFiles.get(name);
			if(previousFile != null && isUnchanged(previousFile, file.build())) {
				if(previousFile.hasKey()) {
					file.setKey(previousFile.getKey());
				}
			} else {
				file.setKey("e" + epoch + "/" + name);
			}

			builder.addFiles(file.build());
		}

		return builder.build();
	}

	/**
	 * Record which Lucene version the index was created with, and which one
	 * wrote the commit being pushed, by reading the commit that is about to be
	 * described.
	 *
	 * The creating major is what decides whether a later build can open the
	 * files at all, and it is set once when the index is first written, so
	 * reading it here is the only chance to put it somewhere that can be
	 * consulted without downloading the segments.
	 *
	 * A push carrying no commit - a definition replaced on its own, or an index
	 * that has not been written to yet - has nothing to read, and keeps
	 * whatever the previous manifest said.
	 *
	 * @param builder
	 *   manifest being built
	 * @param generation
	 *   generation of the commit the pushed files hold, or negative when they
	 *   hold none
	 * @param previous
	 *   manifest describing the index as it was last synchronized
	 */
	private void recordLuceneVersion(
		Manifest.Builder builder,
		long generation,
		Manifest previous
	) {
		if(generation >= 0) {
			var segmentsFile = IndexFileNames.fileNameFromGeneration(
				IndexFileNames.SEGMENTS, "", generation
			);

			try(var directory = FSDirectory.open(localPath)) {
				/*
				 * Read without a minimum supported version, as the point is to
				 * describe what is there rather than to judge it - the refusal
				 * belongs on the side that opens the index.
				 */
				var infos = SegmentInfos.readCommit(directory, segmentsFile, 0);

				builder.setLuceneCreatedMajor(infos.getIndexCreatedVersionMajor());

				var written = infos.getCommitLuceneVersion();
				if(written != null) {
					builder.setLuceneWrittenVersion(written.toString());
				}

				return;
			} catch(IOException e) {
				/*
				 * The commit could not be read, which the upload of its files
				 * is about to run into as well. Recording nothing leaves the
				 * previous answer in place rather than replacing it with a
				 * wrong one.
				 */
				logger.atWarn()
					.addKeyValue("index", index)
					.setCause(e)
					.log("Could not read the Lucene version of the commit; " + e.getMessage());
			}
		}

		if(previous.hasLuceneCreatedMajor()) {
			builder.setLuceneCreatedMajor(previous.getLuceneCreatedMajor());
		}

		if(previous.hasLuceneWrittenVersion()) {
			builder.setLuceneWrittenVersion(previous.getLuceneWrittenVersion());
		}
	}

	/**
	 * The key a file is stored under in the remote, relative to the index's
	 * prefix. A manifest from before keys were recorded stores every file
	 * under its name.
	 */
	private static String keyOf(ManifestFile file) {
		return file.hasKey() ? file.getKey() : file.getName();
	}

	/**
	 * Work out the checksum to record for a file, reading it only when it may
	 * have changed since the previous manifest was written. Reading every file
	 * on every push would mean reading the whole index each time a single
	 * document is committed, and Lucene never rewrites a file it has already
	 * published.
	 *
	 * @param previous
	 *   entry the file had in the previous manifest, or {@code null} if it is
	 *   new
	 * @param path
	 * @param attributes
	 * @param unchangedSince
	 *   point in time the previous manifest describes, or {@code null} when
	 *   there is no previous manifest to trust
	 * @return
	 * @throws IOException
	 */
	private static int checksumOf(
		ManifestFile previous,
		Path path,
		BasicFileAttributes attributes,
		FileTime unchangedSince
	) throws IOException {
		if(
			previous != null
				&& previous.hasChecksum()
				&& previous.getSize() == attributes.size()
				&& unchangedSince != null
				&& attributes.lastModifiedTime().compareTo(unchangedSince) < 0
		) {
			return previous.getChecksum();
		}

		var checksum = new CRC32C();
		var buffer = new byte[64 * 1024];

		try(var in = Files.newInputStream(path)) {
			int read;
			while((read = in.read(buffer)) != -1) {
				checksum.update(buffer, 0, read);
			}
		}

		return (int) checksum.getValue();
	}

	/**
	 * Time the manifest on disk was written, which is the point up to which
	 * the files it describes are known to be as described.
	 *
	 * @return
	 *   the time, or {@code null} when there is no manifest on disk or it
	 *   could not be read
	 */
	private FileTime manifestWrittenAt() {
		try {
			return Files.getLastModifiedTime(localPath.resolve(MANIFEST_NAME));
		} catch(IOException e) {
			return null;
		}
	}

	/**
	 * Check if two entries describe the same contents, so that transferring
	 * the file again would be pointless.
	 *
	 * A manifest written before checksums were recorded only has the size to
	 * go on. Treating that as a match keeps a node that upgrades from
	 * transferring an index it already has, at the cost of the check being no
	 * stronger than it was when that manifest was written.
	 *
	 * @param current
	 *   entry describing the copy that is already there, or {@code null} if
	 *   there is none
	 * @param wanted
	 * @return
	 */
	private static boolean isUnchanged(ManifestFile current, ManifestFile wanted) {
		if(current == null || current.getSize() != wanted.getSize()) {
			return false;
		}

		if(!current.hasChecksum() || !wanted.hasChecksum()) {
			return true;
		}

		return current.getChecksum() == wanted.getChecksum();
	}

	/**
	 * Check if a file has to be kept even when no manifest references it. These
	 * are the files describing the local state, the record of the copy being
	 * used, and the locks held on the directory - everything else can be
	 * downloaded again.
	 */
	private boolean isProtectedFile(Path path) {
		var name = path.getFileName().toString();
		return name.equals(MANIFEST_NAME)
			|| name.equals(MANIFEST_TEMP_NAME)
			|| name.equals(IndexUsageFile.NAME)
			|| name.endsWith(".lock");
	}

	/**
	 * Turn a local path into the name it is tracked under in the manifest.
	 * Names always use forward slashes so they map directly onto object keys,
	 * regardless of the separator used by the local file system.
	 */
	private String remoteName(Path path) {
		var relative = localPath.relativize(path);
		var name = new StringBuilder();

		for(var part : relative) {
			if(!name.isEmpty()) {
				name.append('/');
			}

			name.append(part);
		}

		return name.toString();
	}

	/**
	 * Resolve a name from a manifest against the local directory, rejecting
	 * names that would end up outside of it.
	 */
	private Path resolveLocal(String name) throws IOException {
		var resolved = localPath.resolve(name).normalize();
		if(!resolved.startsWith(localPath) || resolved.equals(localPath)) {
			throw new IOException("Manifest contains an invalid file name: " + name);
		}

		return resolved;
	}

	/**
	 * Write the manifest describing the current local state. The manifest is
	 * written to a temporary file first so that a failure part way through
	 * leaves the previous manifest intact.
	 */
	private void writeManifest(Manifest manifest) throws IOException {
		var tempFile = localPath.resolve(MANIFEST_TEMP_NAME);
		try(var out = Files.newOutputStream(tempFile)) {
			manifest.writeTo(out);
		}

		/*
		 * On the disk before the rename, so that what the rename publishes is
		 * a manifest and never a file of the right name holding nothing. A
		 * write that has only reached the page cache survives a process that
		 * stops but not a machine that loses power, and the rename can reach
		 * the disk first.
		 */
		IOUtils.fsync(tempFile, false);

		moveIntoPlace(tempFile, localPath.resolve(MANIFEST_NAME));

		/*
		 * The rename itself lives in the directory rather than in the file, so
		 * it takes a sync of its own to survive. Without it a machine that
		 * loses power here comes back to the manifest it held before, which
		 * describes files that have since been replaced.
		 */
		IOUtils.fsync(localPath, true);
	}

	/**
	 * Move a fully written temporary file to the name it is used under,
	 * atomically where the file system supports it.
	 */
	private void moveIntoPlace(Path tempFile, Path file) throws IOException {
		try {
			Files.move(
				tempFile,
				file,
				StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE
			);
		} catch(AtomicMoveNotSupportedException e) {
			Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/**
	 * Upload an object, retrying the upload if the remote fails in a way that
	 * may resolve itself.
	 *
	 * @param object
	 *   key to store the object under
	 * @param size
	 *   size of the object in bytes
	 * @param supplier
	 *   opens the contents to upload, called again for every attempt
	 * @throws IOException
	 *   if the object could not be uploaded
	 */
	private void putObject(String object, long size, ContentSupplier supplier)
		throws IOException {
		var request = PutObjectRequest.builder()
			.bucket(remoteBucket)
			.key(object)
			.contentType("application/octet-stream")
			.build();

		withRetries("upload", object, () -> {
			try(var in = supplier.get()) {
				logger.atDebug()
					.addKeyValue("bucket", request.bucket())
					.addKeyValue("object", request.key())
					.log("Uploading object");

				/*
				 * The size is always known up front, which lets the client
				 * stream straight through instead of buffering the object to
				 * find out how large it is.
				 */
				client.putObject(request, RequestBody.fromInputStream(in, size));
				return null;
			}
		});
	}

	/**
	 * Fetch an object and turn it into a value, retrying the fetch if the
	 * remote fails in a way that may resolve itself.
	 *
	 * @param argsBuilder
	 * @param reader
	 *   reads the value from the response, called again for every attempt
	 * @return
	 *   the value, or {@code null} if the object does not exist or is unchanged
	 *   according to the tag it was requested with
	 * @throws IOException
	 *   if the object could not be fetched
	 */
	private <T> T getObject(GetObjectRequest request, ObjectReader<T> reader)
		throws IOException {
		return withRetries("download", request.key(), () -> {
			logger.atDebug()
				.addKeyValue("bucket", request.bucket())
				.addKeyValue("object", request.key())
				.log("Downloading object");

			try(var response = client.getObject(request)) {
				return reader.read(response);
			} catch(S3Exception e) {
				if(e.statusCode() == 304) {
					// 304 Not Modified indicates that we have the latest version
					logger.atDebug()
						.addKeyValue("index", index)
						.addKeyValue("bucket", request.bucket())
						.addKeyValue("object", request.key())
						.log("File is up to date according to ETag");

					return null;
				}

				if(e.statusCode() == 404) {
					// 404 indicates that the object does not exist
					logger.atDebug()
						.addKeyValue("index", index)
						.addKeyValue("bucket", request.bucket())
						.addKeyValue("object", request.key())
						.log("File does not exist");

					return null;
				}

				throw e;
			}
		});
	}

	private void removeObject(String object) throws IOException {
		var request = DeleteObjectRequest.builder()
			.bucket(remoteBucket)
			.key(object)
			.build();

		withRetries("remove", object, () -> {
			client.deleteObject(request);
			return null;
		});
	}

	/**
	 * Run an operation against the object storage, attempting it again if it
	 * fails with something that describes the state of the remote rather than
	 * the request. Object storage routinely rejects requests it would serve at
	 * another time, so a single failure says very little.
	 *
	 * @param operation
	 *   what is being done, used when describing a failure
	 * @param object
	 *   object being operated on, used when describing a failure
	 * @param action
	 *   the work to perform, run once per attempt
	 * @return
	 *   the value returned by the action
	 * @throws IOException
	 *   if the action keeps failing, or fails with something that will not
	 *   resolve itself
	 */
	private <T> T withRetries(String operation, String object, StorageAction<T> action)
		throws IOException {
		var delay = RETRY_DELAY;

		for(int attempt = 1;; attempt++) {
			try {
				return action.run();
			} catch(Exception e) {
				if(e instanceof SyncConflictException conflict) {
					/*
					 * The remote answered clearly: another node owns the state
					 * now. That is an outcome rather than a failure, so it is
					 * passed on as it is.
					 */
					throw conflict;
				}

				if(attempt >= MAX_ATTEMPTS || !isTransient(e)) {
					logger.atWarn()
						.addKeyValue("index", index)
						.addKeyValue("bucket", remoteBucket)
						.addKeyValue("object", object)
						.addKeyValue("attempts", attempt)
						.setCause(e)
						.log("Could not " + operation + " object");

					throw new IOException(
						"Unable to " + operation + " " + object + "; " + e.getMessage(),
						e
					);
				}

				logger.atDebug()
					.addKeyValue("index", index)
					.addKeyValue("bucket", remoteBucket)
					.addKeyValue("object", object)
					.addKeyValue("attempt", attempt)
					.addKeyValue("delayMs", delay.toMillis())
					.setCause(e)
					.log("Could not " + operation + " object, trying again");

				sleep(delay);
				delay = delay.multipliedBy(2);
			}
		}
	}

	/**
	 * Check if a failure describes a temporary condition, meaning the same
	 * request has a chance of succeeding if it is made again.
	 */
	private static boolean isTransient(Exception e) {
		if(e instanceof AwsServiceException service) {
			// Anything the remote reports as its own problem may pass
			if(service.statusCode() >= 500) {
				return true;
			}

			/*
			 * A conditional write the storage could not decide because
			 * something else wrote the same key at that moment. Nothing was
			 * written, and the storage asks for the request to be made again -
			 * which is also how a writer that has actually lost finds out, by
			 * being refused outright the next time.
			 */
			if(service.statusCode() == 409) {
				return true;
			}

			var details = service.awsErrorDetails();
			return details != null && TRANSIENT_ERROR_CODES.contains(details.errorCode());
		}

		/*
		 * Connection failures and truncated responses arrive as
		 * SdkClientException or plain IOException and say nothing about
		 * whether the request can be served. Everything else, such as a
		 * rejected key or a malformed request, is going to be rejected again.
		 */
		return e instanceof SdkClientException || e instanceof IOException;
	}

	private static void sleep(Duration duration) throws IOException {
		try {
			Thread.sleep(duration.toMillis());
		} catch(InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting to retry", e);
		}
	}

	@FunctionalInterface
	private interface StorageAction<T> {
		T run() throws Exception;
	}

	@FunctionalInterface
	private interface ContentSupplier {
		InputStream get() throws IOException;
	}

	@FunctionalInterface
	private interface ObjectReader<T> {
		T read(ResponseInputStream<GetObjectResponse> response) throws IOException;
	}
}
