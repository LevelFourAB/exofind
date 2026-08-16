package se.l4.exofind.engine.index.state;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.OptionalInt;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import com.google.common.hash.Hashing;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

public class ObjectStorageSyncTest {
	S3Client s3Client;

	@TempDir
	Path localPath;

	/**
	 * Directory of a second node holding a copy of the same index, used to
	 * check what a node other than the one that pushed ends up with.
	 */
	@TempDir
	Path otherLocalPath;

	String bucketPrefix;

	ObjectStorageSync sync;

	@BeforeEach
	void setup() throws Exception {
		s3Client = TestObjectStorage.client();

		bucketPrefix = "test" + RandomStringUtils.insecure().nextAlphabetic(10);
		sync = newSync(s3Client);
	}

	/**
	 * Create a sync instance pointing at the same local directory and remote
	 * prefix as {@link #sync}. Used to simulate a restart of the node, where
	 * the in-memory state is lost but the local directory is kept.
	 */
	private ObjectStorageSync newSync(S3Client client) {
		return newSync(client, localPath);
	}

	/**
	 * Create a sync instance for a directory of its own, standing in for
	 * another node holding a copy of the same index.
	 */
	private ObjectStorageSync newSync(S3Client client, Path path) {
		return new ObjectStorageSync(
			client,
			"test",
			path,
			TestObjectStorage.BUCKET,
			bucketPrefix
		);
	}

	/**
	 * Create a sync instance with a grace period of its own, for exercising
	 * the sweep without waiting out the default.
	 */
	private ObjectStorageSync newSync(S3Client client, Path path, Duration orphanGrace) {
		return new ObjectStorageSync(
			client,
			"test",
			path,
			TestObjectStorage.BUCKET,
			bucketPrefix,
			orphanGrace
		);
	}

	@AfterEach
	void cleanup() throws Exception {
		// Cleanup the prefix in the bucket
		var objects = s3Client.listObjectsV2Paginator(
			b -> b.bucket(TestObjectStorage.BUCKET).prefix(bucketPrefix)
		).contents().stream().toList();

		for(var object : objects) {
			s3Client.deleteObject(
				DeleteObjectRequest.builder()
					.bucket(TestObjectStorage.BUCKET)
					.key(object.key())
					.build()
			);
		}
	}

	private record File(String name, long size, String hash) {
	}

	/**
	 * Push the given files as the files the index is made up of, standing in
	 * for the files of a Lucene commit together with the definition. Anything
	 * else the local directory holds is not part of the index.
	 */
	private void push(File... files) throws IOException {
		push(sync, files);
	}

	private void push(ObjectStorageSync sync, File... files) throws IOException {
		var names = new LinkedHashSet<String>();
		for(var file : files) {
			names.add(file.name());
		}

		sync.push(names);
	}

	private File createLocalFile(String name, long size) throws IOException {
		return createLocalFile(localPath, name, size);
	}

	private File createLocalFile(Path root, String name, long size) throws IOException {
		var file = root.resolve(name);

		var hasher = Hashing.sha512().newHasher();
		try(var out = new BufferedOutputStream(Files.newOutputStream(file))) {
			// Fill with random data
			for(int i = 0; i < size; i++) {
				var b = (byte) (Math.random() * 256);
				hasher.putByte(b);
				out.write(b);
			}
		}

		return new File(name, size, hasher.hash().toString());
	}

	private File createRemoteFile(String name, long size) throws IOException {
		var path = Files.createTempFile("ef", "test");
		try {
			var hasher = Hashing.sha512().newHasher();
			try(var out = new BufferedOutputStream(Files.newOutputStream(path))) {
				// Fill with random data
				for(int i = 0; i < size; i++) {
					var b = (byte) (Math.random() * 256);
					out.write(b);
					hasher.putByte(b);
				}
			}

			try {
				s3Client.putObject(
					PutObjectRequest.builder()
						.bucket(TestObjectStorage.BUCKET)
						.key(bucketPrefix + "/" + name)
						.build(),
					RequestBody.fromFile(path)
				);
			} catch(Exception e) {
				fail("unexpected error: " + e.getMessage());
			}

			return new File(name, size, hasher.hash().toString());
		} finally {
			Files.delete(path);
		}
	}

	private Manifest createManifest(int latestSegment, File... files) {
		var builder = Manifest.newBuilder()
			.setLatestSegment(latestSegment);

		var manifestFiles = Arrays.stream(files)
			.sorted((a, b) -> a.name.compareTo(b.name))
			.map(
				file -> ManifestFile.newBuilder()
					.setName(file.name)
					.setSize(file.size)
					.build()
			)
			.toList();

		builder.addAllFiles(manifestFiles);

		return builder.build();
	}

	private File createRemoteManifest(int latestSegment, File... files) {
		var manifest = createManifest(latestSegment, files);
		var bytes = manifest.toByteArray();

		var hash = Hashing.sha512().newHasher().putBytes(bytes)
			.hash().toString();

		try {
			s3Client.putObject(
				PutObjectRequest.builder()
					.bucket(TestObjectStorage.BUCKET)
					.key(bucketPrefix + "/manifest.ef.bin")
					.build(),
				RequestBody.fromBytes(bytes)
			);
		} catch(Exception e) {
			fail("unexpected error: " + e.getMessage());
		}

		return new File("manifest.ef.bin", bytes.length, hash);
	}

