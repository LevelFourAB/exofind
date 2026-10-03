package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.index.state.Manifest;
import se.l4.exofind.engine.index.state.ManifestFile;
import se.l4.exofind.engine.index.state.ObjectStorageSync;
import se.l4.exofind.engine.index.state.TestObjectStorage;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * That a pull which fails in a way the index does not expect leaves the index
 * to be pulled again, with a remote that holds a manifest this node can not
 * use. {@code IndexPullTest} covers the same with a fake remote for each
 * phase of the pull. Runs against SeaweedFS through {@link TestObjectStorage}.
 */
public class IndexPullFailureTest {
	S3Client s3Client;
	String bucketPrefix;

	@TempDir
	Path root;

	private final List<Index> opened = new ArrayList<>();

	@BeforeEach
	void setup() {
		s3Client = TestObjectStorage.client();
		bucketPrefix = "test" + RandomStringUtils.insecure().nextAlphabetic(10);
	}

	@AfterEach
	void cleanup() throws IOException {
		for(var index : opened) {
			index.close(false);
		}

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

	private Index open(String node, NodeState state) throws IOException {
		var path = root.resolve(node);
		Files.createDirectories(path);

		var sync = new ObjectStorageSync(
			s3Client, "books", path, TestObjectStorage.BUCKET, bucketPrefix
		);

		var index = new Index(state, "books", path, sync);
		opened.add(index);
		return index;
	}

	private static NodeState holder() {
		var state = new NodeState(true);
		state.updateOwnership(true);
		return state;
	}

	/**
	 * A manifest that names a file this file system can not hold makes the
	 * download throw an unchecked exception. The index must not stay in
	 * PULLING: once the remote is fixed, the next pull must open it.
	 */
	@Test
	void anUncheckedFailureInAPullDoesNotLeaveTheIndexPullingForever() throws Exception {
		// Given the remote holds a garbled manifest
		putRemoteManifest(
			Manifest.newBuilder()
				.setVersion(1)
				.setEpoch(1)
				.setLatestSegment(-1)
				.addFiles(
					ManifestFile.newBuilder()
						.setName("bad\u0000name")
						.setSize(1)
						.setKey("e1/bad")
				)
				.build()
		);

		var index = open("a", holder());

		// When the index is pulled
		index.pull();

		// Then the index is not left in a pull that nothing runs
		assertThat(index.getState(), is(not(IndexState.PULLING)));

		// And once the remote holds a valid manifest, the next pull opens the index
		putRemoteManifest(
			Manifest.newBuilder().setVersion(2).setEpoch(1).setLatestSegment(-1).build()
		);
		index.pull();
		assertThat(index.getState(), is(IndexState.USABLE));
	}

	private void putRemoteManifest(Manifest manifest) {
		s3Client.putObject(
			PutObjectRequest.builder()
				.bucket(TestObjectStorage.BUCKET)
				.key(bucketPrefix + "/manifest.ef.bin")
				.build(),
			RequestBody.fromBytes(manifest.toByteArray())
		);
	}
}
