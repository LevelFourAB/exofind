package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.FacetConfig;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.SortConfig;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.SortBy;
import se.l4.exofind.engine.query.matchers.Matchers;

/**
 * Tests for an index that fills the locales a document holds no value in.
 *
 * Without it a search naming a locale only sees the documents translated into
 * it, and the rest are missing rather than ranked lower - so what these check
 * is that an untranslated document is matched, ordered, counted, filtered and
 * answered like any other.
 */
public class LocaleFallbackSearchTest extends AbstractIndexTest {
	private static FieldDef.Builder string(StringFieldTypeDef.Builder type) {
		return FieldDef.newBuilder()
			.setType(FieldTypeDef.newBuilder().setString(type));
	}

	private static List<Object> ids(SearchResult result) {
		return result.hits().collect(SearchResult.Hit::id).toList();
	}

	/**
	 * What one hit came back with for `name`, as `locale:value`, so a test says
	 * both which language answered and what it held.
	 */
	private static List<String> names(SearchResult result, Object id) {
		var hit = result.hits().detect(candidate -> candidate.id().equals(id));

		return Arrays.stream(hit.document().fields())
			.filter(value -> value.name().equals("name"))
			.map(value -> value.locale() + ":" + value.value())
			.toList();
	}

	/**
	 * A catalogue whose `name` is English by default and also holds Swedish,
	 * with the locales a document leaves empty filled from English.
	 *
	 * Document `1` is translated, `2` is not - which is the pair every test
	 * here turns on.
	 */
	private Index catalogue() throws IOException {
		var index = create(
			"catalogue",
			IndexDef.newBuilder()
				.setLocaleFallback(
					IndexDef.LocaleFallbackConfig.newBuilder().addChain("en")
				)
				.putFields("id", string(StringFieldTypeDef.newBuilder()).setPrimaryKey(true).build())
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(StringFieldTypeDef.TextUsageConfig.getDefaultInstance())
					)
						.setStored(true)
						.setFilter(FilterConfig.getDefaultInstance())
						.setSort(SortConfig.getDefaultInstance())
						.setFacet(FacetConfig.getDefaultInstance())
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

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "gloves", "en")
			)
		);

		index.commit();
		return index;
	}

	/**
	 * The hole this closes: without a fallback a Swedish search sees only the
	 * translated document, whatever it asks for.
	 */
	@Test
	public void testUntranslatedDocumentIsFoundInALocaleItNeverHeld() throws IOException {
		var index = catalogue();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("gloves"))
				.withLocale("sv")
				.build()
		);

		assertThat(ids(result), contains("2"));
	}

	/**
	 * A document that says the locale itself is left alone - the fallback fills
	 * the gaps rather than adding to what is there.
	 */
	@Test
	public void testTranslationWinsOverTheFallback() throws IOException {
		var index = catalogue();

		var swedish = index.search(
			SearchRequest.create()
				.withQuery(Query.text("skor"))
				.withLocale("sv")
				.build()
		);
		assertThat(ids(swedish), contains("1"));

		/*
		 * The English word for the same thing reaches the Swedish variant of
		 * the untranslated document only, because that is the only one it was
		 * written into.
		 */
		var english = index.search(
			SearchRequest.create()
				.withQuery(Query.text("shoes"))
				.withLocale("sv")
				.build()
		);
		assertThat(ids(english), is(empty()));
	}

	/**
	 * An untranslated document orders by the value it was filled with rather
	 * than as one that has no value at all, which would clump it at one end
	 * however its name reads.
	 */
	@Test
	public void testUntranslatedDocumentSortsByItsFilledValue() throws IOException {
		var index = catalogue();

		var result = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("name"))
				.withLocale("sv")
				.build()
		);

		// `gloves` before `skor`, rather than the untranslated document last
		assertThat(ids(result), contains("2", "1"));
	}

	@Test
	public void testUntranslatedDocumentIsCountedByItsFilledValue() throws IOException {
		var index = catalogue();

		var result = index.search(
			SearchRequest.create()
				.addFacet(Facet.of("name"))
				.withLocale("sv")
				.build()
		);

		assertThat(
			result.facets().get("name").values(),
			containsInAnyOrder(
				new SearchResult.Facet.Value("skor", 1),
				new SearchResult.Facet.Value("gloves", 1)
			)
		);
	}

	@Test
	public void testUntranslatedDocumentIsFilteredByItsFilledValue() throws IOException {
		var index = catalogue();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.field("name", Matchers.equalTo("gloves")))
				.withLocale("sv")
				.build()
		);

		assertThat(ids(result), contains("2"));
	}

	/**
	 * The copies live in the Lucene fields alone. What comes back is the
	 * document as it was given, so a field still reads in the locales it was
	 * given in and a caller can tell a translation from a gap.
	 */
	@Test
	public void testTheFilledValueIsInvisibleInResults() throws IOException {
		var index = catalogue();

		var document = index.getDocument("2");

		var locales = Arrays.stream(document.fields())
			.filter(value -> value.name().equals("name"))
			.map(Document.Value::locale)
			.toList();

		assertThat(locales, contains("en"));
	}

	/**
	 * A filled value is collated as the locale it fills rather than as the one
	 * it came from, which is what keeps the keys in a variant comparable with
	 * each other.
	 *
	 * Swedish sorts ö after z where English sorts it with o, so two untranslated
	 * documents ordered in Swedish come back in the Swedish order - which they
	 * could not if their keys had been made by the English collator.
	 */
	@Test
	public void testFilledValuesAreCollatedAsTheLocaleTheyFill() throws IOException {
		var index = create(
			"collated",
			IndexDef.newBuilder()
				.setLocaleFallback(IndexDef.LocaleFallbackConfig.newBuilder())
				.putFields("id", string(StringFieldTypeDef.newBuilder()).setPrimaryKey(true).build())
				.putFields(
					"name",
					string(StringFieldTypeDef.newBuilder())
						.setSort(SortConfig.getDefaultInstance())
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
				new Document.Value("id", "umlaut"),
				new Document.Value("name", "öl", "en")
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "plain"),
				new Document.Value("name", "zebra", "en")
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

		var english = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("name"))
				.withLocale("en")
				.build()
		);
		assertThat(ids(english), contains("umlaut", "plain"));
	}

	/**
	 * An index that falls back without naming a chain sends every field to its
	 * own default locale, which is the whole of what most definitions want.
	 */
	@Test
	public void testAnEmptyChainFallsBackToTheFieldDefault() throws IOException {
		var index = create(
			"default",
			IndexDef.newBuilder()
				.setLocaleFallback(IndexDef.LocaleFallbackConfig.newBuilder())
				.putFields("id", string(StringFieldTypeDef.newBuilder()).setPrimaryKey(true).build())
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(StringFieldTypeDef.TextUsageConfig.getDefaultInstance())
					)
						.setLocales(
							FieldDef.LocaleConfig.newBuilder()
								.setDefaultLocale("en")
								.addLocales("de")
						)
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "gloves")
			)
		);
		index.commit();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("gloves"))
				.withLocale("de")
				.build()
		);

		assertThat(ids(result), contains("1"));
	}

	/**
	 * The chain is tried in order, so a document holding a middle entry is
	 * filled from that rather than from the one behind it.
	 */
	@Test
	public void testTheChainDecidesWhichLocaleIsTakenFrom() throws IOException {
		var index = create(
			"chained",
			IndexDef.newBuilder()
				.setLocaleFallback(
					IndexDef.LocaleFallbackConfig.newBuilder()
						.addChain("da")
						.addChain("en")
				)
				.putFields("id", string(StringFieldTypeDef.newBuilder()).setPrimaryKey(true).build())
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(StringFieldTypeDef.TextUsageConfig.getDefaultInstance())
					)
						.setLocales(
							FieldDef.LocaleConfig.newBuilder()
								.setDefaultLocale("en")
								.addLocales("da")
								.addLocales("no")
						)
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "gloves", "en"),
				new Document.Value("name", "handsker", "da")
			)
		);
		index.commit();

		// Danish comes first in the chain, so Norwegian is filled from it
		var danish = index.search(
			SearchRequest.create()
				.withQuery(Query.text("handsker"))
				.withLocale("no")
				.build()
		);
		assertThat(ids(danish), contains("1"));

		var english = index.search(
			SearchRequest.create()
				.withQuery(Query.text("gloves"))
				.withLocale("no")
				.build()
		);
		assertThat(ids(english), is(empty()));
	}

	/**
	 * A field that would rather keep the gap than the copies says so, and is
	 * left alone while the rest of the index is filled.
	 */
	@Test
	public void testAFieldCanKeepItsGaps() throws IOException {
		var index = create(
			"opted-out",
			IndexDef.newBuilder()
				.setLocaleFallback(IndexDef.LocaleFallbackConfig.newBuilder().addChain("en"))
				.putFields("id", string(StringFieldTypeDef.newBuilder()).setPrimaryKey(true).build())
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(StringFieldTypeDef.TextUsageConfig.getDefaultInstance())
					)
						.setLocales(
							FieldDef.LocaleConfig.newBuilder()
								.setDefaultLocale("en")
								.addLocales("sv")
						)
						.build()
				)
				.putFields(
					"description",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(StringFieldTypeDef.TextUsageConfig.getDefaultInstance())
					)
						.setLocales(
							FieldDef.LocaleConfig.newBuilder()
								.setDefaultLocale("en")
								.addLocales("sv")
								.setFallback(FieldDef.LocaleConfig.Fallback.FALLBACK_DISABLED)
						)
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "gloves", "en"),
				new Document.Value("description", "warm and woollen", "en")
			)
		);
		index.commit();

		var name = index.search(
			SearchRequest.create()
				.withQuery(Query.text("gloves").withField("name"))
				.withLocale("sv")
				.build()
		);
		assertThat(ids(name), contains("1"));

		var description = index.search(
			SearchRequest.create()
				.withQuery(Query.text("woollen").withField("description"))
				.withLocale("sv")
				.build()
		);
		assertThat(ids(description), is(empty()));
	}

	/**
	 * A search answers a locale specific field in the variant it read it in, and
	 * for an untranslated document that variant holds what it was filled with -
	 * so a Swedish search has something to show for it rather than a hit with no
	 * name at all.
	 */
	@Test
	public void testUntranslatedDocumentAnswersWithTheValueItWasFilledWith() throws IOException {
		var index = catalogue();

		/*
		 * Named and stored on its own, so the answer is read from the stored
		 * variants; asked for as part of the whole document, so it is read from
		 * the copy. Both say the same thing about a filled variant.
		 */
		var named = index.search(
			SearchRequest.create()
				.withLocale("sv")
				.withFields("name")
				.build()
		);

		assertThat(names(named, "1"), contains("sv:skor"));
		assertThat(names(named, "2"), contains("sv:gloves"));

		var whole = index.search(
			SearchRequest.create()
				.withLocale("sv")
				.build()
		);

		assertThat(names(whole, "1"), contains("sv:skor"));
		assertThat(names(whole, "2"), contains("sv:gloves"));
	}

	/**
	 * A field that opted out of being filled has nothing to answer a locale the
	 * document never held, so it is left out of the result the way it is left
	 * out of the matching.
	 */
	@Test
	public void testFieldThatIsNotFilledAnswersNothingInALocaleItNeverHeld() throws IOException {
		var index = create(
			"unfilled",
			IndexDef.newBuilder()
				.setLocaleFallback(IndexDef.LocaleFallbackConfig.newBuilder().addChain("en"))
				.putFields("id", string(StringFieldTypeDef.newBuilder()).setPrimaryKey(true).build())
				.putFields(
					"name",
					string(StringFieldTypeDef.newBuilder())
						.setStored(true)
						.setLocales(
							FieldDef.LocaleConfig.newBuilder()
								.setDefaultLocale("en")
								.addLocales("sv")
								.setFallback(FieldDef.LocaleConfig.Fallback.FALLBACK_DISABLED)
						)
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "gloves", "en")
			)
		);
		index.commit();

		var result = index.search(
			SearchRequest.create()
				.withLocale("sv")
				.withFields("name")
				.build()
		);

		assertThat(names(result, "1"), is(empty()));
	}

	/**
	 * A document holding none of the chain's locales keeps its gaps, the way it
	 * did before there was a chain - there is nothing to fill it from.
	 */
	@Test
	public void testADocumentHoldingNoneOfTheChainIsLeftAlone() throws IOException {
		var index = create(
			"unfillable",
			IndexDef.newBuilder()
				.setLocaleFallback(IndexDef.LocaleFallbackConfig.newBuilder().addChain("en"))
				.putFields("id", string(StringFieldTypeDef.newBuilder()).setPrimaryKey(true).build())
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(StringFieldTypeDef.TextUsageConfig.getDefaultInstance())
					)
						.setLocales(
							FieldDef.LocaleConfig.newBuilder()
								.setDefaultLocale("en")
								.addLocales("sv")
								.addLocales("de")
						)
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "skor", "sv")
			)
		);
		index.commit();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("skor"))
				.withLocale("de")
				.build()
		);

		assertThat(ids(result), is(empty()));
	}
}