	private void verifyLocalFile(File file) {
		verifyLocalFile(localPath, file);
	}

	private void verifyLocalFile(Path root, File file) {
		var localFile = root.resolve(file.name);
		if(!Files.exists(localFile)) {
			fail("file " + file.name + " not found in local path");
		}

		try {
			var hasher = Hashing.sha512().newHasher();
			try(var in = Files.newInputStream(localFile)) {
				var buffer = new byte[1024];
				int read;
				while((read = in.read(buffer)) != -1) {
					hasher.putBytes(buffer, 0, read);
				}
			}

			if(!hasher.hash().toString().equals(file.hash)) {
				fail(
					"file " + file.name + " hash mismatch in local path, expected " + file.hash
						+ " got " + hasher.hash()
				);
			}
		} catch(Exception e) {
			fail("unexpected error: " + e.getMessage());
		}
	}

	/**
	 * The key the remote manifest stores a file under, which is its name when
	 * the manifest predates keys or does not mention it.
	 */
	private String remoteKeyOf(String name) throws Exception {
		return remoteManifest().getFilesList().stream()
			.filter(f -> f.getName().equals(name))
			.findFirst()
			.map(f -> f.hasKey() ? f.getKey() : f.getName())
			.orElse(name);
	}

	/**
	 * Verify that a file exists in the remote bucket, under whatever key the
	 * remote manifest gives it, and matches the local file.
	 *
	 * @param file
	 * @throws Exception
	 */
	private void verifyRemoteFile(File file) {
		try {
			var response = s3Client.getObject(
				GetObjectRequest.builder()
					.bucket(TestObjectStorage.BUCKET)
					.key(bucketPrefix + "/" + remoteKeyOf(file.name))
					.build()
			);

			var hasher = Hashing.sha512().newHasher();
			try(var in = response) {
				var buffer = new byte[1024];
				int read;
				while((read = in.read(buffer)) != -1) {
					hasher.putBytes(buffer, 0, read);
				}
			}

			var hash = hasher.hash().toString();
			if(!hash.equals(file.hash)) {
				fail(
					"file " + file.name + " hash mismatch in remote bucket, expected " + file.hash
						+ " got " + hash
				);
			}
		} catch(S3Exception e) {
			if(e.statusCode() == 404) {
				fail("file " + file.name() + " not found in remote bucket");
			}

			fail("unexpected error: " + e.getMessage());
		} catch(Exception e) {
			fail("unexpected error: " + e.getMessage());
		}
	}

	private void verifyLocalFileMissing(File file) {
		if(Files.exists(localPath.resolve(file.name))) {
			fail("file " + file.name + " found in local path");
		}
	}

	/**
	 * Verify that no object in the remote holds the file, whatever epoch
	 * prefix a key may have placed it under.
	 */
	private void verifyRemoteFileMissing(File file) {
		try {
			var objects = s3Client.listObjectsV2Paginator(
				b -> b.bucket(TestObjectStorage.BUCKET).prefix(bucketPrefix)
			).contents();

			for(var object : objects) {
				var key = object.key().substring(bucketPrefix.length() + 1);
				var base = key.substring(key.lastIndexOf('/') + 1);
				if(key.equals(file.name) || base.equals(file.name)) {
					fail("file " + file.name + " found in remote bucket at " + key);
				}
			}
		} catch(Exception e) {
			fail("unexpected error: " + e.getMessage());
		}
	}

	/**
	 * Read the manifest currently in the remote bucket.
	 */
	private Manifest remoteManifest() throws Exception {
		try(
			var response = s3Client.getObject(
				GetObjectRequest.builder()
					.bucket(TestObjectStorage.BUCKET)
					.key(bucketPrefix + "/manifest.ef.bin")
					.build()
			)
		) {
			return Manifest.parseFrom(response);
		}
	}

	private void verifyRemoteManifest(int latestSegment, File... files) {
		var manifest = createManifest(latestSegment, files);

		try {
			Manifest remoteManifest;
			try(
				var response = s3Client.getObject(
					GetObjectRequest.builder()
						.bucket(TestObjectStorage.BUCKET)
						.key(bucketPrefix + "/manifest.ef.bin")
						.build()
				)
			) {
				remoteManifest = Manifest.parseFrom(response);
			}

			// Compare the manifest segment to start with
			if(manifest.getLatestSegment() != remoteManifest.getLatestSegment()) {
				fail(
					"latest segment mismatch in remote manifest, expected "
						+ manifest.getLatestSegment() + " got " + remoteManifest.getLatestSegment()
				);
			}

			// Compare the files, failing on the first mismatch
			var manifestFiles = manifest.getFilesList().iterator();
			var remoteManifestFiles = remoteManifest.getFilesList().iterator();
			while(manifestFiles.hasNext() && remoteManifestFiles.hasNext()) {
				var localFile = manifestFiles.next();
				var remoteFile = remoteManifestFiles.next();
				if(!localFile.getName().equals(remoteFile.getName())) {
					fail(
						"file mismatch in remote manifest, expected to see file "
							+ localFile.getName() + " got " + remoteFile.getName()
					);
				}

				if(localFile.getSize() != remoteFile.getSize()) {
					fail(
						"file size mismatch in remote manifest, expected " + localFile.getSize()
							+ " got " + remoteFile.getSize()
					);
				}
			}

			if(manifestFiles.hasNext() || remoteManifestFiles.hasNext()) {
				fail("file count mismatch in remote manifest");
			}
		} catch(S3Exception e) {
			if(e.statusCode() == 404) {
				fail("file manifest.ef.bin not found in remote bucket");
			}

			fail("unexpected error: " + e.getMessage());
		} catch(Exception e) {
			fail("unexpected error: " + e.getMessage());
		}
	}

