package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FloatFieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;

/**
 * Tests for changing some of the fields of a document that is already indexed.
 */
public class DocumentUpdateTest extends AbstractIndexTest {
	@Test
	public void aFieldThePatchNamesIsReplacedAndTheRestIsLeftAlone() throws IOException {
		var index = catalogue();

		assertTrue(index.updateDocument(patch(set("id", "1"), set("price", 34.5f))));
		index.commit();

		var doc = index.getDocument("1");
		assertThat(doc.get("price"), is(34.5f));
		assertThat(doc.get("name"), is("Blueberry jam"));
		assertThat(doc.get("category"), is("preserves"));
	}

	@Test
	public void aFieldThePatchNamesWithoutGivingItAnythingIsEmptied() throws IOException {
		var index = catalogue();

		assertTrue(index.updateDocument(
			new DocumentPatch(
				Sets.immutable.of("id", "category"),
				Lists.immutable.of(new Document.Value("id", "1"))
			)
		));
		index.commit();

		var doc = index.getDocument("1");
		assertThat(doc.get("category"), is(nullValue()));
		assertThat(doc.get("name"), is("Blueberry jam"));
	}

	/**
	 * The point of the whole thing: a patch reads what is indexed now, and
	 * nothing has to be committed in between for the one before it to count.
	 */
	@Test
	public void patchesOfTheSameDocumentApplyInTheOrderTheyAreGiven() throws IOException {
		var index = catalogue();

		index.updateDocument(patch(set("id", "1"), set("price", 10f)));
		index.updateDocument(patch(set("id", "1"), set("category", "jam")));
		index.updateDocument(patch(set("id", "1"), set("price", 20f)));
		index.commit();

		var doc = index.getDocument("1");
		assertThat(doc.get("price"), is(20f));
		assertThat(doc.get("category"), is("jam"));
		assertThat(doc.get("name"), is("Blueberry jam"));
	}

	@Test
	public void aPatchSeesADocumentIndexedSinceTheLastCommit() throws IOException {
		var index = catalogue();

		index.addDocument(
			new Document(
				new Document.Value("id", "9"),
				new Document.Value("name", "Cloudberry jam"),
				new Document.Value("price", 60f)
			)
		);

		assertTrue(index.updateDocument(patch(set("id", "9"), set("price", 55f))));
		index.commit();

		var doc = index.getDocument("9");
		assertThat(doc.get("price"), is(55f));
		assertThat(doc.get("name"), is("Cloudberry jam"));
	}

	@Test
	public void aKeyNothingIsIndexedUnderChangesNothing() throws IOException {
		var index = catalogue();

		assertFalse(index.updateDocument(patch(set("id", "404"), set("price", 1f))));

		index.commit();
		assertThat(index.getDocument("404"), is(nullValue()));
	}

	@Test
	public void aDocumentRemovedSinceTheLastCommitIsGone() throws IOException {
		var index = catalogue();

		index.deleteDocument("1");

		assertFalse(index.updateDocument(patch(set("id", "1"), set("price", 1f))));
	}

	@Test
	public void aDocumentRemovedByAQuerySinceTheLastCommitIsGone() throws IOException {
		var index = catalogue();
		index.commit();

		index.deleteByQuery(Lists.immutable.empty(), null);

		assertFalse(index.updateDocument(patch(set("id", "1"), set("price", 1f))));
	}

	@Test
	public void aPatchNamingNoPrimaryKeyIsRefused() throws IOException {
		var index = catalogue();

		assertThrows(
			ValidationException.class,
			() -> index.updateDocument(patch(set("price", 1f)))
		);
	}

	/**
	 * The merged document is indexed as if it had been given whole, so what the
	 * definition refuses about it is refused here too.
	 */
	@Test
	public void aPatchThatLeavesTheDocumentUnacceptableChangesNothing() throws IOException {
		var index = catalogue();

		assertThrows(
			ValidationException.class,
			() -> index.updateDocument(patch(set("id", "1"), set("price", "not a number")))
		);

		index.commit();
		assertThat(index.getDocument("1").get("price"), is(24.5f));
	}

	@Test
	public void anIndexThatKeepsNoCopyOfItsDocumentsRefusesToChangeSomeOfTheirFields()
		throws IOException {
		var index = create(
			"sourceless",
			definition().setSource(IndexDef.SourceMode.SOURCE_MODE_NONE)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Blueberry jam")
			)
		);

		assertThrows(
			IndexSourceNotKeptException.class,
			() -> index.updateDocument(patch(set("id", "1"), set("price", 1f)))
		);
	}

	@Test
	public void aMultipleFieldIsReplacedByEverythingThePatchGivesIt() throws IOException {
		var index = catalogue();

		assertTrue(index.updateDocument(
			patch(set("id", "1"), set("tags", "sale"), set("tags", "new"))
		));
		index.commit();

		assertThat(
			index.getDocument("1").getAll("tags"),
			contains("sale", "new")
		);
	}

	@Test
	public void aDocumentThatWasNeverPatchedIsLeftAsItWas() throws IOException {
		var index = catalogue();

		index.updateDocument(patch(set("id", "1"), set("price", 1f)));
		index.commit();

		var other = index.getDocument("2");
		assertThat(other, is(notNullValue()));
		assertThat(other.get("price"), is(12f));
	}

	private static DocumentPatch patch(Document.Value... values) {
		var names = Sets.mutable.<String>empty();
		for(var value : values) {
			names.add(value.name());
		}

		return new DocumentPatch(names.toImmutable(), Lists.immutable.of(values));
	}

	private static Document.Value set(String name, Object value) {
		return new Document.Value(name, value);
	}

	private Index catalogue() throws IOException {
		var index = create("catalogue", definition());

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Blueberry jam"),
				new Document.Value("category", "preserves"),
				new Document.Value("price", 24.5f),
				new Document.Value("tags", "berry"),
				new Document.Value("tags", "sweet")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "Rye bread"),
				new Document.Value("category", "bread"),
				new Document.Value("price", 12f)
			)
		);

		return index;
	}

	private static IndexDef.Builder definition() {
		return IndexDef.newBuilder()
			.putFields("id", string().setPrimaryKey(true).build())
			.putFields("name", string().build())
			.putFields("category", string().build())
			.putFields("tags", string().setMultiple(true).build())
			.putFields(
				"price",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder()
							.setFloat(FloatFieldTypeDef.getDefaultInstance())
					)
					.build()
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
