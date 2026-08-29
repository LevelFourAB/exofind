package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.Int32FieldTypeDef;
import se.l4.exofind.engine.index.schema.ObjectFieldTypeDef;
import se.l4.exofind.engine.index.schema.SortConfig;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.RankingSignal;
import se.l4.exofind.engine.query.SearchExplanation;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.matchers.Matchers;
import se.l4.exofind.engine.query.matchers.TextMatcher;

/**
 * Tests for what an explanation says about one hit - that the score is the one
 * a search reports, that the steps name the clauses and the fields the caller
 * wrote, and that a hit standing for a value of an object field is explained by
 * the position the search reported it at.
 */
public class ExplainSearchTest extends AbstractIndexTest {
	/**
	 * How close two scores have to be to count as the same. Scores are floats
	 * arrived at by different routes through the same arithmetic.
	 */
	private static final double PRECISION = 0.0001;

	@Test
	public void testScoreIsTheOneTheSearchReports() throws IOException {
		var index = products();

		var request = SearchRequest.create()
			.withQuery(Query.text(words("runner")))
			.build();

		var result = index.search(request);
		var explanation = index.explain(request, "1", 0, null);

		assertThat(explanation.matched(), is(true));
		assertThat(
			(double) explanation.score(),
			is(closeTo(scoreOf(result, "1"), PRECISION))
		);
	}

	@Test
	public void testEveryClauseOfTheRequestIsNamedByItsPlaceInIt() throws IOException {
		var index = products();

		var request = SearchRequest.create()
			.withQuery(
				Query.text(words("runner")),
				Query.or(
					Query.field("category", Matchers.equalTo("shoes")),
					Query.field("category", Matchers.equalTo("boots"))
				)
			)
			.withFilters(Query.field("category", Matchers.equalTo("shoes")))
			.build();

		var clauses = clausesOf(index.explain(request, "1", 0, null));

		assertThat(clauses, hasItem("query[0]"));
		assertThat(clauses, hasItem("query[1]"));
		assertThat(clauses, hasItem("query[1].clauses[0]"));
		assertThat(clauses, hasItem("filters[0]"));
	}

	@Test
	public void testAlternativeThatLostToAnotherIsNotReported() throws IOException {
		var index = products();

		var request = SearchRequest.create()
			.withQuery(
				Query.or(
					Query.field("category", Matchers.equalTo("shoes")),
					Query.field("category", Matchers.equalTo("boots"))
				)
			)
			.build();

		/*
		 * The Trail Runner is a shoe, so the first alternative answered for it
		 * and the second is left out - what an alternative would have been
		 * worth is not part of how the hit scored.
		 */
		var clauses = clausesOf(index.explain(request, "1", 0, null));

		assertThat(clauses, hasItem("query[0].clauses[0]"));
		assertThat(clauses, not(hasItem("query[0].clauses[1]")));
	}

	@Test
	public void testClausesCarryTheKindTheRequestGaveThem() throws IOException {
		var index = products();

		var request = SearchRequest.create()
			.withQuery(Query.text(words("runner")))
			.build();

		var step = stepAt(index.explain(request, "1", 0, null), "query[0]");

		assertThat(step, is(notNullValue()));
		assertThat(step.clauseType(), is("text"));
	}

	@Test
	public void testFieldsAreNamedTheWayTheDefinitionNamesThem() throws IOException {
		var index = products();

		var request = SearchRequest.create()
			.withQuery(Query.text(words("runner")))
			.build();

		var explanation = index.explain(request, "1", 0, null);
		var steps = stepsOf(explanation);

		/*
		 * A field is written into Lucene once per way of using it, under a name
		 * carrying the usage and the locale. None of those names may reach a
		 * caller, in a description or anywhere else.
		 */
		assertThat(
			steps.stream().map(SearchExplanation.Detail::description).toList(),
			everyItem(not(containsString("name:_:matching")))
		);

		var read = steps.stream()
			.filter(step -> "name".equals(step.field()))
			.findFirst()
			.orElse(null);

		assertThat(read, is(notNullValue()));
		assertThat(read.usage(), is("matching"));

		// The field holds one variant for every language, so there is no tag
		assertThat(read.locale(), is(nullValue()));
	}