	@Test
	void testPushOneSegment() throws Exception {
		var segment = createLocalFile("segments_1", 10);
		push(segment);

		verifyRemoteManifest(1, segment);
		verifyRemoteFile(segment);
	}

	@Test
	void testPushOneSegmentAndDataFiles() throws Exception {
		var segment = createLocalFile("segments_1", 10);
		var d1 = createLocalFile("data", 100);
		var d2 = createLocalFile("data2", 2048);
		var d3 = createLocalFile("data3", 5 * 1024 * 1024);
		push(segment, d1, d2, d3);

		verifyRemoteManifest(1, segment, d1, d2, d3);
		verifyRemoteFile(segment);
		verifyRemoteFile(d1);
		verifyRemoteFile(d2);
		verifyRemoteFile(d3);
	}

	@Test
	public void testPushSegmentWithUpdates() throws Exception {
		var segment1 = createLocalFile("segments_1", 10);
		var d1 = createLocalFile("data", 100);
		push(segment1, d1);

		verifyRemoteManifest(1, segment1, d1);
		verifyRemoteFile(segment1);
		verifyRemoteFile(d1);

		var segment2 = createLocalFile("segments_2", 10);
		var d2 = createLocalFile("data2", 2048);
		push(segment1, segment2, d1, d2);

		verifyRemoteManifest(2, segment1, segment2, d1, d2);
		verifyRemoteFile(segment1);
		verifyRemoteFile(segment2);
		verifyRemoteFile(d1);
		verifyRemoteFile(d2);
	}

	@Test
	void testPushWithChunk() throws Exception {
		var segment = createLocalFile("segments_1", 10);
		var d1 = createLocalFile("data", 50 * 1024 * 1024);
		push(segment, d1);

		verifyRemoteManifest(1, segment, d1);
		verifyRemoteFile(segment);
		verifyRemoteFile(d1);
	}

	@Test
	void testPullOneSegment() throws Exception {
		var segment = createRemoteFile("segments_1", 10);
		var manifest = createRemoteManifest(1, segment);
		assertThat(sync.pull(), is(true));

		verifyLocalFile(manifest);
		verifyLocalFile(segment);
	}

	@Test
	void testPullOneSegmentAndDataFiles() throws Exception {
		var segment = createRemoteFile("segments_1", 10);
		var d1 = createRemoteFile("data", 100);
		var d2 = createRemoteFile("data2", 2048);
		var d3 = createRemoteFile("data3", 5 * 1024 * 1024);
		var manifest = createRemoteManifest(1, segment, d1, d2, d3);
		assertThat(sync.pull(), is(true));

		verifyLocalFile(manifest);
		verifyLocalFile(segment);
		verifyLocalFile(d1);
		verifyLocalFile(d2);
		verifyLocalFile(d3);
	}

	@Test
	void testPullSegmentWithUpdates() throws Exception {
		var segment1 = createRemoteFile("segments_1", 10);
		var d1 = createRemoteFile("data", 100);
		var manifest1 = createRemoteManifest(1, segment1, d1);
		assertThat(sync.pull(), is(true));

		verifyLocalFile(manifest1);
		verifyLocalFile(segment1);
		verifyLocalFile(d1);

		var segment2 = createRemoteFile("segments_2", 10);
		var d2 = createRemoteFile("data2", 2048);
		var manifest2 = createRemoteManifest(2, segment2, d1, d2);
		assertThat(sync.pull(), is(true));

		verifyLocalFile(manifest2);
		verifyLocalFileMissing(segment1);
		verifyLocalFile(segment2);
		verifyLocalFile(d1);
		verifyLocalFile(d2);
	}

	@Test
	void testPullThenPushSameSegment() throws Exception {
		var segment = createRemoteFile("segments_1", 10);
		var d1 = createRemoteFile("data", 100);
		var manifest = createRemoteManifest(1, segment, d1);
		assertThat(sync.pull(), is(true));

		verifyLocalFile(manifest);
		verifyLocalFile(segment);
		verifyLocalFile(d1);

		push(segment, d1);

		// This should not have done anything, verify that the same segment exists
		verifyRemoteManifest(1, segment, d1);
		verifyRemoteFile(segment);
		verifyRemoteFile(d1);
	}

