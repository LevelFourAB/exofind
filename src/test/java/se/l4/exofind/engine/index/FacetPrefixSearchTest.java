package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.BooleanFieldTypeDef;
import se.l4.exofind.engine.index.schema.FacetConfig;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.Int32FieldTypeDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.schema.TimestampFieldTypeDef;
import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.FieldQuery;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.matchers.Matchers;

/**
 * Tests for a facet that answers only the values starting with a prefix -
 * how the prefix and the values are folded before they are compared, that
 * the counts stay the ones a facet of the search answers, and which fields
 * refuse a prefix.
 */
public class FacetPrefixSearchTest extends AbstractIndexTest {
	@Test
	public void testPrefixPicksTheValuesStartingWithIt() throws IOException {
		var index = shop();

		var facet = count(index, Facet.of("brand").withPrefix("adi"));

		assertThat(facet.totalValues(), is(2));
		assertThat(
			facet.values(),
			contains(
				new SearchResult.Facet.Value("adidas", 2),
				new SearchResult.Facet.Value("Adidas Originals", 1)
			)
		);
	}

	@Test
	public void testPrefixIsComparedFoldedInCase() throws IOException {
		var index = shop();

		var facet = count(index, Facet.of("brand").withPrefix("ADI"));

		assertThat(facet.totalValues(), is(2));
		assertThat(
			facet.values(),
			containsInAnyOrder(
				new SearchResult.Facet.Value("adidas", 2),
				new SearchResult.Facet.Value("Adidas Originals", 1)
			)
		);
	}

	@Test
	public void testPrefixIsComparedFoldedInUnicodeForm() throws IOException {
		var index = shop();

		var facet = count(index, Facet.of("colour").withPrefix("rö"));

		assertThat(facet.values(), contains(new SearchResult.Facet.Value("Röd", 2)));
	}

	@Test
	public void testPrefixDoesNotFoldAccentsAway() throws IOException {
		var index = shop();

		var facet = count(index, Facet.of("colour").withPrefix("ro"));

		assertThat(facet.values(), contains(new SearchResult.Facet.Value("Rosa", 1)));
	}

	@Test
	public void testBlankPrefixAnswersEveryValue() throws IOException {
		var index = shop();

		var facet = count(index, Facet.of("brand").withPrefix("  "));

		assertThat(facet.totalValues(), is(4));
	}

	@Test
	public void testNothingStartsWithThePrefix() throws IOException {
		var index = shop();

		var facet = count(index, Facet.of("brand").withPrefix("zz"));

		assertThat(facet.totalValues(), is(0));
		assertThat(facet.values().isEmpty(), is(true));
	}

	@Test
	public void testValuesComeBackInValueOrder() throws IOException {
		var index = shop();

		var facet = count(
			index,
			Facet.of("brand").withPrefix("a").withOrder(Facet.Order.VALUE)
		);

		assertThat(
			facet.values(),
			contains(
				new SearchResult.Facet.Value("Adidas Originals", 1),
				new SearchResult.Facet.Value("Asics", 1),
				new SearchResult.Facet.Value("adidas", 2)
			)
		);
	}

	@Test
	public void testLimitCutsTheValuesAndNotTheTotal() throws IOException {
		var index = shop();

		var facet = count(index, Facet.of("brand").withPrefix("a").withLimit(1));

		assertThat(facet.totalValues(), is(3));
		assertThat(facet.values(), contains(new SearchResult.Facet.Value("adidas", 2)));
	}

