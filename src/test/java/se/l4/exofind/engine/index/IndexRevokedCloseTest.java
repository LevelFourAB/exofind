package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

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
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.state.ObjectStorageSync;
import se.l4.exofind.engine.index.state.TestObjectStorage;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

/**
 * That an instance whose claim was revoked leaves nothing on disk when it
 * closes.
 *
 * <p>A node that lost the index without handing it over gives up what only it
 * holds. Lucene commits on close, so a writer that is closed rather than
 * rolled back would leave those documents as a local commit. The next
 * instance on the node opens the newest local commit while the remote has not
 * moved, and would serve them, and later push them. Runs against SeaweedFS
 * through {@link TestObjectStorage}.
 */
public class IndexRevokedCloseTest {
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

	private static FieldDef stringField(boolean primaryKey) {
		return FieldDef.newBuilder()
			.setType(FieldTypeDef.newBuilder().setString(StringFieldTypeDef.getDefaultInstance()))
			.setPrimaryKey(primaryKey)
			.build();
	}

	private static IndexDef firstDefinition() {
		return IndexDef.newBuilder()
			.putFields("id", stringField(true))
			.build();
	}

	@Test
	void aRevokedInstanceThatClosesLeavesNoCommitBehindForTheNextInstance() throws Exception {
		// Given node A writes the index and pushed one document
		var stateA = holder();
		var a = open("a", stateA);
		a.pull();
		a.updateDefinition(firstDefinition());
		a.addDocument(new Document(new Document.Value("id", "1")));
		a.commit();
		var pushedSequence = a.visibleCommit();

		// And node A acknowledged a second document that it did not commit
		a.addDocument(new Document(new Document.Value("id", "2")));

		// And the claim of node A lapsed, so what it holds may not be pushed
		a.revokeWriting();
		stateA.updateOwnership(false);

		// When the instance closes before the queued reopen runs
		a.close();
		opened.remove(a);

		// And a new read-only instance opens over the same directory
		var reopened = open("a", stateA);
		reopened.pull();

		// Then it answers from what the remote holds
		assertThat(reopened.getState(), is(IndexState.USABLE));
		assertThat(
			"document `2` that was given up with the claim",
			reopened.getDocument("2"),
			is(nullValue())
		);
		assertThat(reopened.getDocumentCount(), is(1L));
		assertThat(reopened.visibleCommit(), is(pushedSequence));
	}
}