	/**
	 * A file the local directory holds without it being part of the index is
	 * work in progress, such as a segment a merge is still writing. It stays
	 * where it is until a commit makes it part of the index.
	 */
	@Test
	void testPullThenPushSameSegmentWithNewLocalFiles() throws Exception {
		var segment = createRemoteFile("segments_1", 10);
		var d1 = createRemoteFile("data", 100);
		var manifest = createRemoteManifest(1, segment, d1);
		assertThat(sync.pull(), is(true));

		verifyLocalFile(manifest);
		verifyLocalFile(segment);
		verifyLocalFile(d1);

		var d2 = createLocalFile("data2", 2048);
		push(segment, d1);

		// This should not have done anything, verify that the same segment exists
		verifyRemoteManifest(1, segment, d1);
		verifyRemoteFile(segment);
		verifyRemoteFile(d1);
		verifyRemoteFileMissing(d2);
	}

	/**
	 * A file can be replaced by contents that happen to be the same length,
	 * which is what replacing the definition of an index tends to look like.
	 * Comparing sizes would miss it, so the manifest records a checksum too.
	 */
	@Test
	void testPushDetectsChangedFileOfSameSize() throws Exception {
		var segment = createLocalFile("segments_1", 10);
		var definition = createLocalFile("definition.ef.bin", 100);
		push(segment, definition);

		var replaced = createLocalFile("definition.ef.bin", 100);
		push(segment, replaced);

		verifyRemoteManifest(1, segment, replaced);
		verifyRemoteFile(replaced);
	}

	/**
	 * The same has to hold on the way in, for a node that already holds a file
	 * of the right size but with the contents it had before.
	 */
	@Test
	void testPullDetectsChangedFileOfSameSize() throws Exception {
		var segment = createLocalFile("segments_1", 10);
		var definition = createLocalFile("definition.ef.bin", 100);
		push(segment, definition);

		var other = newSync(s3Client, otherLocalPath);
		assertThat(other.pull(), is(true));

		var replaced = createLocalFile("definition.ef.bin", 100);
		push(segment, replaced);

		assertThat(other.pull(), is(true));
		verifyLocalFile(otherLocalPath, replaced);
	}

	/**
	 * Two manifests are ordered by their version rather than by the segment
	 * number, so every push has to move it along. The first push of a session
	 * moves it twice, once for the epoch claim and once for the content.
	 */
	@Test
	void testPushAdvancesManifestVersion() throws Exception {
		var segment1 = createLocalFile("segments_1", 10);
		push(segment1);
		assertThat(remoteManifest().getVersion(), is(2L));

		var segment2 = createLocalFile("segments_2", 10);
		push(segment1, segment2);
		assertThat(remoteManifest().getVersion(), is(3L));

		// Nothing changed locally, so the version stays where it is
		push(segment1, segment2);
		assertThat(remoteManifest().getVersion(), is(3L));
	}

	@Test
	void testPullThenPushNewSegment() throws Exception {
		var segment1 = createRemoteFile("segments_1", 10);
		var d1 = createRemoteFile("data", 100);
		var manifest = createRemoteManifest(1, segment1, d1);
		assertThat(sync.pull(), is(true));

		verifyLocalFile(manifest);
		verifyLocalFile(segment1);
		verifyLocalFile(d1);

		var d2 = createLocalFile("data2", 2048);
		var segment2 = createLocalFile("segments_2", 10);
		push(segment1, segment2, d1, d2);

		verifyRemoteManifest(2, segment1, segment2, d1, d2);
		verifyRemoteFile(segment1);
		verifyRemoteFile(segment2);
		verifyRemoteFile(d1);
		verifyRemoteFile(d2);
	}

	/**
	 * A manifest may reference a file that is not actually present in the
	 * remote bucket, either because an earlier push was interrupted or because
	 * the object was removed. Pulling must report that as a regular sync
	 * failure instead of blowing up on the missing response.
	 */
	@Test
	void testPullFailsWhenRemoteFileIsMissing() throws Exception {
		var segment = createRemoteFile("segments_1", 10);
		var missing = new File("data", 100, null);
		createRemoteManifest(1, segment, missing);

		assertThrows(IOException.class, () -> sync.pull());
	}

	/**
	 * The local manifest is the only record of what has already been pulled. If
	 * it is lost the next pull has to be able to rebuild the local directory,
	 * even though the files it downloads are already there.
	 */
	@Test
	void testPullRecoversWhenLocalManifestIsLost() throws Exception {
		var segment = createRemoteFile("segments_1", 10);
		var d1 = createRemoteFile("data", 100);
		createRemoteManifest(1, segment, d1);

		assertThat(sync.pull(), is(true));
		verifyLocalFile(segment);
		verifyLocalFile(d1);

		// Drop the manifest, keeping the downloaded files in place
		Files.delete(localPath.resolve("manifest.ef.bin"));

		var restarted = newSync(s3Client);
		assertThat(restarted.pull(), is(true));

		verifyLocalFile(segment);
		verifyLocalFile(d1);
	}

	/**
	 * A pull that fails partway through must not leave behind a manifest
	 * claiming the index is up to date, otherwise the missing files are never
	 * downloaded again.
	 */
	@Test
	void testPullFailureCanBeRetriedAfterRestart() throws Exception {
		var segment = createRemoteFile("segments_1", 10);
		var missing = new File("data", 100, null);
		createRemoteManifest(1, segment, missing);

		assertThrows(IOException.class, () -> sync.pull());

		// The file shows up in the remote, the next pull should complete
		var d1 = createRemoteFile("data", 100);

		var restarted = newSync(s3Client);
		assertThat(restarted.pull(), is(true));

		verifyLocalFile(segment);
		verifyLocalFile(d1);
	}