	@Test
	public void testDocumentTheSearchDoesNotMatchIsExplainedRatherThanRefused()
		throws IOException
	{
		var index = products();

		var request = SearchRequest.create()
			.withQuery(
				Query.text(words("runner")),
				Query.field("category", Matchers.equalTo("boots"))
			)
			.build();

		// The Trail Runner is a shoe, so the second clause is what rules it out
		var explanation = index.explain(request, "1", 0, null);

		assertThat(explanation.matched(), is(false));
		assertThat(explanation.score(), is(0f));

		var ruledOut = stepAt(explanation, "query[1]");
		assertThat(ruledOut, is(notNullValue()));
		assertThat(ruledOut.matched(), is(false));

		// What did match is still reported, so the two can be told apart
		var matched = stepAt(explanation, "query[0]");
		assertThat(matched, is(notNullValue()));
		assertThat(matched.matched(), is(true));
	}

	@Test
	public void testValueHitIsExplainedByThePositionTheSearchReported() throws IOException {
		var index = products();

		var request = SearchRequest.create()
			.withQuery(Query.nested("variants", Query.text(words("waterproof"))))
			.withHits("variants")
			.build();

		var result = index.search(request);
		var hit = result.hits().detect(h -> "4".equals(h.id()) && h.index() == 1);
		assertThat(hit, is(notNullValue()));

		var explanation = index.explain(request, "4", hit.index(), null);

		assertThat(explanation.matched(), is(true));
		assertThat(
			(double) explanation.score(),
			is(closeTo(hit.score(), PRECISION))
		);
	}

	@Test
	public void testValueHitsOfOneDocumentAreExplainedApart() throws IOException {
		var index = products();

		/*
		 * Both variants of the Ridge Boot are waterproof, and only one of them
		 * is leather, so the two positions do not answer the same.
		 */
		var request = SearchRequest.create()
			.withQuery(Query.nested("variants", Query.text(words("waterproof leather"))))
			.withHits("variants")
			.build();

		assertThat(index.explain(request, "4", 1, null).matched(), is(true));
		assertThat(index.explain(request, "4", 0, null).matched(), is(false));
	}

	@Test
	public void testValuePositionTheDocumentDoesNotHoldIsRefused() throws IOException {
		var index = products();

		var request = SearchRequest.create()
			.withQuery(Query.nested("variants", Query.text(words("waterproof"))))
			.withHits("variants")
			.build();

		var e = assertThrows(
			IndexException.class,
			() -> index.explain(request, "4", 7, null)
		);

		assertThat(e.getCode(), is("index:explain:value_not_found"));
	}

	@Test
	public void testKeyNothingIsIndexedUnderIsRefused() throws IOException {
		var index = products();

		var request = SearchRequest.create()
			.withQuery(Query.text(words("runner")))
			.build();

		var e = assertThrows(
			IndexException.class,
			() -> index.explain(request, "404", 0, null)
		);

		assertThat(e.getCode(), is("index:explain:document_not_found"));
	}

	@Test
	public void testSignalsAreExplainedPerFieldTheyRead() throws IOException {
		var index = products();

		var request = SearchRequest.create()
			.withQuery(Query.text(words("runner")))
			.withSignals(RankingSignal.saturation("purchases", 10))
			.build();

		var descriptions = stepsOf(index.explain(request, "1", 0, null)).stream()
			.map(SearchExplanation.Detail::description)
			.toList();

		assertThat(descriptions, hasItem(containsString("signal purchases")));
	}

	@Test
	public void testSearchThatLetsGoOfAWordIsExplainedAsItRan() throws IOException {
		var index = products();

		var request = SearchRequest.create()
			.withQuery(Query.text(
				TextMatcher.of("nonesuch runner").withPrefix(TextMatcher.Prefix.OFF)
			))
			.build();

		var result = index.search(request);
		assertThat(ids(result), contains("1"));

		var explanation = index.explain(request, "1", 0, null);

		assertThat(explanation.relaxed(), is(notNullValue()));
		assertThat(explanation.relaxed().text(), is("runner"));

		// Explained as the search that answered, so the hit matches
		assertThat(explanation.matched(), is(true));
		assertThat(
			(double) explanation.score(),
			is(closeTo(scoreOf(result, "1"), PRECISION))
		);
	}

