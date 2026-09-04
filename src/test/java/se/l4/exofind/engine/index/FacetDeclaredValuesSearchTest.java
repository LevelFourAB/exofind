package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
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
import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.matchers.EqualsMatcher;

/**
 * Tests for the values the search settings declare for a facet field - the
 * order a facet answers them in, the label each value carries in the locale
 * of the search, and how a prefix finds a value by its label.
 */
public class FacetDeclaredValuesSearchTest extends AbstractIndexTest {
	@Test
	public void testDeclaredOrderAnswersDeclaredValuesFirstAndTheRestByCount() throws IOException {
		var index = shop();

		var facet = count(index, sizes(), null, Facet.of("size").withOrder(Facet.Order.DECLARED));

		assertThat(facet.totalValues(), is(6));
		assertThat(
			facet.values(),
			contains(
				new SearchResult.Facet.Value("S", 4),
				new SearchResult.Facet.Value("M", 3),
				new SearchResult.Facet.Value("L", 2),
				new SearchResult.Facet.Value("XL", 1),
				new SearchResult.Facet.Value("OS", 2),
				new SearchResult.Facet.Value("XXL", 1)
			)
		);
	}

	@Test
	public void testDeclaredOrderIsCutByTheLimitAndNotTheTotal() throws IOException {
		var index = shop();

		var facet = count(
			index,
			sizes(),
			null,
			Facet.of("size").withOrder(Facet.Order.DECLARED).withLimit(5)
		);

		assertThat(facet.totalValues(), is(6));
		assertThat(
			facet.values().collect(SearchResult.Facet.Value::value),
			contains("S", "M", "L", "XL", "OS")
		);
	}

	@Test
	public void testValuesSharingAnOrderAreSortedByCount() throws IOException {
		var index = shop();

		var settings = declaring(
			"size",
			declared("L", 1),
			declared("M", 1),
			declared("S", 2)
		);
		var facet = count(index, settings, null, Facet.of("size").withOrder(Facet.Order.DECLARED));

		assertThat(
			facet.values().collect(SearchResult.Facet.Value::value),
			contains("M", "L", "S", "OS", "XL", "XXL")
		);
	}

	@Test
	public void testValueDeclaredWithoutAnOrderComesAfterTheOrderedOnes() throws IOException {
		var index = shop();

		var settings = declaring(
			"size",
			declared("S", 1),
			declared("XL", null)
		);
		var facet = count(index, settings, null, Facet.of("size").withOrder(Facet.Order.DECLARED));

		assertThat(
			facet.values().collect(SearchResult.Facet.Value::value),
			contains("S", "M", "L", "OS", "XL", "XXL")
		);
	}

	@Test
	public void testDeclaredOrderWithoutADeclarationIsByCount() throws IOException {
		var index = shop();

		var facet = count(index, null, null, Facet.of("size").withOrder(Facet.Order.DECLARED));

		assertThat(
			facet.values().collect(SearchResult.Facet.Value::value),
			contains("S", "M", "L", "OS", "XL", "XXL")
		);
	}

	@Test
	public void testDeclaredOrderAppliesUnderAPrefix() throws IOException {
		var index = shop();

		var facet = count(
			index,
			sizes(),
			null,
			Facet.of("size").withOrder(Facet.Order.DECLARED).withPrefix("x")
		);

		assertThat(facet.totalValues(), is(2));
		assertThat(
			facet.values().collect(SearchResult.Facet.Value::value),
			contains("XL", "XXL")
		);
	}

	@Test
	public void testLabelsAreAnsweredInTheSearchLocale() throws IOException {
		var index = shop();

		var facet = count(index, colours(), "sv", Facet.of("colour"));

		assertThat(
			facet.values(),
			contains(
				new SearchResult.Facet.Value("red", 6, "Röd"),
				new SearchResult.Facet.Value("blue", 5, "Blue"),
				new SearchResult.Facet.Value("green", 2)
			)
		);
	}

	@Test
	public void testLabelsFallBackToTheDefaultLocaleOfTheField() throws IOException {
		var index = shop();

		// The colour field is not locale specific, so its values are read in the engine default
		var withoutLocale = count(index, colours(), null, Facet.of("colour").withLimit(1));
		assertThat(withoutLocale.values(), contains(new SearchResult.Facet.Value("red", 6, "Red")));

		var otherLocale = count(index, colours(), "de", Facet.of("colour").withLimit(1));
		assertThat(otherLocale.values(), contains(new SearchResult.Facet.Value("red", 6, "Red")));
	}

	@Test
	public void testLabelsFallBackToTheDeclaredDefaultLocaleOfALocaleSpecificField()
		throws IOException
	{
		var index = shop();

		var settings = declaring(
			"material",
			declared("cotton", null, Map.of("sv", "Bomull"))
		);
		var facet = count(index, settings, "en", Facet.of("material"));

		assertThat(facet.values(), contains(new SearchResult.Facet.Value("cotton", 1, "Bomull")));
	}

	@Test
	public void testSearchLocaleResolvesToTheClosestLabel() throws IOException {
		var index = shop();

		var facet = count(index, colours(), "sv-SE", Facet.of("colour").withLimit(1));

		assertThat(facet.values(), contains(new SearchResult.Facet.Value("red", 6, "Röd")));
	}

	@Test
	public void testPrefixFindsAValueByItsLabel() throws IOException {
		var index = shop();

		var facet = count(index, colours(), "sv", Facet.of("colour").withPrefix("rö"));

		assertThat(facet.totalValues(), is(1));
		assertThat(facet.values(), contains(new SearchResult.Facet.Value("red", 6, "Röd")));
	}