	/**
	 * The same holds for a push that fails while uploading the manifest: the
	 * remote is still on the previous commit, so the next push has to upload
	 * the manifest again rather than consider itself up to date.
	 */
	@Test
	void testPushFailureCanBeRetriedAfterRestart() throws Exception {
		var failingClient = Mockito.spy(s3Client);
		Mockito.doThrow(SdkClientException.create("simulated manifest upload failure"))
			.when(failingClient)
			.putObject(
				ArgumentMatchers.<PutObjectRequest>argThat(
					request -> request != null && request.key().endsWith("manifest.ef.bin")
				),
				ArgumentMatchers.any(RequestBody.class)
			);

		var failingSync = newSync(failingClient);

		var segment = createLocalFile("segments_1", 10);
		var d1 = createLocalFile("data", 100);

		assertThrows(IOException.class, () -> push(failingSync, segment, d1));
		verifyRemoteFileMissing(new File("manifest.ef.bin", 0, null));

		var restarted = newSync(s3Client);
		push(restarted, segment, d1);

		verifyRemoteManifest(1, segment, d1);
		verifyRemoteFile(segment);
		verifyRemoteFile(d1);
	}

	/**
	 * Pulling into a directory that already holds a file of a different size
	 * has to overwrite it, the remote is the source of truth for a read-only
	 * node.
	 */
	@Test
	void testPullOverwritesLocalFileOfDifferentSize() throws Exception {
		createLocalFile("data", 25);

		var segment = createRemoteFile("segments_1", 10);
		var d1 = createRemoteFile("data", 100);
		createRemoteManifest(1, segment, d1);

		assertThat(sync.pull(), is(true));

		verifyLocalFile(segment);
		verifyLocalFile(d1);
	}

	/**
	 * File names come from the remote and must not be able to write outside of
	 * the directory owned by the index.
	 */
	@Test
	void testPullRejectsFileNameOutsideLocalPath() throws Exception {
		var segment = createRemoteFile("segments_1", 10);
		var escaping = new File("../escaped", 100, null);
		createRemoteManifest(1, segment, escaping);

		assertThrows(IOException.class, () -> sync.pull());

		if(Files.exists(localPath.resolve("../escaped").normalize())) {
			fail("file was written outside of the local path");
		}
	}

	/**
	 * Files that the local directory holds without any manifest referencing
	 * them are leftovers, such as a download that was interrupted before the
	 * file was moved into place. A pull is what establishes the local state, so
	 * it is also what cleans them up.
	 */
	@Test
	void testPullRemovesLocalFilesOutsideOfTheManifest() throws Exception {
		var leftover = createLocalFile("data.download.tmp", 25);

		var segment = createRemoteFile("segments_1", 10);
		createRemoteManifest(1, segment);

		assertThat(sync.pull(), is(true));

		verifyLocalFile(segment);
		verifyLocalFileMissing(leftover);
	}

	/**
	 * The usage record belongs to the node rather than the index, so no
	 * manifest ever references it and the cleanup of a pull leaves it alone.
	 */
	@Test
	void testPullKeepsTheUsageRecord() throws Exception {
		IndexUsageFile.write(
			localPath,
			IndexUsageFile.recordOpen(
				IndexUsage.getDefaultInstance(),
				Instant.now(),
				Duration.ofDays(7)
			)
		);

		var segment = createRemoteFile("segments_1", 10);
		createRemoteManifest(1, segment);

		assertThat(sync.pull(), is(true));

		assertThat(Files.exists(localPath.resolve(IndexUsageFile.NAME)), is(true));
	}

	/**
	 * Merging away a segment leaves its files behind in the remote, where they
	 * cost storage without being of use to anyone. The push that stops
	 * referencing them is what removes them.
	 */
	@Test
	void testPushRemovesRemoteFilesOutsideOfTheManifest() throws Exception {
		var segment1 = createLocalFile("segments_1", 10);
		var d1 = createLocalFile("data", 100);
		push(segment1, d1);

		verifyRemoteFile(d1);

		// The data of the first segment is replaced by the data of the second
		Files.delete(localPath.resolve(d1.name));
		var segment2 = createLocalFile("segments_2", 10);
		var d2 = createLocalFile("data2", 2048);
		push(segment1, segment2, d2);

		verifyRemoteManifest(2, segment1, segment2, d2);
		verifyRemoteFile(segment1);
		verifyRemoteFile(segment2);
		verifyRemoteFile(d2);
		verifyRemoteFileMissing(d1);
	}

	/**
	 * Object storage rejecting a request with an error of its own says nothing
	 * about whether the request can be served, so it is made again before the
	 * sync gives up on it.
	 */
	@Test
	void testPushRetriesServerErrors() throws Exception {
		var flakyClient = Mockito.spy(s3Client);
		Mockito.doThrow(
			S3Exception.builder()
				.message("simulated overload")
				.statusCode(503)
				.build()
		)
			.doCallRealMethod()
			.when(flakyClient)
			.putObject(
				ArgumentMatchers.any(PutObjectRequest.class),
				ArgumentMatchers.any(RequestBody.class)
			);

		var flakySync = newSync(flakyClient);

		var segment = createLocalFile("segments_1", 10);
		push(flakySync, segment);

		verifyRemoteManifest(1, segment);
		verifyRemoteFile(segment);
	}

