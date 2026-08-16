package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.AnalyzerDef;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.schema.TokenFilterDef;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.TextQuery;
import se.l4.exofind.engine.query.matchers.Matchers;

/**
 * Tests for a value a search matched whole ranking above one that merely holds
 * the same words - where the lift applies, what it is measured against, and
 * that it only ever reorders what the words had already found.
 *
 * Every fixture here comes in two indexes over the same documents, one that
 * declared the lift and one that did not, because the point is what the
 * declaration changes. The documents are shaped so the plain reading gets it
 * wrong: a listing repeating a name several times outscores the thing itself,
 * which is what a shop meets on the query it sees most.
 */
public class ExactMatchSearchTest extends AbstractIndexTest {
	@Test
	public void testAValueMatchedWholeRanksAboveOneThatRepeatsIt() throws IOException {
		var index = phones("declared", true);

		var result = search(index, Query.text("iphone 15"));

		assertThat(ids(result), contains("phone", "accessories"));
	}

	/**
	 * The same documents and the same search without the declaration, which is
	 * what says the ordering above came from it. Term frequency wins here: the
	 * listing says the name five times, and being named it says it once.
	 */
	@Test
	public void testWithoutTheDeclarationRepetitionWins() throws IOException {
		var index = phones("plain", false);

		var result = search(index, Query.text("iphone 15"));

		assertThat(ids(result), contains("accessories", "phone"));
	}

	@Test
	public void testRankingOnlyEverReordersWhatTheWordsFound() throws IOException {
		var declared = search(phones("declared", true), Query.text("iphone 15"));
		var plain = search(phones("plain", false), Query.text("iphone 15"));

		assertThat(ids(declared), containsInAnyOrder(ids(plain).toArray()));
	}

	/**
	 * A whole-value match is the value as the field reads it, so what the
	 * chain folds away is folded away on both sides.
	 */
	@Test
	public void testCaseIsFoldedTheWayTheFieldFoldsIt() throws IOException {
		var index = phones("declared", true);

		var result = search(index, Query.text("IPHONE 15"));

		assertThat(ids(result), contains("phone", "accessories"));
	}

	/**
	 * And so are accents, where the chain of the field asked for that - the
	 * normalization of the chain runs over the whole value, not only over its
	 * words.
	 */
	@Test
	public void testAccentsAreFoldedWhereTheChainFoldsThem() throws IOException {
		var index = drinks();

		var result = search(index, Query.text("cafe latte"));

		assertThat(ids(result), contains("drink", "menu"));
	}

	/**
	 * The words of a search are combined across fields by default, where a
	 * value taken whole belongs to no single word and is counted once beside
	 * them.
	 */
	@Test
	public void testItAppliesWhenTheWordsAreCombinedAcrossFields() throws IOException {
		var index = phones("declared", true);

		var result = search(
			index,
			Query.text("iphone 15").withCombine(TextQuery.Combine.TERM)
		);

		assertThat(ids(result), contains("phone", "accessories"));
	}

	@Test
	public void testItAppliesWhenAFieldHasToMatchOnItsOwn() throws IOException {
		var index = phones("declared", true);

		var result = search(
			index,
			Query.text("iphone 15").withCombine(TextQuery.Combine.FIELD)
		);

		assertThat(ids(result), contains("phone", "accessories"));
	}

	@Test
	public void testItAppliesToASearchOfTheFieldAlone() throws IOException {
		var index = phones("declared", true);

		var result = search(index, Query.field("name", Matchers.text("iphone 15")));

		assertThat(ids(result), contains("phone", "accessories"));
	}

	/**
	 * Whole means whole, including the last word. Half a name is not a name,
	 * and lifting every value that starts with what has been typed so far
	 * would lift the listings the name was meant to beat.
	 */
	@Test
	public void testAWordStillBeingTypedIsNotAWholeValue() throws IOException {
		var index = phones("declared", true);

		var result = search(index, Query.text("iphone 1"));

		assertThat(ids(result), contains("accessories", "phone"));
	}