	@Test
	public void testPrefixStillFindsAValueByTheValueItself() throws IOException {
		var index = shop();

		var facet = count(index, colours(), "sv", Facet.of("colour").withPrefix("re"));

		assertThat(facet.values(), contains(new SearchResult.Facet.Value("red", 6, "Röd")));
	}

	@Test
	public void testPrefixMatchesTheLabelOfTheSearchLocaleOnly() throws IOException {
		var index = shop();

		var facet = count(index, colours(), null, Facet.of("colour").withPrefix("rö"));

		assertThat(facet.totalValues(), is(0));
	}

	@Test
	public void testPrefixOnALabelOfAValueNoDocumentHoldsAnswersNothing() throws IOException {
		var index = shop();

		var settings = declaring(
			"colour",
			declared("purple", null, Map.of("sv", "Lila"))
		);
		var facet = count(index, settings, "sv", Facet.of("colour").withPrefix("li"));

		assertThat(facet.totalValues(), is(0));
	}

	@Test
	public void testCountsNarrowByTheQueryUnderDeclaredOrder() throws IOException {
		var index = shop();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.field("colour", new EqualsMatcher("red")))
				.addFacet(Facet.of("size").withOrder(Facet.Order.DECLARED))
				.withLimit(0)
				.build(),
			sizes()
		);

		assertThat(
			result.facets().get("size").values(),
			contains(
				new SearchResult.Facet.Value("S", 2),
				new SearchResult.Facet.Value("M", 1),
				new SearchResult.Facet.Value("L", 1),
				new SearchResult.Facet.Value("XL", 1),
				new SearchResult.Facet.Value("OS", 1)
			)
		);
	}

	/**
	 * Count one facet under everything the index holds.
	 */
	private static SearchResult.Facet count(
		Index index,
		SearchSettings.Snapshot settings,
		String locale,
		Facet facet
	) throws IOException {
		var result = index.search(
			SearchRequest.create()
				.addFacet(facet)
				.withLocale(locale)
				.withLimit(0)
				.build(),
			settings
		);

		return result.facets().get(facet.name());
	}

	/**
	 * Sizes in the order they are worn, not the order they sort in.
	 */
	private static SearchSettings.Snapshot sizes() {
		return declaring(
			"size",
			declared("S", 1),
			declared("M", 2),
			declared("L", 3),
			declared("XL", 4)
		);
	}

	/**
	 * Colours labelled in English and, for one of them, in Swedish.
	 */
	private static SearchSettings.Snapshot colours() {
		return declaring(
			"colour",
			declared("red", null, Map.of("en", "Red", "sv", "Röd")),
			declared("blue", null, Map.of("en", "Blue"))
		);
	}

	private static DeclaredValue declared(String value, Integer order) {
		return declared(value, order, Map.of());
	}

	private static DeclaredValue declared(String value, Integer order, Map<String, String> labels) {
		var builder = DeclaredValue.newBuilder()
			.setValue(value)
			.putAllLabels(labels);

		if(order != null) {
			builder.setOrder(order);
		}

		return builder.build();
	}

	/**
	 * Settings declaring the given values of one field.
	 */
	private static SearchSettings.Snapshot declaring(String field, DeclaredValue... values) {
		var stored = SearchSettingsStore.newBuilder()
			.putFields(
				field,
				FieldSettings.newBuilder()
					.addAllValues(Lists.immutable.of(values))
					.build()
			)
			.build();

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
	 * A shop with sizes to order, colours to label and a material held in
	 * two languages, written over two segments.
	 */
	private Index shop() throws IOException {
		var index = create(
			"shop",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields("size", faceted(string()).build())
				.putFields("colour", faceted(string()).build())
				.putFields(
					"material",
					faceted(string())
						.setLocales(
							FieldDef.LocaleConfig.newBuilder()
								.setDefaultLocale("sv")
								.addLocales("en")
						)
						.build()
				)
		);

		index.addDocument(item("1", "S", "red"));
		index.addDocument(item("2", "S", "red"));
		index.addDocument(item("3", "S", "blue"));
		index.addDocument(item("4", "M", "red"));
		index.addDocument(item("5", "M", "blue"));
		index.commit();

		index.addDocument(item("6", "M", "green"));
		index.addDocument(item("7", "S", "green"));
		index.addDocument(item("8", "L", "red"));
		index.addDocument(item("9", "L", "blue"));
		index.addDocument(item("10", "XL", "red"));
		index.addDocument(item("11", "XXL", "blue"));
		index.addDocument(item("12", "OS", "red"));
		index.addDocument(item("13", "OS", "blue"));
		index.addDocument(
			new Document(
				new Document.Value("id", "14"),
				new Document.Value("material", "bomull", "sv"),
				new Document.Value("material", "cotton", "en")
			)
		);
		index.commit();

		return index;
	}

	private static Document item(String id, String size, String colour) {
		return new Document(
			new Document.Value("id", id),
			new Document.Value("size", size),
			new Document.Value("colour", colour)
		);
	}

	private static FieldDef.Builder faceted(FieldDef.Builder builder) {
		return builder
			.setFilter(FilterConfig.getDefaultInstance())
			.setFacet(FacetConfig.getDefaultInstance());
	}

	private static FieldDef.Builder string() {
		return FieldDef.newBuilder()
			.setType(FieldTypeDef.newBuilder().setString(StringFieldTypeDef.newBuilder()));
	}
}