	@Test
	void testPullRetriesServerErrors() throws Exception {
		var flakyClient = Mockito.spy(s3Client);
		Mockito.doThrow(
			S3Exception.builder()
				.message("simulated overload")
				.statusCode(503)
				.build()
		)
			.doCallRealMethod()
			.when(flakyClient)
			.getObject(ArgumentMatchers.any(GetObjectRequest.class));

		var flakySync = newSync(flakyClient);

		var segment = createRemoteFile("segments_1", 10);
		var manifest = createRemoteManifest(1, segment);

		assertThat(flakySync.pull(), is(true));

		verifyLocalFile(manifest);
		verifyLocalFile(segment);
	}

	/**
	 * A request the remote refuses because of what it asks for will be refused
	 * again, repeating it only delays the error.
	 */
	@Test
	void testPushDoesNotRetryRejectedRequests() throws Exception {
		var failingClient = Mockito.spy(s3Client);
		Mockito.doThrow(new IllegalArgumentException("simulated invalid request"))
			.when(failingClient)
			.putObject(
				ArgumentMatchers.any(PutObjectRequest.class),
				ArgumentMatchers.any(RequestBody.class)
			);

		var failingSync = newSync(failingClient);

		var segment = createLocalFile("segments_1", 10);
		assertThrows(IOException.class, () -> push(failingSync, segment));

		Mockito.verify(failingClient, Mockito.times(1)).putObject(
			ArgumentMatchers.any(PutObjectRequest.class),
			ArgumentMatchers.any(RequestBody.class)
		);
	}

	/**
	 * Polling an unchanged remote should keep reporting that there is nothing
	 * to do.
	 */
	@Test
	void testPullTwiceWithoutRemoteChanges() throws Exception {
		var segment = createRemoteFile("segments_1", 10);
		var d1 = createRemoteFile("data", 100);
		createRemoteManifest(1, segment, d1);

		assertThat(sync.pull(), is(true));
		assertThat(sync.pull(), is(false));

		verifyLocalFile(segment);
		verifyLocalFile(d1);
	}

	/**
	 * Two nodes pushing against the same remote is the misconfiguration the
	 * conditional write exists for: a push built on a manifest that is no
	 * longer there has to be refused before it uploads anything, rather than
	 * overwrite what the other node wrote.
	 */
	@Test
	void testPushIsRefusedWhenRemoteManifestChanged() throws Exception {
		var segment = createLocalFile("segments_1", 10);
		push(segment);

		// Another node replaces the manifest behind this node's back
		var remoteSegment = createRemoteFile("segments_2", 12);
		createRemoteManifest(2, remoteSegment);

		var newSegment = createLocalFile("segments_3", 14);
		assertThrows(SyncConflictException.class, () -> push(segment, newSegment));

		// The other node's manifest is left in place, and nothing was uploaded
		verifyRemoteManifest(2, remoteSegment);
		verifyRemoteFileMissing(newSegment);
	}

	/**
	 * A fresh node pushing to a remote that already holds an index it has
	 * never pulled must not overwrite it, however it came to hold local files
	 * of its own.
	 */
	@Test
	void testFirstPushIsRefusedWhenRemoteHoldsAnIndex() throws Exception {
		var remoteSegment = createRemoteFile("segments_1", 10);
		createRemoteManifest(1, remoteSegment);

		var localSegment = createLocalFile("segments_2", 12);
		assertThrows(SyncConflictException.class, () -> push(localSegment));

		verifyRemoteManifest(1, remoteSegment);
		verifyRemoteFileMissing(localSegment);
	}

	/**
	 * A restart loses the tag the node knew the remote manifest under but
	 * keeps the manifest itself, and the node has to be able to keep pushing.
	 */
	@Test
	void testPushContinuesAfterRestart() throws Exception {
		var segment = createLocalFile("segments_1", 10);
		push(segment);

		var restarted = newSync(s3Client);
		var next = createLocalFile("segments_2", 12);
		push(restarted, segment, next);

		verifyRemoteManifest(2, segment, next);
		assertThat(remoteManifest().getVersion(), is(4L));
	}

	/**
	 * A refused push leaves the node able to pull the winning state and push
	 * on top of it.
	 */
	@Test
	void testPushContinuesAfterPullingRefusedState() throws Exception {
		var segment = createLocalFile("segments_1", 10);
		push(segment);

		var remoteSegment = createRemoteFile("segments_2", 12);
		createRemoteManifest(2, remoteSegment);

		var newSegment = createLocalFile("segments_3", 14);
		assertThrows(SyncConflictException.class, () -> push(segment, newSegment));

		assertThat(sync.pull(), is(true));
		verifyLocalFile(remoteSegment);

		var next = createLocalFile("segments_4", 16);
		push(remoteSegment, next);

		verifyRemoteManifest(4, remoteSegment, next);
	}

