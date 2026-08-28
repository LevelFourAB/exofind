package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.Int64FieldTypeDef;
import se.l4.exofind.engine.index.schema.ObjectFieldTypeDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;

/**
 * Tests for reading documents back out of an index: that the order is the
 * order of the keys whatever the index has been through, that a read carries
 * on where the one before it stopped, and that an index that cannot answer
 * with its documents says so instead of answering with part of them.
 */
public class DocumentScanTest extends AbstractIndexTest {
	@Test
	public void everyDocumentIsReadInKeyOrder() throws IOException {
		var index = catalogue();
		index.commit();

		assertThat(keysOf(index, null, 10), contains("1", "2", "3"));
	}

	@Test
	public void theOrderIsTheKeysRatherThanTheOrderTheyWereIndexedIn() throws IOException {
		var index = create("catalogue", definition());

		index.addDocument(doc("3", "Lingonberry jam"));
		index.addDocument(doc("1", "Blueberry jam"));
		index.addDocument(doc("2", "Rye bread"));
		index.commit();

		assertThat(keysOf(index, null, 10), contains("1", "2", "3"));
	}

	@Test
	public void theOrderHoldsAcrossTheSegmentsCommitsLeaveBehind() throws IOException {
		var index = create("catalogue", definition());

		index.addDocument(doc("3", "Lingonberry jam"));
		index.commit();

		index.addDocument(doc("1", "Blueberry jam"));
		index.commit();

		index.addDocument(doc("2", "Rye bread"));
		index.commit();

		assertThat(keysOf(index, null, 10), contains("1", "2", "3"));
	}

	@Test
	public void awholeNumberKeyIsReadInNumberOrder() throws IOException {
		var index = create(
			"measurements",
			IndexDef.newBuilder()
				.putFields(
					"id",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setInt64(Int64FieldTypeDef.getDefaultInstance())
						)
						.setPrimaryKey(true)
						.build()
				)
		);

		index.addDocument(new Document(new Document.Value("id", 100L)));
		index.addDocument(new Document(new Document.Value("id", 2L)));
		index.addDocument(new Document(new Document.Value("id", -5L)));
		index.commit();

		var keys = new ArrayList<Object>();
		index.scanDocuments(null, 10, document -> keys.add(document.get("id")));

		assertThat(keys, contains(-5L, 2L, 100L));
	}

	@Test
	public void aDocumentIsReadAsItWasGiven() throws IOException {
		var index = catalogue();
		index.commit();

		var read = new ArrayList<Document>();
		index.scanDocuments("1", 1, read::add);

		assertThat(read.size(), is(1));
		assertThat(read.get(0).get("id"), is("2"));
		assertThat(read.get(0).get("name"), is("Rye bread"));
	}

	@Test
	public void aReadCarriesOnAfterTheKeyItIsGiven() throws IOException {
		var index = catalogue();
		index.commit();

		assertThat(keysOf(index, "1", 10), contains("2", "3"));
	}

	@Test
	public void aReadCarriesOnAfterAKeyNothingIsIndexedUnder() throws IOException {
		var index = catalogue();
		index.commit();

		assertThat(keysOf(index, "15", 10), contains("2", "3"));
	}

	@Test
	public void aReadCarryingOnPastTheLastKeyReadsNothing() throws IOException {
		var index = catalogue();
		index.commit();

		assertThat(keysOf(index, "3", 10), is(empty()));
	}

	@Test
	public void aReadStopsAtTheLimitItIsGiven() throws IOException {
		var index = catalogue();
		index.commit();

		var read = new ArrayList<Document>();
		var count = index.scanDocuments(null, 2, read::add);

		assertThat(count, is(2));
		assertThat(keysOf(read), contains("1", "2"));
	}

	@Test
	public void oneReadAfterAnotherReadsTheIndexOnce() throws IOException {
		var index = catalogue();
		index.commit();

		var first = new ArrayList<Document>();
		index.scanDocuments(null, 2, first::add);

		var second = new ArrayList<Document>();
		var count = index.scanDocuments(keysOf(first).get(1), 2, second::add);

		assertThat(count, is(1));
		assertThat(keysOf(second), contains("3"));
	}

	@Test
	public void aRemovedDocumentIsNotRead() throws IOException {
		var index = catalogue();
		index.deleteDocument("2");
		index.commit();

		assertThat(keysOf(index, null, 10), contains("1", "3"));
	}

	@Test
	public void aReplacedDocumentIsReadOnceAsItIsNow() throws IOException {
		var index = catalogue();
		index.commit();

		index.addDocument(doc("2", "Sourdough bread"));
		index.commit();

		var read = new ArrayList<Document>();
		index.scanDocuments(null, 10, read::add);

		assertThat(keysOf(read), contains("1", "2", "3"));
		assertThat(read.get(1).get("name"), is("Sourdough bread"));
	}

	@Test
	public void aDocumentWithValuesOfItsOwnIsReadOnceAndWhole() throws IOException {
		var index = create(
			"products",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"variants",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setObject(
									ObjectFieldTypeDef.newBuilder()
										.putFields("size", string().build())
										.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
								)
						)
						.setMultiple(true)
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value(
					"variants",
					new Document(new Document.Value("size", "S"))
				),
				new Document.Value(
					"variants",
					new Document(new Document.Value("size", "M"))
				)
			)
		);
		index.commit();

		var read = new ArrayList<Document>();
		index.scanDocuments(null, 10, read::add);

		assertThat(read.size(), is(1));

		var sizes = read.get(0)
			.getAll("variants")
			.stream()
			.map(value -> ((Document) value).get("size"))
			.toList();

		assertThat(sizes, contains("S", "M"));
	}

	@Test
	public void anEmptyIndexReadsNothing() throws IOException {
		var index = create("catalogue", definition());
		index.commit();

		assertThat(keysOf(index, null, 10), is(empty()));
	}

	@Test
	public void anIndexWithoutAPrimaryKeyIsRefused() throws IOException {
		var index = create(
			"catalogue",
			IndexDef.newBuilder().putFields("name", string().build())
		);

		assertThrows(
			IndexNoPrimaryKeyException.class,
			() -> index.scanDocuments(null, 10, document -> {})
		);
	}

	@Test
	public void anIndexThatKeepsNoCopiesIsRefused() throws IOException {
		var index = create(
			"catalogue",
			definition().setSource(IndexDef.SourceMode.SOURCE_MODE_NONE)
		);

		index.addDocument(doc("1", "Blueberry jam"));
		index.commit();

		assertThrows(
			IndexSourceNotKeptException.class,
			() -> index.scanDocuments(null, 10, document -> {})
		);
	}

	@Test
	public void aClosedIndexIsRefused() throws IOException {
		var index = catalogue();
		index.commit();
		index.close();

		assertThrows(
			IndexClosedException.class,
			() -> index.scanDocuments(null, 10, document -> {})
		);
	}

	/**
	 * Read the keys of everything an index holds from a key on.
	 */
	private static List<Object> keysOf(Index index, Object after, int limit) throws IOException {
		var keys = new ArrayList<Object>();
		index.scanDocuments(after, limit, document -> keys.add(document.get("id")));
		return keys;
	}

	private static List<Object> keysOf(List<Document> documents) {
		return documents.stream().map(document -> document.get("id")).toList();
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
		index.addDocument(doc("3", "Lingonberry jam"));

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
