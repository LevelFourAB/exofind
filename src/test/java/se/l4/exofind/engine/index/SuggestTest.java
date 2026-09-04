package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.eclipse.collections.api.factory.Lists;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.FacetConfig;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.settings.DeclaredValue;
import se.l4.exofind.engine.index.settings.FieldSettings;
import se.l4.exofind.engine.index.settings.SearchSettings;
import se.l4.exofind.engine.index.settings.SearchSettingsStore;
import se.l4.exofind.engine.index.settings.SuggestConfig;
import se.l4.exofind.engine.query.FieldQuery;
import se.l4.exofind.engine.query.SuggestRequest;
import se.l4.exofind.engine.query.SuggestResult;
import se.l4.exofind.engine.query.matchers.EqualsMatcher;

/**
 * Tests for suggesting what to search for from the values of the fields the
 * search settings opt in - which values a text finds, in what order, what
 * is marked as typed, and what a mistake finds.
 */
public class SuggestTest extends AbstractIndexTest {
	@Test
	public void testValuesStartingWithTheTextAreSuggestedByCount() throws IOException {
		var index = boutique();

		var result = index.suggest(SuggestRequest.of("a"), suggesting("brand", "colour"));

		assertThat(texts(result), contains("Adidas", "Adidas Originals", "Asics"));

		var first = result.suggestions().get(0);
		assertThat(first.field(), is("brand"));
		assertThat(first.value(), is("Adidas"));
		assertThat(first.count(), is(2L));
		assertThat(first.typed(), is(1));
		assertThat(first.corrected(), is(false));
		assertThat(first.label(), is(nullValue()));
	}

	@Test
	public void testTheTextIsFoldedTheWayTheFacetFolds() throws IOException {
		var index = boutique();

		var result = index.suggest(SuggestRequest.of("ADI"), suggesting("brand"));

		assertThat(texts(result), contains("Adidas", "Adidas Originals"));
		assertThat(result.suggestions().get(0).typed(), is(3));
	}

	@Test
	public void testAnEmptyTextAnswersTheMostCommonValues() throws IOException {
		var index = boutique();

		var result = index.suggest(
			SuggestRequest.of("").withLimit(3),
			suggesting("brand", "colour")
		);

		// Ties go by field and then by value, so the order is the same every time
		assertThat(texts(result), contains("Adidas", "black", "red"));
		assertThat(result.suggestions().get(0).typed(), is(0));
	}

	@Test
	public void testFiltersNarrowTheCounts() throws IOException {
		var index = boutique();

		var clothing = index.suggest(
			SuggestRequest.of("n").withFilters(new FieldQuery("category", new EqualsMatcher("Clothing"))),
			suggesting("brand")
		);
		assertThat(texts(clothing), contains("Nike"));

		var shoes = index.suggest(
			SuggestRequest.of("n").withFilters(new FieldQuery("category", new EqualsMatcher("Shoes"))),
			suggesting("brand")
		);
		assertThat(shoes.suggestions().isEmpty(), is(true));
	}

	@Test
	public void testAFilterOnTheSuggestedFieldIsLeftOutOfItsCounts() throws IOException {
		var index = boutique();

		var result = index.suggest(
			SuggestRequest.of("adi").withFilters(new FieldQuery("brand", new EqualsMatcher("Nike"))),
			suggesting("brand")
		);

		assertThat(texts(result), contains("Adidas", "Adidas Originals"));
	}

	@Test
	public void testTheLimitCutsTheSuggestions() throws IOException {
		var index = boutique();

		var result = index.suggest(SuggestRequest.of("a").withLimit(1), suggesting("brand"));

		assertThat(texts(result), contains("Adidas"));
	}

	@Test
	public void testALabelIsSuggestedInTheLocaleOfTheRequest() throws IOException {
		var index = boutique();
		var settings = suggesting(
			SearchSettingsStore.newBuilder()
				.putFields("colour", FieldSettings.newBuilder()
					.setSuggest(SuggestConfig.getDefaultInstance())
					.addValues(
						DeclaredValue.newBuilder()
							.setValue("red")
							.putLabels("sv", "Röd")
							.putLabels("en", "Red")
					)
					.build()
				)
		);

		var swedish = index.suggest(SuggestRequest.of("RÖ").withLocale("sv"), settings);

		assertThat(swedish.suggestions().size(), is(1));
		var suggestion = swedish.suggestions().get(0);
		assertThat(suggestion.text(), is("Röd"));
		assertThat(suggestion.typed(), is(2));
		assertThat(suggestion.value(), is("red"));
		assertThat(suggestion.label(), is("Röd"));
		assertThat(suggestion.count(), is(2L));

		// The value starts with the text but its label does not, so the value is what is marked
		var byValue = index.suggest(SuggestRequest.of("re").withLocale("sv"), settings);

		assertThat(byValue.suggestions().size(), is(1));
		assertThat(byValue.suggestions().get(0).text(), is("red"));
		assertThat(byValue.suggestions().get(0).typed(), is(2));
		assertThat(byValue.suggestions().get(0).label(), is("Röd"));
	}

	@Test
	public void testAMistakeIsForgivenWhenTheTextFindsTooFew() throws IOException {
		var index = boutique();

		var result = index.suggest(SuggestRequest.of("adidsa"), suggesting("brand"));

		assertThat(texts(result), contains("Adidas", "Adidas Originals"));

		var first = result.suggestions().get(0);
		assertThat(first.corrected(), is(true));
		assertThat(first.typed(), is(0));
		assertThat(first.count(), is(2L));
	}

