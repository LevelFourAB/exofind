package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

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
 * That a local copy whose manifest names a commit that is no longer on disk
 * is repaired from the remote rather than opened as an empty index. A
 * process that stops while a copy is deleted, a disk fault or an operator
 * can leave such a copy behind. Runs against SeaweedFS through
 * {@link TestObjectStorage}.
 */
public class IndexLostCommitFileTest {
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

	@Test
	void aReaderWhoseCopyLostItsCommitFileServesTheRemoteDocuments() throws Exception {
		// Given node A writes the index and pushed three documents
		var a = open("a", holder());
		a.pull();
		a.updateDefinition(definition());
		for(var id : List.of("1", "2", "3")) {
			a.addDocument(new Document(new Document.Value("id", id)));
		}
		a.commit();

		// And reader B pulled them
		var b = open("b", new NodeState(false));
		b.pull();
		assertThat(b.getDocumentCount(), is(3L));
		b.close();
		opened.remove(b);

		// And the copy of B lost its commit file, while its manifest stayed
		deleteCommitFiles(root.resolve("b"));

		// When B opens the index again and the remote has not moved
		var reopened = open("b", new NodeState(false));
		reopened.pull();

		// Then it serves the documents the remote holds
		assertThat(reopened.getDocumentCount(), is(3L));
	}

	@Test
	void aWriterWhoseCopyLostItsCommitFileOpens() throws Exception {
		// Given node A writes the index and pushed three documents
		var stateA = holder();
		var a = open("a", stateA);
		a.pull();
		a.updateDefinition(definition());
		for(var id : List.of("1", "2", "3")) {
			a.addDocument(new Document(new Document.Value("id", id)));
		}
		a.commit();
		a.close();
		opened.remove(a);

		// And the copy of A lost its commit file, while its manifest stayed
		deleteCommitFiles(root.resolve("a"));

		// When A opens the index again as the writer, and a later refresh pulls again
		var reopened = open("a", stateA);
		reopened.pull();
		reopened.pull();

		// Then the missing files are downloaded and the writer opens
		assertThat(reopened.getState(), is(IndexState.USABLE));
		assertThat(reopened.getDocumentCount(), is(3L));
	}

	private static void deleteCommitFiles(Path directory) throws IOException {
		try(var listing = Files.list(directory)) {
			for(var path : listing.toList()) {
				if(path.getFileName().toString().startsWith("segments_")) {
					Files.delete(path);
				}
			}
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

	private static IndexDef definition() {
		return IndexDef.newBuilder()
			.putFields(
				"id",
				FieldDef.newBuilder()
					.setType(FieldTypeDef.newBuilder().setString(StringFieldTypeDef.getDefaultInstance()))
					.setPrimaryKey(true)
					.build()
			)
			.build();
	}
}
