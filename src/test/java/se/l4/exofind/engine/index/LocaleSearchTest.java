package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.SortConfig;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.SortBy;

/**
 * Tests for locale specific fields end to end - a value is analyzed and
 * collated by the locale it carries, and a search reads the field in the
 * locale it asks for.
 */
public class LocaleSearchTest extends AbstractIndexTest {
	/**
	 * An index whose `name` holds values in English, Swedish and German, with
	 * English assumed for values that carry no locale.
	 */
	private Index localized() throws IOException {
		var index = create(
			"localized",
			IndexDef.newBuilder()
				.putFields("id", string(StringFieldTypeDef.newBuilder()).setPrimaryKey(true).build())
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(
								StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
							)
					)
						.setStored(true)
						.setSort(SortConfig.getDefaultInstance())
						.setLocales(
							FieldDef.LocaleConfig.newBuilder()
								.setDefaultLocale("en")
								.addLocales("sv")
								.addLocales("de")
						)
						.build()
				)
				.putFields(
					"category",
					string(StringFieldTypeDef.newBuilder())
						.setFilter(FilterConfig.getDefaultInstance())
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "red running shoes", "en"),
				new Document.Value("name", "röda löparskor", "sv"),
				new Document.Value("name", "rote Laufschuhe", "de"),
				new Document.Value("category", "shoes")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "fast cars", "en"),
				new Document.Value("name", "snabba bilar", "sv"),
				new Document.Value("category", "cars")
			)
		);

		index.commit();
		return index;
	}

	private static FieldDef.Builder string(StringFieldTypeDef.Builder type) {
		return FieldDef.newBuilder()
			.setType(FieldTypeDef.newBuilder().setString(type));
	}

	private static List<Object> ids(SearchResult result) {
		return result.hits().collect(SearchResult.Hit::id).toList();
	}

	/**
	 * The Swedish `bilar` stems to `bil` by Swedish rules, so searching for
	 * the singular in Swedish finds it. The same query read as English never
	 * would - which is also what the second search shows.
	 */
	@Test
	public void testValuesAreAnalyzedByTheLocaleTheyCarry() throws IOException {
		var index = localized();

		var swedish = index.search(
			SearchRequest.create()
				.withQuery(Query.text("bil"))
				.withLocale("sv")
				.build()
		);
		assertThat(ids(swedish), contains("2"));

		var english = index.search(
			SearchRequest.create()
				.withQuery(Query.text("bil"))
				.build()
		);
		assertThat(ids(english), is(empty()));
	}

	@Test
	public void testSearchWithoutALocaleReadsTheFieldDefault() throws IOException {
		var index = localized();

		// `running` and `runs` only meet through English stemming
		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("runs"))
				.build()
		);

		assertThat(ids(result), contains("1"));
	}

	/**
	 * A locale the field never holds values in falls back to the default of
	 * the field, so a search in it still answers rather than finding nothing.
	 */
	@Test
	public void testSearchLocaleTheFieldDoesNotHoldFallsBack() throws IOException {
		var index = localized();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("running"))
				.withLocale("fr")
				.build()
		);

		assertThat(ids(result), contains("1"));
	}

	/**
	 * A tag says as much as whoever wrote it knew - a browser sends the region
	 * along - so it is matched as closely as the field's declared locales tell
	 * apart rather than exactly.
	 */
	@Test
	public void testSearchLocaleIsMatchedAsCloselyAsTheFieldTellsApart() throws IOException {
		var index = localized();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("bil"))
				.withLocale("sv-SE")
				.build()
		);

		assertThat(ids(result), contains("2"));
	}

	/**
	 * The same matching on the way in, so a value arriving with a region lands
	 * in the variant the search reads rather than being refused.
	 */
	@Test
	public void testValueLocaleIsMatchedAsCloselyAsTheFieldTellsApart() throws IOException {
		var index = localized();

		index.addDocument(
			new Document(
				new Document.Value("id", "3"),
				new Document.Value("name", "vandringskängor", "sv-SE")
			)
		);
		index.commit();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("vandringskängor"))
				.withLocale("sv")
				.build()
		);

		assertThat(ids(result), contains("3"));
	}

	@Test
	public void testSearchInALocaleTheEngineDoesNotSupportIsRefused() throws IOException {
		var index = localized();

		assertThrows(
			IndexException.class,
			() -> index.search(
				SearchRequest.create()
					.withQuery(Query.text("bil"))
					.withLocale("xx")
					.build()
			)
		);
	}

	@Test
	public void testValueInAnUndeclaredLocaleIsRefused() throws IOException {
		var index = localized();

		var e = assertThrows(
			ValidationException.class,
			() -> index.addDocument(
				new Document(
					new Document.Value("id", "3"),
					new Document.Value("name", "chaussures", "fr")
				)
			)
		);

		assertThat(e.getErrors().get(0).getCode(), is("index:update:locale_not_declared"));
	}

	@Test
	public void testLocaleOnAFieldThatIsNotLocaleSpecificIsRefused() throws IOException {
		var index = localized();

		var e = assertThrows(
			ValidationException.class,
			() -> index.addDocument(
				new Document(
					new Document.Value("id", "3"),
					new Document.Value("category", "skor", "sv")
				)
			)
		);

		assertThat(e.getErrors().get(0).getCode(), is("index:update:locale_not_allowed"));
	}

	@Test
	public void testValueWithoutALocaleIsAssumedTheDefault() throws IOException {
		var index = localized();

		index.addDocument(
			new Document(
				new Document.Value("id", "3"),
				new Document.Value("name", "walking boots")
			)
		);
		index.commit();

		// English stemming is what makes `walk` find `walking`
		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("walk"))
				.build()
		);

		assertThat(ids(result), contains("3"));
	}

	/**
	 * Swedish sorts ö after z, German sorts it with o. The same two documents
	 * order differently depending on the locale the search reads them in,
	 * because each locale's variant carries its own collation key.
	 */
	@Test
	public void testSortFollowsTheLocaleOfTheSearch() throws IOException {
		var index = create(
			"sorted",
			IndexDef.newBuilder()
				.putFields("id", string(StringFieldTypeDef.newBuilder()).setPrimaryKey(true).build())
				.putFields(
					"name",
					string(StringFieldTypeDef.newBuilder())
						.setSort(SortConfig.getDefaultInstance())
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
				new Document.Value("id", "umlaut"),
				new Document.Value("name", "öken", "sv"),
				new Document.Value("name", "öde", "de")
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "plain"),
				new Document.Value("name", "zebra", "sv"),
				new Document.Value("name", "zebra", "de")
			)
		);
		index.commit();

		var swedish = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("name"))
				.withLocale("sv")
				.build()
		);
		assertThat(ids(swedish), contains("plain", "umlaut"));

		var german = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("name"))
				.withLocale("de")
				.build()
		);
		assertThat(ids(german), contains("umlaut", "plain"));
	}

	/**
	 * Japanese text has no spaces, so the words a search finds only exist
	 * because the locale's own tokenizer found them - and the conjugated
	 * 食べました only answers a search for 食べる because both reduce to the
	 * dictionary form. This is the locale tokenizer working through the whole
	 * indexing and search path.
	 */
	@Test
	public void testJapaneseIsSegmentedAndLemmatizedEndToEnd() throws IOException {
		var index = create(
			"japanese",
			IndexDef.newBuilder()
				.putFields("id", string(StringFieldTypeDef.newBuilder()).setPrimaryKey(true).build())
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(
								StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
							)
					)
						.setLocales(
							FieldDef.LocaleConfig.newBuilder().setDefaultLocale("ja")
						)
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "美味しいラーメンを食べました", "ja")
			)
		);
		index.commit();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("食べる"))
				.withLocale("ja")
				.build()
		);

		assertThat(ids(result), contains("1"));

		var noodles = index.search(
			SearchRequest.create()
				.withQuery(Query.text("ラーメン"))
				.withLocale("ja")
				.build()
		);

		assertThat(ids(noodles), contains("1"));
	}

	/**
	 * A required field is satisfied by a value in any locale - being required
	 * is about the document saying something for the field, not about saying
	 * it in every language.
	 */
	@Test
	public void testRequiredFieldIsSatisfiedByAnyLocale() throws IOException {
		var index = create(
			"required",
			IndexDef.newBuilder()
				.putFields("id", string(StringFieldTypeDef.newBuilder()).setPrimaryKey(true).build())
				.putFields(
					"name",
					string(StringFieldTypeDef.newBuilder())
						.setRequired(true)
						.setStored(true)
						.setLocales(
							FieldDef.LocaleConfig.newBuilder()
								.setDefaultLocale("en")
								.addLocales("sv")
						)
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "bara svenska", "sv")
			)
		);
	}

	@Test
	public void testValuesComeBackWithTheirLocales() throws IOException {
		var index = localized();

		var document = index.getDocument("1");

		var locales = Arrays.stream(document.fields())
			.filter(value -> value.name().equals("name"))
			.map(Document.Value::locale)
			.toList();

		assertThat(locales, containsInAnyOrder("en", "sv", "de"));
	}

	/**
	 * Without a copy of the document, results are read from the stored
	 * variants - all of them, which is what declaring the locales of a field
	 * makes known.
	 */
	@Test
	public void testStoredVariantsAllComeBackWhenFieldsAreSelected() throws IOException {
		var index = create(
			"stored",
			IndexDef.newBuilder()
				.setSource(IndexDef.SourceMode.SOURCE_MODE_NONE)
				.putFields("id", string(StringFieldTypeDef.newBuilder()).setPrimaryKey(true).build())
				.putFields(
					"name",
					string(StringFieldTypeDef.newBuilder())
						.setStored(true)
						.setLocales(
							FieldDef.LocaleConfig.newBuilder()
								.setDefaultLocale("en")
								.addLocales("sv")
						)
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "shoes", "en"),
				new Document.Value("name", "skor", "sv")
			)
		);
		index.commit();

		var result = index.search(
			SearchRequest.create()
				.withFields("name")
				.build()
		);

		var values = Arrays.stream(result.hits().getFirst().document().fields())
			.filter(value -> value.name().equals("name"))
			.map(value -> value.locale() + ":" + value.value())
			.toList();

		assertThat(values, containsInAnyOrder("en:shoes", "sv:skor"));
	}
}
