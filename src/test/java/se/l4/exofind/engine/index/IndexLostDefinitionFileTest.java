package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
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
import se.l4.exofind.engine.index.state.Manifest;
import se.l4.exofind.engine.index.state.ObjectStorageSync;
import se.l4.exofind.engine.index.state.TestObjectStorage;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/**
 * That a local copy that holds a commit but lost its definition, or holds
 * an empty one, is not opened as an index with no fields. The definition is
 * what the documents of the commit were written under. Runs against
 * SeaweedFS through {@link TestObjectStorage}.
 */
public class IndexLostDefinitionFileTest {
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
	void aReaderWhoseCopyLostItsDefinitionDoesNotOpenWithoutFields() throws Exception {
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

		// And the copy of B lost its definition file, while its commit and manifest stayed
		Files.delete(root.resolve("b").resolve(Index.DEFINITION_FILE));

		// When B opens the index again and the remote has not moved
		var reopened = open("b", new NodeState(false));
		reopened.pull();

		// Then it is not served as an index without fields
		assertThat(
			"usable with the definition " + reopened.getDefinition(),
			reopened.getState() == IndexState.USABLE && !reopened.getDefinition().containsFields("id"),
			is(false)
		);
	}

	@Test
	void aWriterWhoseCopyHoldsAnEmptyDefinitionDoesNotOpenWithoutFields() throws Exception {
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

		// And the definition file of the copy of A is empty, as a crash during its write leaves it
		Files.write(root.resolve("a").resolve(Index.DEFINITION_FILE), new byte[0]);

		// When A opens the index again as the writer and commits
		var reopened = open("a", stateA);
		reopened.pull();
		try {
			reopened.commit();
		} catch(IOException | RuntimeException e) {
			// A refused commit pushes nothing
		}

		// Then it is not served as an index without fields
		assertThat(
			"usable with the definition " + reopened.getDefinition(),
			reopened.getState() == IndexState.USABLE && !reopened.getDefinition().containsFields("id"),
			is(false)
		);

		// And the remote still names a definition that is not empty
		var described = remoteManifest().getFilesList().stream()
			.filter(f -> f.getName().equals(Index.DEFINITION_FILE))
			.findFirst()
			.orElseThrow(() -> new AssertionError("the remote manifest names no definition"));
		assertThat(described.getSize(), greaterThan(0L));
	}

	private Manifest remoteManifest() throws IOException {
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