	@Test
	public void testCountsNarrowByTheQuery() throws IOException {
		var index = shop();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.field("inStock", Matchers.equalTo(true)))
				.addFacet(Facet.of("brand").withPrefix("a"))
				.withLimit(0)
				.build()
		);

		var facet = result.facets().get("brand");
		assertThat(
			facet.values(),
			containsInAnyOrder(
				new SearchResult.Facet.Value("adidas", 1),
				new SearchResult.Facet.Value("Asics", 1)
			)
		);
	}

	@Test
	public void testCountsAreSidewaysOfTheFilterOnTheirOwnField() throws IOException {
		var index = shop();

		var result = index.search(
			SearchRequest.create()
				.addFilter(new FieldQuery("brand", Matchers.equalTo("Nike")))
				.addFilter(new FieldQuery("inStock", Matchers.equalTo(true)))
				.addFacet(Facet.of("brand").withPrefix("a"))
				.withLimit(0)
				.build()
		);

		var facet = result.facets().get("brand");
		assertThat(
			facet.values(),
			containsInAnyOrder(
				new SearchResult.Facet.Value("adidas", 1),
				new SearchResult.Facet.Value("Asics", 1)
			)
		);
	}

	@Test
	public void testPrefixIsFoundAcrossSegments() throws IOException {
		var index = shop();

		index.addDocument(
			new Document(
				new Document.Value("id", "7"),
				new Document.Value("brand", "Adidas Originals"),
				new Document.Value("colour", "Röd"),
				new Document.Value("inStock", false),
				new Document.Value("stock", 0),
				new Document.Value("added", "2024-08-01T12:00:00Z")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "8"),
				new Document.Value("brand", "Altra"),
				new Document.Value("colour", "Blå"),
				new Document.Value("inStock", true),
				new Document.Value("stock", 1),
				new Document.Value("added", "2024-08-01T12:00:00Z")
			)
		);

		index.commit();

		var facet = count(index, Facet.of("brand").withPrefix("a"));

		assertThat(facet.totalValues(), is(4));
		assertThat(
			facet.values(),
			containsInAnyOrder(
				new SearchResult.Facet.Value("adidas", 2),
				new SearchResult.Facet.Value("Adidas Originals", 2),
				new SearchResult.Facet.Value("Asics", 1),
				new SearchResult.Facet.Value("Altra", 1)
			)
		);
	}

	@Test
	public void testPrefixOnANumberComparesTheValueAsShown() throws IOException {
		var index = shop();

		var facet = count(index, Facet.of("stock").withPrefix("1"));

		assertThat(
			facet.values(),
			containsInAnyOrder(
				new SearchResult.Facet.Value(1, 1),
				new SearchResult.Facet.Value(12, 2)
			)
		);
	}

	@Test
	public void testPrefixOnATimestampComparesTheValueAsShown() throws IOException {
		var index = shop();

		var facet = count(index, Facet.of("added").withPrefix("2024-06"));

		assertThat(facet.totalValues(), is(1));
		assertThat(facet.values().getFirst().count(), is(3L));
	}

	@Test
	public void testPrefixOnABooleanIgnoresCase() throws IOException {
		var index = shop();

		var facet = count(index, Facet.of("inStock").withPrefix("T"));

		assertThat(facet.values(), contains(new SearchResult.Facet.Value(true, 3)));
	}

	@Test
	public void testPrefixIsPartOfWhatIsKept() throws IOException {
		var index = shop();

		var before = FacetStates.stats().hits();
		var first = count(index, Facet.of("brand").withPrefix("a"));
		var again = count(index, Facet.of("brand").withPrefix("a"));
		var other = count(index, Facet.of("brand").withPrefix("n"));

		assertThat(again, is(first));
		assertThat(other.values(), contains(new SearchResult.Facet.Value("Nike", 2)));
		assertThat(FacetStates.stats().hits() - before, is(greaterThan(0L)));
	}

	@Test
	public void testPrefixWithRangesIsRefused() {
		assertThrows(
			IllegalArgumentException.class,
			() -> Facet.of("stock")
				.withRanges(new Facet.Range(0, 10))
				.withPrefix("1")
		);
	}

	@Test
	public void testPrefixOnATreeIsRefused() throws IOException {
		var index = create(
			"catalogue",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"category",
					faceted(
						string(
							StringFieldTypeDef.newBuilder()
								.setHierarchy(
									StringFieldTypeDef.HierarchyConfig.getDefaultInstance()
								)
						)
					).build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("category", "Men/Shoes")
			)
		);
		index.commit();

		var e = assertThrows(
			IndexException.class,
			() -> count(index, Facet.of("category").withPrefix("M"))
		);

		assertThat(e.getCode(), is("index:query:facet_prefix_on_a_tree"));
	}

	/**
	 * Count one facet under everything the index holds.
	 */
	private static SearchResult.Facet count(Index index, Facet facet) throws IOException {
		var result = index.search(
			SearchRequest.create()
				.addFacet(facet)
				.withLimit(0)
				.build()
		);

		return result.facets().get(facet.name());
	}

	/**
	 * A small shop with faceted fields of every kind a prefix is compared on.
	 */
	private Index shop() throws IOException {
		var index = create(
			"shop",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields("brand", faceted(string()).build())
				.putFields("colour", faceted(string()).build())
				.putFields("inStock", faceted(bool()).build())
				.putFields("stock", faceted(int32()).build())
				.putFields("added", faceted(timestamp()).build())
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("brand", "adidas"),
				new Document.Value("colour", "Röd"),
				new Document.Value("inStock", true),
				new Document.Value("stock", 12),
				new Document.Value("added", "2024-06-01T12:00:00Z")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("brand", "adidas"),
				new Document.Value("colour", "Röd"),
				new Document.Value("inStock", false),
				new Document.Value("stock", 0),
				new Document.Value("added", "2024-06-01T12:00:00Z")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "3"),
				new Document.Value("brand", "Adidas Originals"),
				new Document.Value("colour", "Rosa"),
				new Document.Value("inStock", false),
				new Document.Value("stock", 0),
				new Document.Value("added", "2024-06-01T12:00:00Z")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "4"),
				new Document.Value("brand", "Asics"),
				new Document.Value("colour", "Blå"),
				new Document.Value("inStock", true),
				new Document.Value("stock", 1),
				new Document.Value("added", "2024-07-01T12:00:00Z")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "5"),
				new Document.Value("brand", "Nike"),
				new Document.Value("colour", "Blå"),
				new Document.Value("inStock", true),
				new Document.Value("stock", 12),
				new Document.Value("added", "2024-07-01T12:00:00Z")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "6"),
				new Document.Value("brand", "Nike"),
				new Document.Value("colour", "Blå"),
				new Document.Value("inStock", false),
				new Document.Value("stock", 0),
				new Document.Value("added", "2024-07-01T12:00:00Z")
			)
		);

		index.commit();
		return index;
	}

	private static FieldDef.Builder faceted(FieldDef.Builder builder) {
		return builder
			.setFilter(FilterConfig.getDefaultInstance())
			.setFacet(FacetConfig.getDefaultInstance());
	}

	private static FieldDef.Builder string() {
		return string(StringFieldTypeDef.newBuilder());
	}

	private static FieldDef.Builder string(StringFieldTypeDef.Builder type) {
		return FieldDef.newBuilder()
			.setType(FieldTypeDef.newBuilder().setString(type));
	}

	private static FieldDef.Builder bool() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setBoolean(BooleanFieldTypeDef.getDefaultInstance())
			);
	}

	private static FieldDef.Builder int32() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setInt32(Int32FieldTypeDef.getDefaultInstance())
			);
	}

	private static FieldDef.Builder timestamp() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder()
					.setTimestamp(TimestampFieldTypeDef.getDefaultInstance())
			);
	}
}
