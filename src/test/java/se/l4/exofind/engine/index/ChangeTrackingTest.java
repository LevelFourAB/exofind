package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.lucene.util.BytesRef;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.impl.factory.Lists;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.matchers.Matchers;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;

/**
 * Tests for recording which documents change while an index tracks its
 * changes, and for holding writes still.
 */
public class ChangeTrackingTest extends AbstractIndexTest {
	@Test
	public void nothingIsRecordedWhileNothingTracks() throws IOException {
		var index = catalogue();

		index.addDocument(doc("3", "Lingonberry jam", "preserves"));

		assertTrue(index.getChangeLog().isEmpty());
	}

	@Test
	public void anAddedDocumentIsRecorded() throws IOException {
		var index = catalogue();
		var log = index.beginChangeTracking();

		index.addDocument(doc("3", "Lingonberry jam", "preserves"));

		assertThat(log.size(), is(1));
	}

	@Test
	public void writingTheSameDocumentTwiceRecordsOneEntry() throws IOException {
		var index = catalogue();
		var log = index.beginChangeTracking();

		index.addDocument(doc("3", "Lingonberry jam", "preserves"));
		index.addDocument(doc("3", "Lingonberry jam", "jam"));

		assertThat(log.size(), is(1));
	}

	@Test
	public void aRemovedDocumentIsRecorded() throws IOException {
		var index = catalogue();
		var log = index.beginChangeTracking();

		index.deleteDocument("1");

		assertThat(log.size(), is(1));
	}

	@Test
	public void aPatchedDocumentIsRecorded() throws IOException {
		var index = catalogue();
		var log = index.beginChangeTracking();

		assertTrue(index.updateDocument(DocumentPatch.replacing(
			Sets.immutable.of("id", "category"),
			Lists.immutable.of(
				new Document.Value("id", "1"),
				new Document.Value("category", "jam")
			)
		)));

		assertThat(log.size(), is(1));
	}

	@Test
	public void aDeleteByQueryRecordsTheDocumentsItTakes() throws IOException {
		var index = catalogue();
		index.commit();

		var log = index.beginChangeTracking();
		var deleted = index.deleteByQuery(
			Lists.immutable.of(Query.field("category", Matchers.equalTo("preserves"))),
			null
		);

		assertThat(deleted, is(1));
		assertThat(log.size(), is(1));
	}

	@Test
	public void aDeleteByQueryRecordsDocumentsNotYetCommitted() throws IOException {
		var index = catalogue();
		index.commit();

		var log = index.beginChangeTracking();
		index.addDocument(doc("3", "Lingonberry jam", "preserves"));

		index.deleteByQuery(Lists.immutable.empty(), null);

		// The two committed documents join the one recorded by its own add
		assertThat(log.size(), is(3));
	}

	@Test
	public void theSameKeyDeletedAndAddedAgainStaysOneEntry() throws IOException {
		var index = catalogue();
		var log = index.beginChangeTracking();

		index.deleteDocument("1");
		index.addDocument(doc("1", "Blueberry jam", "preserves"));

		assertThat(log.size(), is(1));
	}

	@Test
	public void aCommittedLogIsResumedByTheNextWriter() throws IOException {
		var index = create("resumed", definition());
		index.addDocument(doc("1", "Blueberry jam", "preserves"));
		index.commit();

		index.beginChangeTracking();
		index.deleteDocument("1");
		index.commit();
		index.close();

		var reopened = create("resumed");
		var log = reopened.beginChangeTracking();

		assertThat(log.size(), is(1));
	}

	@Test
	public void endingTrackingDropsTheLogAndItsFile() throws IOException {
		var index = catalogue();
		index.beginChangeTracking();
		index.addDocument(doc("3", "Lingonberry jam", "preserves"));
		index.commit();

		index.endChangeTracking();

		assertTrue(index.getChangeLog().isEmpty());
		assertFalse(Files.exists(indexRoot.resolve("catalogue").resolve(Index.CHANGES_FILE)));

		index.addDocument(doc("4", "Rosehip soup", "soup"));
		assertTrue(index.getChangeLog().isEmpty());
	}

	@Test
	public void beginningTrackingTwiceAnswersTheSameLog() throws IOException {
		var index = catalogue();

		var first = index.beginChangeTracking();
		first.record(new BytesRef("marker"));

		assertThat(index.beginChangeTracking().size(), is(1));
	}

	@Test
	public void anIndexWithoutAPrimaryKeyRefusesToTrack() throws IOException {
		var index = create(
			"keyless",
			IndexDef.newBuilder()
				.putFields("name", string().build())
		);

		assertThrows(IndexNoPrimaryKeyException.class, index::beginChangeTracking);
	}

	@Test
	public void anIndexThatKeepsNoCopyOfItsDocumentsRefusesToTrack() throws IOException {
		var index = create(
			"sourceless",
			definition().setSource(IndexDef.SourceMode.SOURCE_MODE_NONE)
		);

		assertThrows(IndexSourceNotKeptException.class, index::beginChangeTracking);
	}

	@Test
	public void aHeldWriteWaitsUntilTheHoldIsClosed() throws Exception {
		var index = catalogue();

		var started = new CountDownLatch(1);
		var finished = new CountDownLatch(1);

		var hold = index.holdWrites();
		var writer = new Thread(() -> {
			started.countDown();
			try {
				index.addDocument(doc("3", "Lingonberry jam", "preserves"));
			} catch(IOException e) {
				throw new RuntimeException(e);
			}
			finished.countDown();
		});
		writer.start();

		try {
			assertTrue(started.await(5, TimeUnit.SECONDS));
			assertFalse(finished.await(200, TimeUnit.MILLISECONDS));
		} finally {
			hold.close();
		}

		assertTrue(finished.await(5, TimeUnit.SECONDS));
		writer.join(5000);
	}

	@Test
	public void theChangeLogIsEmptyAtTheMomentOfAHold() throws Exception {
		var index = catalogue();
		var log = index.beginChangeTracking();

		index.addDocument(doc("3", "Lingonberry jam", "preserves"));

		try(var hold = index.holdWrites()) {
			log.forget(log.snapshot());
			assertTrue(log.isEmpty());
		}
	}

	private static Document doc(String id, String name, String category) {
		return new Document(
			new Document.Value("id", id),
			new Document.Value("name", name),
			new Document.Value("category", category)
		);
	}

	private Index catalogue() throws IOException {
		var index = create("catalogue", definition());

		index.addDocument(doc("1", "Blueberry jam", "preserves"));
		index.addDocument(doc("2", "Rye bread", "bread"));

		return index;
	}

	private static IndexDef.Builder definition() {
		return IndexDef.newBuilder()
			.putFields("id", string().setPrimaryKey(true).build())
			.putFields("name", string().build())
			.putFields(
				"category",
				string().setFilter(FilterConfig.getDefaultInstance()).build()
			);
	}

	private static FieldDef.Builder string() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder()
					.setString(StringFieldTypeDef.getDefaultInstance())
			);
	}
}