	/**
	 * A manifest write that succeeded with a lost response is attempted again,
	 * and that attempt is refused by the node's own earlier write. The refusal
	 * has to read as the success it is, and leave the node able to keep
	 * pushing.
	 */
	@Test
	void testPushSurvivesLostManifestResponse() throws Exception {
		var segment = createLocalFile("segments_1", 10);
		push(segment);

		var failingClient = Mockito.spy(s3Client);
		Mockito.doAnswer(invocation -> {
			invocation.callRealMethod();
			throw SdkClientException.create("simulated lost response");
		}).doCallRealMethod()
			.when(failingClient)
			.putObject(
				ArgumentMatchers.<PutObjectRequest>argThat(
					request -> request != null && request.key().endsWith("manifest.ef.bin")
				),
				ArgumentMatchers.any(RequestBody.class)
			);

		var failingSync = newSync(failingClient);
		var next = createLocalFile("segments_2", 12);
		push(failingSync, segment, next);

		verifyRemoteManifest(2, segment, next);
		assertThat(remoteManifest().getVersion(), is(4L));

		// The node recovered the tag of its own write and can keep pushing
		var third = createLocalFile("segments_3", 14);
		push(failingSync, segment, next, third);
		assertThat(remoteManifest().getVersion(), is(5L));
	}

	/**
	 * Each writer session writes its files under a prefix of its own, and a
	 * file that does not change keeps the key it already has - a later
	 * session never uploads it again, and never writes where an earlier one
	 * did.
	 */
	@Test
	void testFilesAreKeyedByWriterEpoch() throws Exception {
		var segment = createLocalFile("segments_1", 10);
		push(segment);

		var restarted = newSync(s3Client);
		var next = createLocalFile("segments_2", 12);
		push(restarted, segment, next);

		assertThat(remoteManifest().getEpoch(), is(2L));
		assertThat(remoteKeyOf("segments_1"), is("e1/segments_1"));
		assertThat(remoteKeyOf("segments_2"), is("e2/segments_2"));

		verifyRemoteFile(segment);
		verifyRemoteFile(next);
	}

	/**
	 * A second writer claims an epoch of its own before uploading anything,
	 * so even a file Lucene names the same on both nodes ends up under a key
	 * of its own instead of overwriting the other node's upload.
	 */
	@Test
	void testSecondWriterWritesUnderItsOwnEpoch() throws Exception {
		var segment = createLocalFile("segments_1", 10);
		push(segment);

		// A second node pulls the index and pushes work of its own
		var other = newSync(s3Client, otherLocalPath);
		assertThat(other.pull(), is(true));
		var otherSegment = createLocalFile(otherLocalPath, "segments_2", 12);
		push(other, segment, otherSegment);

		assertThat(remoteManifest().getEpoch(), is(2L));
		assertThat(remoteKeyOf("segments_2"), is("e2/segments_2"));
		// The first writer's upload is carried forward where it was
		assertThat(remoteKeyOf("segments_1"), is("e1/segments_1"));
		verifyRemoteFile(segment);

		// And the first writer's next push is refused
		var stale = createLocalFile("segments_3", 14);
		assertThrows(SyncConflictException.class, () -> push(segment, stale));
	}

	/**
	 * A writer that pulled someone else's state may find files of its own
	 * epoch carried forward in it, so it claims a fresh epoch rather than
	 * write where the adopted manifest is pointing.
	 */
	@Test
	void testWriterClaimsFreshEpochAfterPull() throws Exception {
		var segment = createLocalFile("segments_1", 10);
		push(segment);

		var other = newSync(s3Client, otherLocalPath);
		assertThat(other.pull(), is(true));
		var otherSegment = createLocalFile(otherLocalPath, "segments_2", 12);
		push(other, segment, otherSegment);

		// The first writer loses, pulls the winning state and continues
		var stale = createLocalFile("segments_3", 14);
		assertThrows(SyncConflictException.class, () -> push(segment, stale));
		assertThat(sync.pull(), is(true));

		var next = createLocalFile("segments_4", 16);
		push(segment, otherSegment, next);

		assertThat(remoteManifest().getEpoch(), is(3L));
		assertThat(remoteKeyOf("segments_4"), is("e3/segments_4"));
		verifyRemoteFile(next);
	}

	/**
	 * Objects that no manifest references - the uploads of a writer session
	 * that died before its manifest was accepted - are only found by listing,
	 * and are removed once they are old enough that no running push can still
	 * be about to reference them.
	 */
	@Test
	void testSweepRemovesOrphanedObjects() throws Exception {
		var orphan = createRemoteFile("e7/segments_9", 20);

		/*
		 * A negative grace period puts the cutoff in the future, so the sweep
		 * removes the orphan no matter how far the storage's clock is from
		 * this one's.
		 */
		var sweeping = newSync(s3Client, localPath, Duration.ofSeconds(-30));
		var segment = createLocalFile("segments_1", 10);
		push(sweeping, segment);

		verifyRemoteFileMissing(orphan);
		verifyRemoteFile(segment);
	}

	/**
	 * An object younger than the grace period may belong to a push that is
	 * still underway, so the sweep leaves it alone.
	 */
	@Test
	void testSweepKeepsRecentObjects() throws Exception {
		var orphan = createRemoteFile("e7/segments_9", 20);

		var segment = createLocalFile("segments_1", 10);
		push(segment);

		verifyRemoteFile(orphan);
	}

