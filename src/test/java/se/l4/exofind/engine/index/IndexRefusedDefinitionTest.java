package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import se.l4.exofind.engine.index.state.SyncConflictException;
import se.l4.exofind.engine.index.state.TestObjectStorage;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

/**
 * That a definition update whose push is refused does not stay on the node
 * that made it.
 *
 * <p>{@link Index#updateDefinition} writes the definition file before it
 * pushes. When another node took the index in between, the push is refused
 * and the client is told the update failed. The pull that follows must put
 * back the definition the remote holds, or the node serves, and later
 * publishes, a definition the cluster does not have. Runs against SeaweedFS
 * through {@link TestObjectStorage}.
 */
public class IndexRefusedDefinitionTest {
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

	private static IndexDef refusedDefinition() {
		return IndexDef.newBuilder()
			.putFields("id", stringField(true))
			.putFields("title", stringField(false))
			.build();
	}

	@Test
	void aRefusedDefinitionUpdateDoesNotSurviveTheReopenAfterALoss() throws Exception {
		// Given node A writes the index and pushed the first definition
		var stateA = holder();
		var a = open("a", stateA);
		a.pull();
		a.updateDefinition(firstDefinition());

		// And node B takes the index over and opens its writer
		var b = open("b", holder());
		b.pull();
		assertThat(b.getState(), is(IndexState.USABLE));

		// And node A has not noticed, and its definition update is refused
		assertThrows(SyncConflictException.class, () -> a.updateDefinition(refusedDefinition()));

		// When node A learns that the index was taken away and reopens
		a.revokeWriting();
		stateA.updateOwnership(false);
		a.reopen();

		// Then node A answers with the definition the remote holds
		assertThat(a.getState(), is(IndexState.USABLE));
		assertThat(
			"field `title` from the refused update on node A",
			a.getDefinition().getFieldsMap().containsKey("title"),
			is(false)
		);
		assertThat(a.getDefinition(), is(b.getDefinition()));
	}

	@Test
	void aRefusedDefinitionUpdateIsNotPublishedByTheNextCommit() throws Exception {
		// Given node A writes the index and pushed the first definition
		var a = open("a", holder());
		a.pull();
		a.updateDefinition(firstDefinition());

		// And node B claims the writer, so the next push of node A is refused
		var b = open("b", holder());
		b.pull();
		b.close(false);

		// And the definition update on node A is answered with an error
		assertThrows(SyncConflictException.class, () -> a.updateDefinition(refusedDefinition()));
		assertThat(a.getState(), is(IndexState.NEEDS_PULL));

		// When node A pulls, takes the index back and commits a document
		a.pull();
		assertThat(a.getState(), is(IndexState.USABLE));
		a.addDocument(new Document(new Document.Value("id", "1")));
		a.commit();

		// Then a new node that pulls the index does not see the refused field
		var c = open("c", holder());
		c.pull();
		assertThat(
			"field `title` from the refused update on a new node",
			c.getDefinition().getFieldsMap().containsKey("title"),
			is(false)
		);
	}
}