	@Test
	public void testSearchThatFindsEverythingIsExplainedWithoutRelaxing() throws IOException {
		var index = products();

		var request = SearchRequest.create()
			.withQuery(Query.text(words("runner")))
			.build();

		assertThat(index.explain(request, "1", 0, null).relaxed(), is(nullValue()));
	}

	/**
	 * Collect the clauses an explanation names, in the order they are walked.
	 */
	private static List<String> clausesOf(SearchExplanation explanation) {
		return stepsOf(explanation).stream()
			.map(SearchExplanation.Detail::clause)
			.filter(clause -> clause != null)
			.toList();
	}

	/**
	 * Find the step standing for one clause of the request.
	 */
	private static SearchExplanation.Detail stepAt(
		SearchExplanation explanation,
		String clause
	) {
		return stepsOf(explanation).stream()
			.filter(step -> clause.equals(step.clause()))
			.findFirst()
			.orElse(null);
	}

	private static List<SearchExplanation.Detail> stepsOf(SearchExplanation explanation) {
		var steps = new ArrayList<SearchExplanation.Detail>();
		collect(explanation.detail(), steps);
		return steps;
	}

	private static void collect(
		SearchExplanation.Detail detail,
		List<SearchExplanation.Detail> into
	) {
		into.add(detail);
		for(var child : detail.children()) {
			collect(child, into);
		}
	}

	private static TextMatcher words(String text) {
		return TextMatcher.of(text).withPrefix(TextMatcher.Prefix.OFF);
	}

	private static List<Object> ids(SearchResult result) {
		return result.hits().collect(SearchResult.Hit::id).toList();
	}

	private static double scoreOf(SearchResult result, String id) {
		return result.hits().detect(hit -> id.equals(hit.id())).score();
	}

	private Index products() throws IOException {
		var index = create(
			"products",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(StringFieldTypeDef.TextUsageConfig.getDefaultInstance())
					).build()
				)
				.putFields(
					"category",
					string().setFilter(FilterConfig.getDefaultInstance()).build()
				)
				.putFields(
					"purchases",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setInt32(Int32FieldTypeDef.getDefaultInstance())
						)
						.setSort(SortConfig.getDefaultInstance())
						.build()
				)
				.putFields(
					"variants",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder().setObject(
								ObjectFieldTypeDef.newBuilder()
									.putFields(
										"color",
										string()
											.setFilter(FilterConfig.getDefaultInstance())
											.build()
									)
									.putFields(
										"material",
										string(
											StringFieldTypeDef.newBuilder().setMatching(
												StringFieldTypeDef.TextUsageConfig
													.getDefaultInstance()
											)
										).build()
									)
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
				new Document.Value("name", "Trail Runner"),
				new Document.Value("category", "shoes"),
				new Document.Value("purchases", 12),
				new Document.Value("variants", variant("red", "waterproof leather")),
				new Document.Value("variants", variant("black", "canvas"))
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "City Sneaker"),
				new Document.Value("category", "sneakers"),
				new Document.Value("purchases", 3),
				new Document.Value("variants", variant("blue", "suede"))
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "4"),
				new Document.Value("name", "Ridge Boot"),
				new Document.Value("category", "boots"),
				new Document.Value("purchases", 40),
				new Document.Value("variants", variant("red", "waterproof suede")),
				new Document.Value("variants", variant("red", "waterproof leather"))
			)
		);

		index.commit();
		return index;
	}

	private static Document variant(String color, String material) {
		return new Document(
			new Document.Value("color", color),
			new Document.Value("material", material)
		);
	}

	private static FieldDef.Builder string() {
		return string(StringFieldTypeDef.newBuilder());
	}

	private static FieldDef.Builder string(StringFieldTypeDef.Builder type) {
		return FieldDef.newBuilder().setType(FieldTypeDef.newBuilder().setString(type));
	}
}
