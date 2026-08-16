package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;

/**
 * Tests compound splitting end to end: the languages that glue words
 * together answer a search for a part with the compounds built from it.
 */
public class DecompoundSearchTest extends AbstractIndexTest {
	private Index catalog(StringFieldTypeDef.TextUsageConfig matching) throws IOException {
		var index = create(
			"catalog",
			IndexDef.newBuilder()
				.putFields(
					"id",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setString(StringFieldTypeDef.getDefaultInstance())
						)
						.setPrimaryKey(true)
						.build()
				)
				.putFields(
					"name",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setString(
									StringFieldTypeDef.newBuilder().setMatching(matching)
								)
						)
						.setLocales(
							FieldDef.LocaleConfig.newBuilder()
								.setDefaultLocale("sv")
								.addLocales("de")
						)
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "regnjacka"),
				new Document.Value("name", "snygg regnjacka", "sv"),
				new Document.Value("name", "schicke Regenjacke", "de")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "jacka"),
				new Document.Value("name", "vanlig jacka", "sv"),
				new Document.Value("name", "einfache Jacke", "de")
			)
		);

		index.commit();
		return index;
	}

	private Iterable<Object> search(Index index, String text, String locale) throws IOException {
		return index.search(
			SearchRequest.create()
				.withQuery(Query.text(text))
				.withLocale(locale)
				.build()
		).hits().collect(hit -> hit.id()).toList();
	}

	/**
	 * The point of the feature: the part finds the compound, in each
	 * language by its own rules.
	 */
	@Test
	public void testAPartFindsTheCompoundsBuiltFromIt() throws IOException {
		var index = catalog(StringFieldTypeDef.TextUsageConfig.getDefaultInstance());

		assertThat(
			search(index, "jacka", "sv"),
			containsInAnyOrder("regnjacka", "jacka")
		);
		assertThat(
			search(index, "Jacke", "de"),
			containsInAnyOrder("regnjacka", "jacka")
		);
	}

	/**
	 * The other direction stays precise: a compound query matches through
	 * the whole word, not through its parts, so it does not flood with every
	 * document holding only a part.
	 */
	@Test
	public void testACompoundQueryDoesNotMatchBareParts() throws IOException {
		var index = catalog(StringFieldTypeDef.TextUsageConfig.getDefaultInstance());

		assertThat(search(index, "regnjacka", "sv"), contains("regnjacka"));
	}

	@Test
	public void testTurnedOffTheCompoundStaysWhole() throws IOException {
		var index = catalog(
			StringFieldTypeDef.TextUsageConfig.newBuilder()
				.setDecompound(
					StringFieldTypeDef.TextUsageConfig.DecompoundMode.DECOMPOUND_MODE_NONE
				)
				.build()
		);

		assertThat(search(index, "jacka", "sv"), contains("jacka"));
	}
}
