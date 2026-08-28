package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.io.IOException;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.state.NoopSync;

/**
 * Tests for reaching documents by the key terms a {@link ChangeLog} records -
 * what a reindex replays through - and for counting what an index holds.
 */
public class KeyTermAccessTest extends AbstractIndexTest {
	@Test
	public void aRecordedKeyReadsTheDocumentAsItIsNow() throws IOException {
		var index = catalogue();
		var log = index.beginChangeTracking();

		// Written after the last commit, so only the writer's view holds it
		index.addDocument(doc("3", "Lingonberry jam"));

		var key = log.snapshot().keys().get(0);
		var document = index.getDocumentByKeyTerm(key);

		assertThat(document, is(notNullValue()));
		assertThat(document.get("name"), is("Lingonberry jam"));
	}

	@Test
	public void aKeyOfARemovedDocumentAnswersNothing() throws IOException {
		var index = catalogue();
		var log = index.beginChangeTracking();

		index.deleteDocument("1");

		var key = log.snapshot().keys().get(0);
		assertThat(index.getDocumentByKeyTerm(key), is(nullValue()));
	}

	@Test
	public void aDeleteByKeyTermTakesTheDocumentAndIsRecorded() throws IOException {
		var index = catalogue();
		var other = create("copy", definition());
		other.addDocument(doc("1", "Blueberry jam"));

		var log = index.beginChangeTracking();
		index.deleteDocument("1");

		// The other index shares the key field, so the recorded term names its copy
		var key = log.snapshot().keys().get(0);
		var otherLog = other.beginChangeTracking();
		other.deleteDocumentByKeyTerm(key);
		other.commit();

		assertThat(other.getDocument("1"), is(nullValue()));
		assertThat(otherLog.size(), is(1));
	}

	@Test
	public void theDocumentCountIsWhatACommitMadeSearchable() throws IOException {
		var index = catalogue();

		assertThat(index.getDocumentCount(), is(2L));

		index.addDocument(doc("3", "Lingonberry jam"));
		assertThat(index.getDocumentCount(), is(2L));

		index.commit();
		assertThat(index.getDocumentCount(), is(3L));
	}

	/**
	 * A node that loses and regains an index pulls, and the pull replaces the
	 * local state - the in-memory log has to go with it, or tracking would
	 * resume from a log the pulled file does not hold.
	 */
	@Test
	public void aPullResumesTrackingFromThePersistedLog() throws IOException {
		var state = new NodeState(true);
		state.updateOwnership("handover", true);

		var path = indexRoot.resolve("handover");
		Files.createDirectories(path);

		var index = new Index(state, "handover", path, new NoopSync());
		index.pull();
		index.updateDefinition(definition().build());

		index.beginChangeTracking();
		index.addDocument(doc("1", "Blueberry jam"));
		index.commit();

		// Recorded after the commit, so the persisted log does not hold it
		index.addDocument(doc("2", "Rye bread"));

		// The index moves away and comes back, dropping what was not pushed
		state.updateOwnership("handover", false);
		index.reopen();
		state.updateOwnership("handover", true);
		index.reopen();

		var log = index.beginChangeTracking();
		assertThat(log.size(), is(1));

		index.close();
	}

	private static Document doc(String id, String name) {
		return new Document(
			new Document.Value("id", id),
			new Document.Value("name", name)
		);
	}

	private Index catalogue() throws IOException {
		var index = create("catalogue", definition());

		index.addDocument(doc("1", "Blueberry jam"));
		index.addDocument(doc("2", "Rye bread"));
		index.commit();

		return index;
	}

	private static IndexDef.Builder definition() {
		return IndexDef.newBuilder()
			.putFields("id", string().setPrimaryKey(true).build())
			.putFields("name", string().build());
	}

	private static FieldDef.Builder string() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder()
					.setString(StringFieldTypeDef.getDefaultInstance())
			);
	}
}