	/**
	 * A remote whose manifest was removed holds nothing a push could
	 * overwrite, so the push recreates it rather than refusing forever.
	 */
	@Test
	void testPushRecreatesRemovedRemoteManifest() throws Exception {
		var segment = createLocalFile("segments_1", 10);
		push(segment);

		s3Client.deleteObject(
			DeleteObjectRequest.builder()
				.bucket(TestObjectStorage.BUCKET)
				.key(bucketPrefix + "/manifest.ef.bin")
				.build()
		);

		var next = createLocalFile("segments_2", 12);
		push(segment, next);

		verifyRemoteManifest(2, segment, next);
	}

	/**
	 * A push of a real Lucene commit records which version created the index,
	 * so that a later build can tell whether it can still open the files
	 * without downloading them.
	 */
	@Test
	void testPushRecordsLuceneVersion() throws Exception {
		var files = writeLuceneCommit(localPath);
		sync.push(files);

		var manifest = remoteManifest();

		assertThat(manifest.hasLuceneCreatedMajor(), is(true));
		assertThat(manifest.getLuceneCreatedMajor(), is(Version.LATEST.major));
		assertThat(manifest.getLuceneWrittenVersion(), is(Version.LATEST.toString()));
	}

	/**
	 * A push carrying no commit - a definition replaced on its own - keeps the
	 * version the previous push recorded rather than dropping it.
	 */
	@Test
	void testPushWithoutCommitKeepsLuceneVersion() throws Exception {
		var files = writeLuceneCommit(localPath);
		sync.push(files);

		var definition = createLocalFile("definition.ef.bin", 16);
		var withDefinition = new LinkedHashSet<>(files);
		withDefinition.add(definition.name());
		sync.push(withDefinition);

		assertThat(remoteManifest().getLuceneCreatedMajor(), is(Version.LATEST.major));
	}

	/**
	 * An index created too far back to open is refused while pulling, and
	 * refused before any of its files are fetched - downloading them would only
	 * arrive at the same answer, having spent the transfer.
	 */
	@Test
	void testPullRefusesIndexCreatedByUnsupportedLucene() throws Exception {
		var remote = createRemoteFile("segments_1", 20);
		putRemoteManifest(
			createManifest(1, remote)
				.toBuilder()
				.setLuceneCreatedMajor(Version.MIN_SUPPORTED_MAJOR - 1)
				.build()
		);

		var other = newSync(s3Client, otherLocalPath);
		var e = assertThrows(SyncIncompatibleException.class, () -> other.pull());

		assertThat(e.getCreatedMajor(), is(Version.MIN_SUPPORTED_MAJOR - 1));

		// Nothing was fetched, so the refusal cost one manifest read
		assertThat(Files.exists(otherLocalPath.resolve("segments_1")), is(false));
	}

	/**
	 * An index created by the oldest version Lucene still reads is pulled
	 * normally. Saying it is nearing the end of the window is worth doing, but
	 * refusing it would take away the only copy that can still be reindexed.
	 */
	@Test
	void testPullAcceptsIndexCreatedByOldestSupportedLucene() throws Exception {
		var remote = createRemoteFile("segments_1", 20);
		putRemoteManifest(
			createManifest(1, remote)
				.toBuilder()
				.setLuceneCreatedMajor(Version.MIN_SUPPORTED_MAJOR)
				.build()
		);

		var other = newSync(s3Client, otherLocalPath);

		assertThat(other.pull(), is(true));
		assertThat(
			other.luceneCreatedMajor(),
			is(OptionalInt.of(Version.MIN_SUPPORTED_MAJOR))
		);
	}

	/**
	 * A manifest written before the version was recorded says nothing about
	 * which Lucene made the index, and is pulled rather than refused - every
	 * index from before this was tracked looks like that.
	 */
	@Test
	void testPullAcceptsManifestWithoutLuceneVersion() throws Exception {
		var remote = createRemoteFile("segments_1", 20);
		putRemoteManifest(createManifest(1, remote));

		var other = newSync(s3Client, otherLocalPath);

		assertThat(other.pull(), is(true));
		assertThat(other.luceneCreatedMajor(), is(OptionalInt.empty()));
	}

	private void putRemoteManifest(Manifest manifest) throws Exception {
		var bytes = manifest.toByteArray();

		s3Client.putObject(
			PutObjectRequest.builder()
				.bucket(TestObjectStorage.BUCKET)
				.key(bucketPrefix + "/manifest.ef.bin")
				.build(),
			RequestBody.fromBytes(bytes)
		);
	}

	/**
	 * Write a real Lucene index into a directory and return the files of its
	 * commit, for the tests that care about what Lucene records in them rather
	 * than about the transfer.
	 */
	private LinkedHashSet<String> writeLuceneCommit(Path path) throws IOException {
		try(var directory = FSDirectory.open(path)) {
			try(var writer = new IndexWriter(directory, new IndexWriterConfig())) {
				var document = new Document();
				document.add(new StringField("id", "1", Field.Store.YES));
				writer.addDocument(document);
				writer.commit();
			}

			return new LinkedHashSet<>(
				SegmentInfos.readLatestCommit(directory).files(true)
			);
		}
	}
}
