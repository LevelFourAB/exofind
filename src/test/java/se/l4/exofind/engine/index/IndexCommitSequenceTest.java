package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.state.NoopSync;

/**
 * The commit sequence: what a change says it lands in, what a commit makes
 * visible, and what a copy reads back out of the commit it opens.
 */
public class IndexCommitSequenceTest {
	@TempDir
	Path root;

	private final List<Index> indexes = new ArrayList<>();

	@AfterEach
	void cleanup() throws IOException {
		for(var index : indexes) {
			index.close(false);
		}
	}

	private static NodeState nodeState(boolean indexer) {
		var state = new NodeState(indexer);
		state.updateOwnership(indexer);
		return state;
	}

	private static IndexDef definition() {
		return IndexDef.newBuilder()
			.putFields(
				"id",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder().setString(StringFieldTypeDef.getDefaultInstance())
					)
					.setPrimaryKey(true)
					.build()
			)
			.build();
	}

	private Index open(String name, boolean indexer) throws IOException {
		var path = root.resolve(name);
		Files.createDirectories(path);

		var index = new Index(nodeState(indexer), name, path, new NoopSync());
		indexes.add(index);
		index.pull();
		return index;
	}

	private Index create(String name) throws IOException {
		var index = open(name, true);
		index.updateDefinition(definition());
		return index;
	}

	private static Document document(String id) {
		return new Document(new Document.Value("id", id));
	}

	@Test
	public void testANewIndexHoldsNoCommit() throws IOException {
		var index = create("books@1");

		assertThat(index.visibleCommit(), is(0L));
	}

	@Test
	public void testAWriteLandsInTheNextCommit() throws IOException {
		var index = create("books@1");

		long landsIn;
		try(var change = index.beginChange()) {
			index.addDocument(document("1"));
			landsIn = change.landsIn();
		}

		assertThat(landsIn, is(1L));
		assertThat(index.visibleCommit(), is(0L));

		index.commit();

		assertThat(index.visibleCommit(), is(1L));
	}

	/**
	 * A change the index refused wrote nothing, so the state it names is the
	 * commit already open rather than one that nothing will trigger.
	 */
	@Test
	public void testAChangeThatChangedNothingLandsInTheOpenCommit() throws IOException {
		var index = create("books@1");
		index.addDocument(document("1"));
		index.commit();

		long landsIn;
		try(var change = index.beginChange()) {
			assertThrows(
				ValidationException.class,
				() -> index.addDocument(new Document(new Document.Value("unknown", "x")))
			);
			landsIn = change.landsIn();
		}

		assertThat(landsIn, is(index.visibleCommit()));
	}

	/**
	 * A removal that matched nothing still counts as a change, and the commit
	 * it triggers is the one the state names.
	 */
	@Test
	public void testARemovalOfNothingLandsInTheCommitItTriggers() throws IOException {
		var index = create("books@1");
		index.addDocument(document("1"));
		index.commit();

		long landsIn;
		try(var change = index.beginChange()) {
			index.deleteDocument("missing");
			landsIn = change.landsIn();
		}
		index.commit();

		assertThat(landsIn, is(2L));
		assertThat(index.visibleCommit(), is(2L));
	}

	@Test
	public void testACommitWithNothingToCommitMovesNothing() throws IOException {
		var index = create("books@1");
		index.addDocument(document("1"));
		index.commit();
		index.commit();

		assertThat(index.visibleCommit(), is(1L));
	}

	@Test
	public void testEveryCommitThatCarriesChangesCounts() throws IOException {
		var index = create("books@1");

		index.addDocument(document("1"));
		index.commit();
		index.addDocument(document("2"));
		index.commit();
		index.deleteDocument("1");
		index.commit();

		assertThat(index.visibleCommit(), is(3L));
	}

	/**
	 * The sequence is recorded in the commit itself, so an instance opened
	 * over the same files - after a restart, or a copy pulled elsewhere -
	 * stands where the writer stood.
	 */
	@Test
	public void testTheSequenceIsReadBackFromTheCommit() throws IOException {
		var writer = create("books@1");
		writer.addDocument(document("1"));
		writer.commit();
		writer.addDocument(document("2"));
		writer.commit();
		writer.close(false);
		indexes.remove(writer);

		try(var directory = FSDirectory.open(root.resolve("books@1"))) {
			var userData = SegmentInfos.readLatestCommit(directory).getUserData();
			assertThat(userData.get(Index.COMMIT_SEQUENCE_KEY), is("2"));
		}

		var reader = open("books@1", false);
		assertThat(reader.visibleCommit(), is(2L));

		var reopened = open("books@1", true);
		assertThat(reopened.visibleCommit(), is(2L));

		reopened.addDocument(document("3"));
		reopened.commit();
		assertThat(reopened.visibleCommit(), is(3L));
	}
}
