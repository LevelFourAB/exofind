package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.eclipse.collections.api.factory.Lists;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.DoubleFieldTypeDef;
import se.l4.exofind.engine.index.schema.FacetConfig;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.Int32FieldTypeDef;
import se.l4.exofind.engine.index.schema.ObjectFieldTypeDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.settings.FieldSettings;
import se.l4.exofind.engine.index.settings.InterpretConfig;
import se.l4.exofind.engine.index.settings.SearchSettings;
import se.l4.exofind.engine.index.settings.SearchSettingsStore;
import se.l4.exofind.engine.query.AndQuery;
import se.l4.exofind.engine.query.BoostQuery;
import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.NestedQuery;
import se.l4.exofind.engine.query.OrQuery;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.TextQuery;
import se.l4.exofind.engine.query.matchers.EqualsMatcher;
import se.l4.exofind.engine.query.matchers.RangeMatcher;
import se.l4.exofind.engine.query.matchers.TextMatcher;

/**
 * Tests for reading the text of a search box as filters - which words become
 * a filter on which field, what the results then hold, and what the result
 * says about it.
 */
public class InterpretSearchTest extends AbstractIndexTest {
	@Test
	public void testBoundIsReadAsAFilter() throws IOException {
		var index = shop();

		var result = search(index, user("shoes under 100"));

		assertThat(ids(result), contains("1"));

		var interpreted = result.interpreted();
		assertThat(interpreted, is(notNullValue()));
		assertThat(interpreted.text(), is("shoes"));
		assertThat(interpreted.filters().size(), is(1));

		var filter = interpreted.filters().get(0);
		assertThat(filter.field(), is("price"));
		assertThat(filter.words().toList(), contains("under", "100"));
		assertThat(filter.matcher(), is(new RangeMatcher(null, false, 100.0, false)));
	}

	@Test
	public void testUnitNamesTheField() throws IOException {
		var index = shop();

		var result = search(index, user("phone 256gb"));

		assertThat(ids(result), contains("5"));

		var filter = result.interpreted().filters().get(0);
		assertThat(filter.field(), is("storage"));
		assertThat(filter.matcher(), is(new EqualsMatcher(256)));
		assertThat(result.interpreted().text(), is("phone"));
	}

	@Test
	public void testCurrencyIsReadInEverySpelling() throws IOException {
		var index = shop();

		assertThat(ids(search(index, user("under 100 kr"))), contains("1"));
		assertThat(ids(search(index, user("under 100 SEK"))), contains("1"));
		assertThat(ids(search(index, user("under 100kr"))), contains("1"));
	}

	@Test
	public void testWordsStillFindText() throws IOException {
		var index = shop();

		// A price at most 100, or the product that is named Air Max 100
		var result = search(index, user("max 100"));

		assertThat(ids(result), containsInAnyOrder("1", "3"));
		assertThat(result.interpreted(), is(notNullValue()));
		assertThat(result.interpreted().text(), is(""));
	}

	@Test
	public void testReadingIsTurnedOffWhenAsked() throws IOException {
		var index = shop();

		var result = search(
			index,
			user("shoes under 100").withInterpret(TextMatcher.Interpret.OFF)
		);

		assertThat(result.interpreted(), is(nullValue()));
		// Every word is text, and no product holds all three
		assertThat(ids(result), is(List.of()));
	}

	@Test
	public void testTextThatIsNotFromASearchBoxIsNotRead() throws IOException {
		var index = shop();

		var result = search(index, Query.text(TextMatcher.of("shoes under 100")));

		assertThat(result.interpreted(), is(nullValue()));
	}

	@Test
	public void testQuotedWordsAreNeverRead() throws IOException {
		var index = shop();

		var result = search(index, user("\"under 100\""));

		assertThat(result.interpreted(), is(nullValue()));
		assertThat(ids(result), contains("6"));
	}

	@Test
	public void testExcludedWordsAreNeverRead() throws IOException {
		var index = shop();

		var result = search(index, user("shoes -100"));

		assertThat(result.interpreted(), is(nullValue()));
	}

	@Test
	public void testWholeNumberFieldTakesTheNearestWholeNumber() throws IOException {
		var index = shop();

		var result = search(index, user("under 200.5 gb"));

		assertThat(ids(result), contains("4"));
		assertThat(
			result.interpreted().filters().get(0).matcher(),
			is(new RangeMatcher(null, false, 201, false))
		);
	}