	@Test
	public void testAFieldThatOnlyCompletesTextCarriesItToo() throws IOException {
		var declared = search(cities("declared", true), Query.text("stockholm"));
		var plain = search(cities("plain", false), Query.text("stockholm"));

		assertThat(ids(declared), contains("city", "airport"));
		assertThat(ids(plain), contains("airport", "city"));
	}

	/**
	 * A phone and the accessories listed for it, where the listing says the
	 * name five times over.
	 */
	private Index phones(String name, boolean exact) throws IOException {
		var index = create(
			name,
			IndexDef.newBuilder()
				.putFields("id", primaryKey())
				.putFields("name", matching(exact, null))
				.putFields("description", matching(false, null))
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "phone"),
				new Document.Value("name", "iPhone 15"),
				new Document.Value("description", "The phone itself")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "accessories"),
				new Document.Value(
					"name",
					"iPhone 15 case iPhone 15 cover iPhone 15 screen protector"
						+ " iPhone 15 charger iPhone 15 cable"
				),
				new Document.Value("description", "Everything for it")
			)
		);

		index.commit();
		return index;
	}

	/**
	 * A drink and a menu repeating it, on a field whose chain folds accents
	 * away.
	 */
	private Index drinks() throws IOException {
		var folding = AnalyzerDef.newBuilder()
			.addFilters(
				TokenFilterDef.newBuilder()
					.setNormalize(TokenFilterDef.Normalize.getDefaultInstance())
			)
			.addFilters(
				TokenFilterDef.newBuilder()
					.setAsciiFolding(TokenFilterDef.AsciiFolding.getDefaultInstance())
			)
			.build();

		var index = create(
			"drinks",
			IndexDef.newBuilder()
				.putFields("id", primaryKey())
				.putFields("name", matching(true, folding))
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "drink"),
				new Document.Value("name", "Café Latte")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "menu"),
				new Document.Value(
					"name",
					"Café Latte Café Latte Café Latte Café Latte Café Latte"
						+ " Café Latte Café Latte Café Latte"
				)
			)
		);

		index.commit();
		return index;
	}

	/**
	 * A city and an airport listing repeating its name, on a field written
	 * only to complete what is being typed.
	 */
	private Index cities(String name, boolean exact) throws IOException {
		var autocomplete = StringFieldTypeDef.TextUsageConfig.newBuilder();
		if(exact) {
			autocomplete.setExact(
				StringFieldTypeDef.TextUsageConfig.ExactConfig.getDefaultInstance()
			);
		}

		var index = create(
			name,
			IndexDef.newBuilder()
				.putFields("id", primaryKey())
				.putFields(
					"name",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setString(
									StringFieldTypeDef.newBuilder()
										.setAutocomplete(autocomplete)
								)
						)
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "city"),
				new Document.Value("name", "Stockholm")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "airport"),
				new Document.Value(
					"name",
					"Stockholm Stockholm Stockholm Stockholm Stockholm"
						+ " Stockholm Stockholm Stockholm Stockholm Stockholm"
				)
			)
		);

		index.commit();
		return index;
	}

	private static FieldDef primaryKey() {
		return FieldDef.newBuilder()
			.setPrimaryKey(true)
			.setType(FieldTypeDef.newBuilder().setString(StringFieldTypeDef.getDefaultInstance()))
			.setFilter(FilterConfig.getDefaultInstance())
			.build();
	}

	/**
	 * A field searched for text, optionally ranking a whole-value match above
	 * a mention, and optionally analyzed by a chain of its own.
	 */
	private static FieldDef matching(boolean exact, AnalyzerDef analyzer) {
		var usage = StringFieldTypeDef.TextUsageConfig.newBuilder();

		if(exact) {
			usage.setExact(StringFieldTypeDef.TextUsageConfig.ExactConfig.getDefaultInstance());
		}

		if(analyzer != null) {
			usage.setAnalyzer(analyzer);
		}

		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder()
					.setString(StringFieldTypeDef.newBuilder().setMatching(usage))
			)
			.build();
	}

	private static SearchResult search(Index index, Query... query) throws IOException {
		return index.search(SearchRequest.create().withQuery(query).build());
	}

	private static List<Object> ids(SearchResult result) {
		return result.hits().collect(SearchResult.Hit::id).toList();
	}
}