	@Test
	public void testValuesTheTextStartsComeBeforeTheOnesAMistakeAway() throws IOException {
		var index = boutique();

		// Asics starts the text; Adidas is a mistake away and more common
		var result = index.suggest(SuggestRequest.of("asics"), suggesting("brand"));

		assertThat(texts(result), contains("Asics"));

		var withTypo = index.suggest(SuggestRequest.of("adida"), suggesting("brand"));

		assertThat(texts(withTypo), contains("Adidas", "Adidas Originals"));
		assertThat(withTypo.suggestions().get(0).corrected(), is(false));
		assertThat(withTypo.suggestions().get(0).typed(), is(5));
	}

	@Test
	public void testAShortTextIsNotCorrected() throws IOException {
		var index = boutique();

		var result = index.suggest(SuggestRequest.of("adx"), suggesting("brand"));

		assertThat(result.suggestions().isEmpty(), is(true));
	}

	@Test
	public void testTyposCanBeTurnedOff() throws IOException {
		var index = boutique();

		var result = index.suggest(SuggestRequest.of("adidsa").withTypos(false), suggesting("brand"));

		assertThat(result.suggestions().isEmpty(), is(true));
	}

	@Test
	public void testNothingIsSuggestedWithoutSettings() throws IOException {
		var index = boutique();

		assertThat(index.suggest(SuggestRequest.of("a"), null).suggestions().isEmpty(), is(true));
	}

	@Test
	public void testAFieldTheGenerationCannotSuggestIsSkipped() throws IOException {
		var index = boutique();

		// The name has no facet, so it holds no dictionary to suggest from
		var result = index.suggest(SuggestRequest.of("a"), suggesting("name", "brand"));

		assertThat(texts(result), contains("Adidas", "Adidas Originals", "Asics"));
	}

	@Test
	public void testTypedCoversTheCharactersTheFoldedPrefixCovers() throws IOException {
		var index = boutique();

		var result = index.suggest(SuggestRequest.of("adidas or"), suggesting("brand"));

		assertThat(texts(result), contains("Adidas Originals"));
		assertThat(result.suggestions().get(0).typed(), is(9));
	}

	@Test
	public void testAWarmFoldsTheDictionariesOfTheSuggestedFields() throws IOException {
		var index = boutique();

		index.warmFacets(null);
		var withoutSettings = FacetStates.heldBytes();

		index.warmFacets(suggesting("brand"));
		var withSettings = FacetStates.heldBytes();

		assertThat(withSettings > withoutSettings, is(true));

		// The first suggestion after the warm builds nothing more
		index.suggest(SuggestRequest.of("a"), suggesting("brand"));
		assertThat(FacetStates.heldBytes(), is(withSettings));
	}

	private static List<String> texts(SuggestResult result) {
		return result.suggestions().collect(SuggestResult.Suggestion::text).toList();
	}

	/**
	 * Settings suggesting the values of the given fields.
	 */
	private static SearchSettings.Snapshot suggesting(String... fields) {
		var stored = SearchSettingsStore.newBuilder();
		for(var field : fields) {
			stored.putFields(
				field,
				FieldSettings.newBuilder().setSuggest(SuggestConfig.getDefaultInstance()).build()
			);
		}

		return suggesting(stored);
	}

	private static SearchSettings.Snapshot suggesting(SearchSettingsStore.Builder builder) {
		var stored = builder.build();
		return new SearchSettings.Snapshot(
			stored,
			null,
			Map.of(),
			Map.of(),
			stored.getFieldsMap(),
			Lists.immutable.empty(),
			"\"1\""
		);
	}

	/**
	 * A boutique whose brand and colour are values a shopper filters and
	 * counts by.
	 */
	private Index boutique() throws IOException {
		var index = create(
			"boutique",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"name",
					string(StringFieldTypeDef.newBuilder()
						.setMatching(StringFieldTypeDef.TextUsageConfig.getDefaultInstance())
					).build()
				)
				.putFields("brand", faceted().build())
				.putFields("colour", faceted().build())
				.putFields("category", faceted().build())
		);

		index.addDocument(product("1", "Running Shoes", "Adidas", "red", "Shoes"));
		index.addDocument(product("2", "Trail Shoes", "Adidas Originals", "blue", "Shoes"));
		index.addDocument(product("3", "Court Shoes", "Adidas", "black", "Shoes"));
		index.addDocument(product("4", "Track Jacket", "Nike", "red", "Clothing"));
		index.addDocument(product("5", "Racing Shoes", "Asics", "black", "Shoes"));

		index.commit();
		return index;
	}

	private static Document product(
		String id,
		String name,
		String brand,
		String colour,
		String category
	) {
		return new Document(
			new Document.Value("id", id),
			new Document.Value("name", name),
			new Document.Value("brand", brand),
			new Document.Value("colour", colour),
			new Document.Value("category", category)
		);
	}

	private static FieldDef.Builder string() {
		return string(StringFieldTypeDef.newBuilder());
	}

	private static FieldDef.Builder string(StringFieldTypeDef.Builder type) {
		return FieldDef.newBuilder().setType(FieldTypeDef.newBuilder().setString(type));
	}

	private static FieldDef.Builder faceted() {
		return string()
			.setFilter(FilterConfig.getDefaultInstance())
			.setFacet(FacetConfig.getDefaultInstance());
	}
}