	@Test
	public void testRangeOfTwoNumbersIsRead() throws IOException {
		var index = shop();

		var result = search(index, user("100-200 kr"));

		assertThat(ids(result), containsInAnyOrder("2", "3"));
		assertThat(
			result.interpreted().filters().get(0).matcher(),
			is(new RangeMatcher(100.0, true, 200.0, true))
		);
	}

	@Test
	public void testRelaxingKeepsTheReading() throws IOException {
		var index = shop();

		var result = search(index, user("waterproof shoes under 100"));

		assertThat(ids(result), contains("1"));
		assertThat(result.relaxed(), is(notNullValue()));
		assertThat(
			result.relaxed().dropped().collect(SearchResult.Relaxed.Dropped::word).toList(),
			contains("waterproof")
		);
		assertThat(result.relaxed().text(), is("shoes"));
		assertThat(result.interpreted(), is(notNullValue()));
		assertThat(result.interpreted().filters().get(0).field(), is("price"));
	}

	@Test
	public void testFacetsCountWhatTheReadingFound() throws IOException {
		var index = shop();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text(user("under 200")))
				.withFacets(Facet.of("category"))
				.build()
		);

		assertThat(ids(result), containsInAnyOrder("1", "2", "3"));

		var category = result.facets().get("category");
		assertThat(category.values().size(), is(1));
		assertThat(category.values().get(0).value(), is("shoes"));
		assertThat(category.values().get(0).count(), is(3L));
	}

	@Test
	public void testCountingOnlyStillReads() throws IOException {
		var index = shop();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text(user("shoes under 100")))
				.withLimit(0)
				.build()
		);

		assertThat(result.total().count(), is(1L));
		assertThat(result.interpreted(), is(notNullValue()));
	}

	@Test
	public void testLocaleDecidesTheComparatives() throws IOException {
		var index = shop();

		var swedish = index.search(
			SearchRequest.create()
				.withQuery(Query.text(user("högst 100")))
				.withLocale("sv")
				.build()
		);
		assertThat(ids(swedish), contains("1"));
		assertThat(
			swedish.interpreted().filters().get(0).matcher(),
			is(new RangeMatcher(null, false, 100.0, true))
		);

		var english = search(index, user("högst 100"));
		assertThat(english.interpreted(), is(nullValue()));
	}

	@Test
	public void testNumberWithoutAUnitNeedsOneCurrency() throws IOException {
		var index = twoCurrencies();

		var result = search(index, user("under 100"));

		assertThat(result.interpreted(), is(nullValue()));
	}

	@Test
	public void testUnitStillNamesTheFieldAmongSeveralCurrencies() throws IOException {
		var index = twoCurrencies();

		var result = search(index, user("under 100 kr"));

		assertThat(result.interpreted().filters().size(), is(1));
		assertThat(result.interpreted().filters().get(0).field(), is("price"));
	}

	@Test
	public void testFieldInsideAValueIsReadAgainstOneValue() throws IOException {
		var index = variants();

		var result = search(index, user("under 100"));

		assertThat(ids(result), contains("1"));
		assertThat(result.interpreted().filters().get(0).field(), is("variants.price"));
	}

	@Test
	public void testExplanationReportsTheReading() throws IOException {
		var index = shop();

		var request = SearchRequest.create()
			.withQuery(Query.text(user("shoes under 100")))
			.build();

		var explanation = index.explain(request, "1", 0, null);

		assertThat(explanation.matched(), is(true));
		assertThat(explanation.interpreted(), is(notNullValue()));
		assertThat(explanation.interpreted().text(), is("shoes"));
	}

	@Test
	public void testWithoutTargetsAnyListOfAProductIsRead() throws IOException {
		var index = pricelists();

		// The one currency field is the amount, on whichever list holds it
		var result = search(index, Query.text(user("rain under 100")));

		assertThat(ids(result), containsInAnyOrder("1", "2", "3"));
	}

	@Test
	public void testNamedTargetPinsTheListTheNumberIsReadOn() throws IOException {
		var index = pricelists();

		var result = search(
			index,
			Query.text(user("rain under 100")).withTargets(onList("cust-17"))
		);

		// The boots have no price on the customer's list, and the hat is 149 there
		assertThat(ids(result), contains("1"));

		var filter = result.interpreted().filters().get(0);
		assertThat(filter.field(), is("prices.amount"));
		assertThat(filter.matcher(), is(new RangeMatcher(null, false, 100.0, false)));
		assertThat(filter.when(), contains(listIs("cust-17")));
		assertThat(filter.fallback().isEmpty(), is(true));
		assertThat(result.interpreted().text(), is("rain"));
	}

	@Test
	public void testFallbackIsReadWhereAProductHoldsNoValueOnTheTarget() throws IOException {
		var index = pricelists();

		var target = onList("cust-17").withFallback(onList("store"));
		var result = search(
			index,
			Query.text(user("rain under 100")).withTargets(target)
		);

		// The boots fall back to the store price; the hat has a customer price
		// of 149 and its store price of 99 is never looked at
		assertThat(ids(result), containsInAnyOrder("1", "2"));

		var filter = result.interpreted().filters().get(0);
		assertThat(filter.field(), is("prices.amount"));
		assertThat(filter.when(), contains(listIs("cust-17")));
		assertThat(filter.fallback(), contains(onList("store")));
	}

	@Test
	public void testFallbackChainIsTriedInOrder() throws IOException {
		var index = pricelists();

		var target = onList("cust-17").withFallback(onList("wholesale"), onList("store"));
		var result = search(
			index,
			Query.text(user("rain under 100")).withTargets(target)
		);

		// Nothing is on the wholesale list, so it falls through to the store
		assertThat(ids(result), containsInAnyOrder("1", "2"));
	}

	@Test
	public void testUnitStillPicksAmongNamedTargets() throws IOException {
		var index = pricelists();

		var result = search(
			index,
			Query.text(user("rain under 2 kg"))
				.withTargets(onList("cust-17"), TextQuery.Target.of("weight"))
		);

		assertThat(ids(result), contains("2"));
		assertThat(result.interpreted().filters().size(), is(1));
		assertThat(result.interpreted().filters().get(0).field(), is("weight"));
	}

	@Test
	public void testNamedTargetsInSeveralCurrenciesLeaveABareNumberAsText() throws IOException {
		var index = pricelists();

		var query = Query.text(user("rain under 100"))
			.withTargets(onList("cust-17"), TextQuery.Target.of("shipping"));

		assertThat(search(index, query).interpreted(), is(nullValue()));

		var withUnit = Query.text(user("rain under 100 kr"))
			.withTargets(onList("cust-17"), TextQuery.Target.of("shipping"));
		var result = search(index, withUnit);

		assertThat(result.interpreted().filters().size(), is(1));
		assertThat(result.interpreted().filters().get(0).field(), is("prices.amount"));
	}

	@Test
	public void testNamedTargetsSharingACurrencyAllReadABareNumber() throws IOException {
		var index = flatPricelists();

		// Two fields in kronor: nothing says which, so nothing is read
		assertThat(search(index, Query.text(user("rain under 100"))).interpreted(), is(nullValue()));

		// Named, both are read and either is enough
		var result = search(
			index,
			Query.text(user("rain under 100"))
				.withTargets(TextQuery.Target.of("price_customer"), TextQuery.Target.of("price_store"))
		);

		assertThat(ids(result), containsInAnyOrder("1", "2", "3"));
		assertThat(result.interpreted().filters().size(), is(2));
	}

	@Test
	public void testFallbackOnFlatFields() throws IOException {
		var index = flatPricelists();

		var target = TextQuery.Target.of("price_customer")
			.withFallback(TextQuery.Target.of("price_store"));
		var result = search(index, Query.text(user("rain under 100")).withTargets(target));

		assertThat(ids(result), containsInAnyOrder("1", "2"));
	}

	@Test
	public void testTargetWithoutAUnitIsRefused() throws IOException {
		var index = pricelists();

		var e = assertThrows(
			IndexException.class,
			() -> search(
				index,
				Query.text(user("rain")).withTargets(TextQuery.Target.of("prices.list"))
			)
		);

		assertThat(e.getCode(), is("index:query:interpret:no_unit"));
	}

	@Test
	public void testTargetThatDoesNotExistIsRefused() throws IOException {
		var index = pricelists();

		var e = assertThrows(
			IndexException.class,
			() -> search(
				index,
				Query.text(user("rain")).withTargets(TextQuery.Target.of("cost"))
			)
		);

		assertThat(e.getCode(), is("index:query:field_not_found"));
	}

	@Test
	public void testFallbackInAnotherUnitIsRefused() throws IOException {
		var index = pricelists();

		var target = onList("cust-17").withFallback(TextQuery.Target.of("weight"));
		var e = assertThrows(
			IndexException.class,
			() -> search(index, Query.text(user("rain under 100")).withTargets(target))
		);

		assertThat(e.getCode(), is("index:query:interpret:fallback_unit"));
	}

	@Test
	public void testWhenNamingAFieldOutsideTheListIsRefused() throws IOException {
		var index = pricelists();

		var target = TextQuery.Target.of("prices.amount")
			.withWhen(Query.field("id", new EqualsMatcher("1")));
		var e = assertThrows(
			IndexException.class,
			() -> search(index, Query.text(user("rain under 100")).withTargets(target))
		);

		assertThat(e.getCode(), is("index:query:nested:not_in_path"));
	}

	@Test
	public void testTextInsideOrIsReadInEveryPlace() throws IOException {
		var index = catalogue();

		// Found by the name of the product, with a variant under 100
		var byName = search(index, productOrVariant(user("shoes under 100")));
		assertThat(ids(byName), contains("1"));
		assertThat(byName.interpreted(), is(notNullValue()));
		assertThat(byName.interpreted().text(), is("shoes"));
		assertThat(byName.interpreted().filters().size(), is(1));
		assertThat(byName.interpreted().filters().get(0).field(), is("variants.price"));

		// Found by the number of a variant, which is text only a variant holds
		var byNumber = search(index, productOrVariant(user("rs-1 under 100")));
		assertThat(ids(byNumber), contains("1"));
		assertThat(byNumber.interpreted().text(), is("rs-1"));
	}

	@Test
	public void testReadingInsideNestedIsOnTheValueTheTextMatched() throws IOException {
		var index = catalogue();

		// The red variant is 129, and the cheap variant is not the one named
		var result = search(index, productOrVariant(user("rs-2 under 100")));

		assertThat(result.interpreted(), is(notNullValue()));
		assertThat(ids(result), is(List.of()));
	}

	@Test
	public void testTextInsideNestedAloneIsRead() throws IOException {
		var index = catalogue();

		var result = search(
			index,
			NestedQuery.of("variants", Query.text(user("blue under 100")))
		);

		assertThat(ids(result), contains("1"));
		assertThat(result.interpreted().filters().get(0).field(), is("variants.price"));
	}

	@Test
	public void testFieldNoPlaceCanHoldIsNotRead() throws IOException {
		var index = catalogue();

		// The weight is on the product, and the text only sees one variant
		var result = search(
			index,
			NestedQuery.of("variants", Query.text(user("blue under 2 kg")))
		);

		assertThat(result.interpreted(), is(nullValue()));
	}

	@Test
	public void testFieldOutsideThePathIsLeftOutOfThatPlace() throws IOException {
		var index = catalogue();

		// The weight is read where the text is against the product
		var result = search(index, productOrVariant(user("shoes under 2 kg")));

		assertThat(ids(result), contains("1"));
		assertThat(result.interpreted().filters().get(0).field(), is("weight"));
	}

	@Test
	public void testNamedTargetOutsideThePathIsRefused() throws IOException {
		var index = catalogue();

		var e = assertThrows(
			IndexException.class,
			() -> search(
				index,
				NestedQuery.of(
					"variants",
					Query.text(user("blue")).withTargets(TextQuery.Target.of("weight"))
				)
			)
		);

		assertThat(e.getCode(), is("index:query:nested:not_in_path"));
	}

	@Test
	public void testClausesHoldingDifferentTextsAreNotRead() throws IOException {
		var index = shop();

		var result = search(
			index,
			AndQuery.of(Query.text(user("shoes")), Query.text(user("under 100")))
		);

		assertThat(result.interpreted(), is(nullValue()));
	}

	@Test
	public void testTextInsideBoostIsRead() throws IOException {
		var index = shop();

		var result = search(
			index,
			Query.field("category", new EqualsMatcher("shoes")),
			BoostQuery.of(2f, Query.text(user("under 100")))
		);

		// Every pair of shoes, the one under 100 first
		assertThat(result.interpreted(), is(notNullValue()));
		assertThat(ids(result).size(), is(3));
		assertThat(ids(result).get(0), is("1"));
	}

	/**
	 * Products priced on several lists, each list a value of a nested field
	 * holding the list id and the amount in kronor.
	 */
	private Index pricelists() throws IOException {
		var index = create(
			"pricelists",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).setFilter(FilterConfig.getDefaultInstance()).build())
				.putFields("name", matching().build())
				.putFields(
					"prices",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder().setObject(
								ObjectFieldTypeDef.newBuilder()
									.putFields(
										"list",
										string().setFilter(FilterConfig.getDefaultInstance()).build()
									)
									.putFields("amount", price("SEK").build())
									.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
							)
						)
						.setMultiple(true)
						.build()
				)
				.putFields(
					"weight",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setDouble(DoubleFieldTypeDef.newBuilder().setUnit("kilogram"))
						)
						.setFilter(FilterConfig.getDefaultInstance())
						.build()
				)
				.putFields("shipping", price("EUR").build())
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Rain jacket"),
				new Document.Value("prices", priced("cust-17", 89.0)),
				new Document.Value("prices", priced("store", 129.0)),
				new Document.Value("weight", 2.5),
				new Document.Value("shipping", 5.0)
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "Rain boots"),
				new Document.Value("prices", priced("store", 79.0)),
				new Document.Value("weight", 1.5),
				new Document.Value("shipping", 9.0)
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "3"),
				new Document.Value("name", "Rain hat"),
				new Document.Value("prices", priced("cust-17", 149.0)),
				new Document.Value("prices", priced("store", 99.0)),
				new Document.Value("weight", 3.0),
				new Document.Value("shipping", 5.0)
			)
		);

		index.commit();
		return index;
	}

	/**
	 * The same products with one field per list instead, both in kronor.
	 */
	private Index flatPricelists() throws IOException {
		var index = create(
			"flat-pricelists",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields("name", matching().build())
				.putFields("price_customer", price("SEK").build())
				.putFields("price_store", price("SEK").build())
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Rain jacket"),
				new Document.Value("price_customer", 89.0),
				new Document.Value("price_store", 129.0)
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "Rain boots"),
				new Document.Value("price_store", 79.0)
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "3"),
				new Document.Value("name", "Rain hat"),
				new Document.Value("price_customer", 149.0),
				new Document.Value("price_store", 99.0)
			)
		);

		index.commit();
		return index;
	}

	private static Document priced(String list, double amount) {
		return new Document(
			new Document.Value("list", list),
			new Document.Value("amount", amount)
		);
	}

	private static Query listIs(String list) {
		return Query.field("prices.list", new EqualsMatcher(list));
	}

	private static TextQuery.Target onList(String list) {
		return TextQuery.Target.of("prices.amount").withWhen(listIs(list));
	}

	/**
	 * A handful of products with a price in Swedish kronor and, for the
	 * phones, a storage size in gigabytes.
	 */
	@Test
	public void testValueIsReadAsAFilter() throws IOException {
		var index = boutique();

		var result = search(index, reading("colour", "brand"), user("red shoes"));

		assertThat(ids(result), contains("1"));

		var interpreted = result.interpreted();
		assertThat(interpreted, is(notNullValue()));
		assertThat(interpreted.text(), is("shoes"));
		assertThat(interpreted.filters().size(), is(1));

		var filter = interpreted.filters().get(0);
		assertThat(filter.kind(), is(SearchResult.Interpreted.Kind.VALUE));
		assertThat(filter.field(), is("colour"));
		assertThat(filter.words().toList(), contains("red"));
		assertThat(filter.matcher(), is(new EqualsMatcher("Red")));
	}

	@Test
	public void testValueIsReadInEverySpelling() throws IOException {
		var index = boutique();

		var result = search(index, reading("colour"), user("RED shoes"));

		assertThat(ids(result), contains("1"));
		assertThat(result.interpreted().filters().get(0).matcher(), is(new EqualsMatcher("Red")));
		assertThat(result.interpreted().filters().get(0).words().toList(), contains("RED"));
	}

	@Test
	public void testWordsOfAValueStillFindText() throws IOException {
		var index = boutique();

		// The colour black, or the deals that are named after a Friday
		var result = search(index, reading("colour"), user("black"));

		assertThat(ids(result), containsInAnyOrder("3", "4"));
		assertThat(result.interpreted().text(), is(""));
	}

	@Test
	public void testLongestValueWins() throws IOException {
		var index = boutique();

		var result = search(index, reading("colour"), user("dark red jacket"));

		assertThat(ids(result), contains("5"));
		assertThat(result.interpreted().text(), is("jacket"));
		assertThat(result.interpreted().filters().size(), is(1));

		var filter = result.interpreted().filters().get(0);
		assertThat(filter.matcher(), is(new EqualsMatcher("Dark Red")));
		assertThat(filter.words().toList(), contains("dark", "red"));
	}

	@Test
	public void testValueOfSeveralFieldsIsEveryOneOfThem() throws IOException {
		var index = boutique();

		// A brand and a colour share the spelling, and either finds a product
		var result = search(index, reading("colour", "brand"), user("stone"));

		assertThat(ids(result), containsInAnyOrder("4", "6"));
		assertThat(result.interpreted().filters().size(), is(2));
		assertThat(
			result.interpreted().filters().collect(SearchResult.Interpreted.Filter::field).toList(),
			containsInAnyOrder("brand", "colour")
		);
	}

	@Test
	public void testValueAndNumberAreReadTogether() throws IOException {
		var index = boutique();

		var result = search(index, reading("colour", "brand"), user("nike under 100"));

		assertThat(ids(result), contains("1"));
		assertThat(result.interpreted().text(), is(""));

		var filters = result.interpreted().filters();
		assertThat(filters.size(), is(2));
		assertThat(filters.get(0).kind(), is(SearchResult.Interpreted.Kind.VALUE));
		assertThat(filters.get(0).field(), is("brand"));
		assertThat(filters.get(0).matcher(), is(new EqualsMatcher("Nike")));
		assertThat(filters.get(1).kind(), is(SearchResult.Interpreted.Kind.NUMBER));
		assertThat(filters.get(1).field(), is("price"));
	}

	@Test
	public void testWordsOfANumberAreNeverAValue() throws IOException {
		var index = boutique();

		// The size is a value, and a bare number next to it would be a price
		var result = search(index, reading("size"), user("shoes under 100"));

		var filters = result.interpreted().filters();
		assertThat(filters.size(), is(1));
		assertThat(filters.get(0).field(), is("price"));
	}

	@Test
	public void testAFieldNotOptedInIsNotRead() throws IOException {
		var index = boutique();

		var result = search(index, reading("colour"), user("nike"));

		assertThat(result.interpreted(), is(nullValue()));
		assertThat(ids(result), is(List.of()));
	}

	@Test
	public void testWithoutSettingsNoValueIsRead() throws IOException {
		var index = boutique();

		var result = search(index, user("red shoes"));

		assertThat(result.interpreted(), is(nullValue()));
	}

	@Test
	public void testSettingsSetAsideReadNoValues() throws IOException {
		var index = boutique();

		var stored = reading("colour").stored();
		var setAside = new SearchSettings.Snapshot(
			stored,
			null,
			Map.of(),
			Map.of(),
			Map.of(),
			Lists.immutable.of("something_this_build_lacks"),
			"\"2\""
		);

		assertThat(search(index, setAside, user("red shoes")).interpreted(), is(nullValue()));
	}

	@Test
	public void testAFieldThatCanNotBeReadIsSkipped() throws IOException {
		var index = boutique();

		// The name has no facet, so it holds no dictionary to read
		var result = search(index, reading("name", "colour"), user("red shoes"));

		assertThat(ids(result), contains("1"));
		assertThat(result.interpreted().filters().size(), is(1));
	}

	@Test
	public void testValueInsideAListIsReadAgainstOneValue() throws IOException {
		var index = colouredVariants();

		var result = search(
			index,
			reading("variants.colour"),
			productOrVariant(user("red"))
		);

		assertThat(ids(result), contains("1"));

		var filter = result.interpreted().filters().get(0);
		assertThat(filter.field(), is("variants.colour"));
		assertThat(filter.matcher(), is(new EqualsMatcher("Red")));
	}

	/**
	 * A boutique whose colours and brands are values a shopper types, written
	 * in two segments so a value is looked up across them.
	 */
	private Index boutique() throws IOException {
		var index = create(
			"boutique",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields("name", matching().setStored(true).build())
				.putFields("colour", value().build())
				.putFields("brand", value().build())
				.putFields("size", value().build())
				.putFields("price", price("SEK").build())
		);

		index.addDocument(item("1", "Running Shoes", "Red", "Nike", 79.0));
		index.addDocument(item("2", "Trail Shoes", "Blue", "Nike", 129.0));
		index.addDocument(item("3", "Black Friday Deals", "White", "Adidas", 249.0));
		index.commit();

		index.addDocument(item("4", "Sneakers", "Black", "Stone", 99.0));
		index.addDocument(item("5", "Jacket", "Dark Red", "The North Face", 1299.0));
		index.addDocument(
			new Document(
				new Document.Value("id", "6"),
				new Document.Value("name", "Boots"),
				new Document.Value("colour", "Stone"),
				new Document.Value("size", "100"),
				new Document.Value("price", 899.0)
			)
		);
		index.commit();

		return index;
	}

	/**
	 * Products whose colour sits on the variant, so a colour is read against
	 * one variant at a time.
	 */
	private Index colouredVariants() throws IOException {
		var index = create(
			"coloured-variants",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields("name", matching().build())
				.putFields(
					"variants",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder().setObject(
								ObjectFieldTypeDef.newBuilder()
									.putFields("title", matching().build())
									.putFields("number", matching().build())
									.putFields("colour", value().build())
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
				new Document.Value("name", "Running Shoes"),
				new Document.Value("variants", colouredVariant("Small", "RS-1", "Blue")),
				new Document.Value("variants", colouredVariant("Large", "RS-2", "Red"))
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "Boots"),
				new Document.Value("variants", colouredVariant("Large", "BT-1", "Black"))
			)
		);

		index.commit();
		return index;
	}

	private static Document colouredVariant(String title, String number, String colour) {
		return new Document(
			new Document.Value("title", title),
			new Document.Value("number", number),
			new Document.Value("colour", colour)
		);
	}

	private static Document item(String id, String name, String colour, String brand, double price) {
		return new Document(
			new Document.Value("id", id),
			new Document.Value("name", name),
			new Document.Value("colour", colour),
			new Document.Value("brand", brand),
			new Document.Value("price", price)
		);
	}

	/**
	 * A string field a shopper filters and counts by, which is what holds a
	 * dictionary of values.
	 */
	private static FieldDef.Builder value() {
		return string()
			.setFilter(FilterConfig.getDefaultInstance())
			.setFacet(FacetConfig.getDefaultInstance());
	}

	/**
	 * Settings that read the values of the given fields out of the text.
	 */
	private static SearchSettings.Snapshot reading(String... fields) {
		var stored = SearchSettingsStore.newBuilder();
		for(var field : fields) {
			stored.putFields(
				field,
				FieldSettings.newBuilder()
					.setInterpret(InterpretConfig.getDefaultInstance())
					.build()
			);
		}

		var built = stored.build();
		return new SearchSettings.Snapshot(
			built,
			null,
			Map.of(),
			Map.of(),
			built.getFieldsMap(),
			Lists.immutable.empty(),
			"\"1\""
		);
	}

	private static SearchResult search(
		Index index,
		SearchSettings.Snapshot settings,
		TextMatcher matcher
	) throws IOException {
		return search(index, settings, Query.text(matcher));
	}

	private static SearchResult search(
		Index index,
		SearchSettings.Snapshot settings,
		Query... query
	) throws IOException {
		return index.search(SearchRequest.create().withQuery(query).build(), settings);
	}

	private Index shop() throws IOException {
		var index = create(
			"shop",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields("name", matching().setStored(true).build())
				.putFields(
					"category",
					string()
						.setFilter(FilterConfig.getDefaultInstance())
						.setFacet(FacetConfig.getDefaultInstance())
						.build()
				)
				.putFields("price", price("SEK").build())
				.putFields(
					"storage",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setInt32(Int32FieldTypeDef.newBuilder().setUnit("gigabyte"))
						)
						.setFilter(FilterConfig.getDefaultInstance())
						.build()
				)
		);

		index.addDocument(product("1", "Mens Running Shoes", "shoes", 79.0));
		index.addDocument(product("2", "Womens Running Shoes", "shoes", 129.0));
		index.addDocument(product("3", "Air Max 100", "shoes", 150.0));
		index.addDocument(
			new Document(
				new Document.Value("id", "4"),
				new Document.Value("name", "Phone"),
				new Document.Value("category", "phones"),
				new Document.Value("price", 999.0),
				new Document.Value("storage", 128)
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "5"),
				new Document.Value("name", "Phone"),
				new Document.Value("category", "phones"),
				new Document.Value("price", 1299.0),
				new Document.Value("storage", 256)
			)
		);
		index.addDocument(product("6", "Everything under 100", "books", 249.0));

		index.commit();
		return index;
	}

	/**
	 * Products with two prices in the same currency, so a number without a
	 * unit names neither.
	 */
	private Index twoCurrencies() throws IOException {
		var index = create(
			"two-currencies",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields("name", matching().build())
				.putFields("price", price("SEK").build())
				.putFields("shipping", price("EUR").build())
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Shoes"),
				new Document.Value("price", 79.0),
				new Document.Value("shipping", 5.0)
			)
		);

		index.commit();
		return index;
	}

	/**
	 * Products priced per variant, where a price is read against one variant
	 * at a time.
	 */
	private Index variants() throws IOException {
		var index = create(
			"variants",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields("name", matching().build())
				.putFields(
					"variants",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder().setObject(
								ObjectFieldTypeDef.newBuilder()
									.putFields("price", price("SEK").build())
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
				new Document.Value("name", "Shoes"),
				new Document.Value("variants", new Document(new Document.Value("price", 79.0))),
				new Document.Value("variants", new Document(new Document.Value("price", 129.0)))
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "Boots"),
				new Document.Value("variants", new Document(new Document.Value("price", 199.0)))
			)
		);

		index.commit();
		return index;
	}

	/**
	 * A catalogue where a product is found by its name or by the title or
	 * the number of one of its variants, and every variant carries a price in
	 * kronor. The product also has a weight, which sits on the product and
	 * not on a variant.
	 */
	private Index catalogue() throws IOException {
		var index = create(
			"catalogue",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields("name", matching().build())
				.putFields(
					"weight",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setDouble(DoubleFieldTypeDef.newBuilder().setUnit("kilogram"))
						)
						.setFilter(FilterConfig.getDefaultInstance())
						.build()
				)
				.putFields(
					"variants",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder().setObject(
								ObjectFieldTypeDef.newBuilder()
									.putFields("title", matching().build())
									.putFields("number", matching().build())
									.putFields("price", price("SEK").build())
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
				new Document.Value("name", "Running Shoes"),
				new Document.Value("weight", 0.8),
				new Document.Value("variants", variant("Blue", "RS-1", 79.0)),
				new Document.Value("variants", variant("Red", "RS-2", 129.0))
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "Boots"),
				new Document.Value("weight", 1.4),
				new Document.Value("variants", variant("Black", "BT-1", 199.0))
			)
		);

		index.commit();
		return index;
	}

	private static Document variant(String title, String number, double price) {
		return new Document(
			new Document.Value("title", title),
			new Document.Value("number", number),
			new Document.Value("price", price)
		);
	}

	/**
	 * The shape a catalogue searches with: the text against the product, or
	 * against one of its variants.
	 */
	private static Query productOrVariant(TextMatcher matcher) {
		return OrQuery.of(
			Query.text(matcher).withField("name"),
			NestedQuery.of(
				"variants",
				Query.text(matcher).withField("variants.title").withField("variants.number")
			)
		);
	}

	private static Document product(String id, String name, String category, double price) {
		return new Document(
			new Document.Value("id", id),
			new Document.Value("name", name),
			new Document.Value("category", category),
			new Document.Value("price", price)
		);
	}

	private static FieldDef.Builder string() {
		return FieldDef.newBuilder()
			.setType(FieldTypeDef.newBuilder().setString(StringFieldTypeDef.newBuilder()));
	}

	private static FieldDef.Builder matching() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setString(
					StringFieldTypeDef.newBuilder()
						.setMatching(StringFieldTypeDef.TextUsageConfig.getDefaultInstance())
				)
			);
	}

	private static FieldDef.Builder price(String unit) {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setDouble(DoubleFieldTypeDef.newBuilder().setUnit(unit))
			)
			.setFilter(FilterConfig.getDefaultInstance());
	}

	private static TextMatcher user(String text) {
		return TextMatcher.of(text).withMatch(TextMatcher.Match.USER);
	}

	private static SearchResult search(Index index, TextMatcher matcher) throws IOException {
		return search(index, Query.text(matcher));
	}

	private static SearchResult search(Index index, Query... query) throws IOException {
		return index.search(SearchRequest.create().withQuery(query).build());
	}

	private static List<Object> ids(SearchResult result) {
		return result.hits().collect(SearchResult.Hit::id).toList();
	}
}
