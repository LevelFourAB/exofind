package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

import java.io.IOException;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.ObjectFieldTypeDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;

/**
 * Tests that Lucene stores the norms of the analyzed fields of an index
 * densely - one value per Lucene document, whether or not the document holds
 * text for the field.
 *
 * <p>The index here holds everything that leaves a Lucene document without a
 * field: an optional field some documents omit, and an object field whose
 * values are Lucene documents of their own, carrying none of the fields of the
 * documents around them.
 */
public class NormDensityTest extends AbstractIndexTest {
	private static final String TITLE = FieldNames.name("title", null, FieldNames.MATCHING);
	private static final String SUBTITLE =
		FieldNames.name("subtitle", null, FieldNames.MATCHING);
	private static final String VARIANT_NAME =
		FieldNames.name("variants.name", null, FieldNames.MATCHING);

	/**
	 * How many Lucene documents the catalogue is written as: four documents,
	 * and three values of an object field.
	 */
	private static final int LUCENE_DOCUMENTS = 7;

	@Test
	public void testEveryDocumentHasANormForEveryAnalyzedField() throws IOException {
		catalogue();

		try(var reader = DirectoryReader.open(FSDirectory.open(indexRoot.resolve("catalogue")))) {
			assertThat(reader.maxDoc(), is(LUCENE_DOCUMENTS));

			var checked = 0;
			for(var leaf : reader.leaves()) {
				var segment = leaf.reader();

				for(var info : segment.getFieldInfos()) {
					if(!info.hasNorms()) {
						continue;
					}

					var norms = segment.getNormValues(info.name);
					var seen = 0;
					while(norms.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
						seen++;
					}

					assertThat(
						"norms of " + info.name + " cover every document of the segment",
						seen,
						is(segment.maxDoc())
					);

					checked++;
				}
			}

			assertThat("fields with norms were found", checked, is(greaterThan(0)));
		}
	}

	/**
	 * An empty entry writes no terms, so what a field says about the collection
	 * - how many documents hold it and how long their values are - counts the
	 * documents that really have text.
	 */
	@Test
	public void testEmptyEntriesAreLeftOutOfTheCollectionStatistics() throws IOException {
		catalogue();

		try(var reader = DirectoryReader.open(FSDirectory.open(indexRoot.resolve("catalogue")))) {
			assertThat(documentsWithText(reader, TITLE), is(4));
			assertThat(documentsWithText(reader, SUBTITLE), is(2));
			assertThat(documentsWithText(reader, VARIANT_NAME), is(3));
		}
	}

	/**
	 * How many Lucene documents hold at least one term of a field, summed over
	 * the segments the way scoring reads it per segment.
	 */
	private static int documentsWithText(DirectoryReader reader, String field)
		throws IOException
	{
		var total = 0;
		for(var leaf : reader.leaves()) {
			total += documentsWithText(leaf.reader(), field);
		}

		return total;
	}

	private static int documentsWithText(LeafReader segment, String field) throws IOException {
		var terms = segment.terms(field);
		return terms == null ? 0 : terms.getDocCount();
	}

	/**
	 * A catalogue where every kind of gap turns up: a document with several
	 * values of the object field, one with a single value, one with none, and
	 * two that leave the optional field out.
	 */
	private void catalogue() throws IOException {
		var index = create("catalogue", definition());

		index.addDocument(
			new Document(
				new Document.Value("id", "a"),
				new Document.Value("title", "Atlas"),
				new Document.Value("subtitle", "Maps"),
				new Document.Value("variants", new Document(new Document.Value("name", "Paper"))),
				new Document.Value("variants", new Document(new Document.Value("name", "Cloth")))
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "b"),
				new Document.Value("title", "Bridges"),
				new Document.Value("variants", new Document(new Document.Value("name", "Steel")))
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "c"),
				new Document.Value("title", "Compass"),
				new Document.Value("subtitle", "Needles")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "d"),
				new Document.Value("title", "Dunes")
			)
		);

		index.commit();
	}

	private static IndexDef.Builder definition() {
		return IndexDef.newBuilder()
			.putFields(
				"id",
				FieldDef.newBuilder()
					.setPrimaryKey(true)
					.setType(
						FieldTypeDef.newBuilder()
							.setString(StringFieldTypeDef.getDefaultInstance())
					)
					.build()
			)
			.putFields("title", matching())
			.putFields("subtitle", matching())
			.putFields(
				"variants",
				FieldDef.newBuilder()
					.setMultiple(true)
					.setType(
						FieldTypeDef.newBuilder().setObject(
							ObjectFieldTypeDef.newBuilder()
								.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
								.putFields("name", matching())
						)
					)
					.build()
			);
	}

	private static FieldDef matching() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setString(
					StringFieldTypeDef.newBuilder()
						.setMatching(StringFieldTypeDef.TextUsageConfig.getDefaultInstance())
				)
			)
			.build();
	}
}
