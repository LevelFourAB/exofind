package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.Int32FieldTypeDef;
import se.l4.exofind.engine.index.schema.RankingConfig;
import se.l4.exofind.engine.index.schema.SortConfig;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.schema.TimestampFieldTypeDef;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.RankingSignal;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.SortBy;
import se.l4.exofind.engine.query.matchers.Matchers;

/**
 * Tests for the values of the documents themselves taking part in their score -
 * what a shape makes of a value, what a document holding none is left as, and
 * where in a search the multiplier is and is not read.
 */
public class RankingSignalSearchTest extends AbstractIndexTest {
	/**
	 * How close two scores have to be to count as the same. Scores are floats
	 * that have been through a multiplication, so they are compared as numbers
	 * rather than for equality.
	 */
	private static final double PRECISION = 0.0001;

	@Test
	public void testDocumentsAreRankedByTheirCount() throws IOException {
		var index = products(purchases(50));

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("runner"))
				.build()
		);

		// Same words in the same field, so the count is what tells them apart
		assertThat(ids(result), contains("popular", "quiet"));
	}

	@Test
	public void testACountOfNothingRanksBelowOneOfSomething() throws IOException {
		var index = products(purchases(50));

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("sneaker"))
				.build()
		);

		assertThat(ids(result), contains("some", "none"));
	}

	@Test
	public void testADocumentWithoutAValueKeepsItsScore() throws IOException {
		var index = products(purchases(50));

		var with = index.search(
			SearchRequest.create()
				.withQuery(
					Query.text("sneaker"),
					Query.field("id", Matchers.equalTo("none"))
				)
				.build()
		);

		var without = index.search(
			SearchRequest.create()
				.withQuery(
					Query.text("sneaker"),
					Query.field("id", Matchers.equalTo("none"))
				)
				.withSignals(SearchRequest.Signals.replace())
				.build()
		);

		/*
		 * The whole point of a bounded shape and a neutral multiplier: a
		 * document the signal says nothing about is left exactly as it matched.
		 */
		assertThat(
			(double) with.hits().get(0).score(),
			is(closeTo(without.hits().get(0).score(), PRECISION))
		);
	}

	@Test
	public void testAValueAtThePivotIsWorthHalfTheWeight() throws IOException {
		var index = products(purchases(50));

		var ranked = index.search(
			SearchRequest.create()
				.withQuery(Query.field("id", Matchers.equalTo("popular")))
				.build()
		);

		var plain = index.search(
			SearchRequest.create()
				.withQuery(Query.field("id", Matchers.equalTo("popular")))
				.withSignals(SearchRequest.Signals.replace())
				.build()
		);

		/*
		 * `popular` was bought exactly as often as the pivot, so it contributes
		 * half of what the signal can give - a multiplier of 1 + 1 * 0.5.
		 */
		assertThat(
			(double) ranked.hits().get(0).score(),
			is(closeTo(plain.hits().get(0).score() * 1.5, PRECISION))
		);
	}

	@Test
	public void testAWeightOfNothingChangesNothing() throws IOException {
		var index = products(
			RankingConfig.Signal.newBuilder()
				.setField("purchases")
				.setSaturation(RankingConfig.Signal.Saturation.newBuilder().setPivot(50))
				.setWeight(0)
				.build()
		);

		var weightless = index.search(
			SearchRequest.create()
				.withQuery(Query.text("runner"))
				.build()
		);

		var plain = index.search(
			SearchRequest.create()
				.withQuery(Query.text("runner"))
				.withSignals(SearchRequest.Signals.replace())
				.build()
		);

		assertThat(ids(weightless), is(ids(plain)));
		assertThat(
			(double) weightless.hits().get(0).score(),
			is(closeTo(plain.hits().get(0).score(), PRECISION))
		);
	}

	@Test
	public void testRecentDocumentsRankAboveOlderOnes() throws IOException {
		var index = products(
			RankingConfig.Signal.newBuilder()
				.setField("published")
				.setDecay(
					RankingConfig.Signal.Decay.newBuilder()
						.setHalfLifeSeconds(Duration.ofDays(7).toSeconds())
				)
				.build()
		);

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("runner"))
				.build()
		);

		// `quiet` was published today, `popular` a year ago
		assertThat(ids(result), contains("quiet", "popular"));
	}

	@Test
	public void testASearchRanksByItsOwnSignalsInsteadOfTheIndexesWhenItReplacesThem() throws IOException {
		var index = products(purchases(50));

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("runner"))
				.withSignals(
					SearchRequest.Signals.replace(
						RankingSignal.decay("published", Duration.ofDays(7))
					)
				)
				.build()
		);

		/*
		 * The index would have put the popular one first; the search asked to
		 * rank by recency instead, and the two do not add up.
		 */
		assertThat(ids(result), contains("quiet", "popular"));
	}

	@Test
	public void testASearchAddsItsOwnSignalsToTheIndexes() throws IOException {
		var index = products(purchases(50));

		var added = index.search(
			SearchRequest.create()
				.withQuery(Query.field("id", Matchers.equalTo("popular")))
				.withSignals(RankingSignal.decay("published", Duration.ofDays(365)))
				.build()
		);

		var plain = index.search(
			SearchRequest.create()
				.withQuery(Query.field("id", Matchers.equalTo("popular")))
				.withSignals(SearchRequest.Signals.replace())
				.build()
		);

		/*
		 * `popular` was bought exactly as often as the index's pivot and was
		 * published exactly one half life ago, so each signal is worth 1.5 and
		 * both of them are read. A search replacing the ranking would have
		 * scored it 1.5 times the plain match.
		 */
		assertThat(
			(double) added.hits().get(0).score(),
			is(closeTo(plain.hits().get(0).score() * 2.25, PRECISION))
		);
	}

	@Test
	public void testASignalStandsInForTheIndexesOnTheSameField() throws IOException {
		var index = products(purchases(50));

		var added = index.search(
			SearchRequest.create()
				.withQuery(Query.field("id", Matchers.equalTo("popular")))
				.withSignals(RankingSignal.saturation("purchases", 50).withWeight(0.5f))
				.build()
		);

		var plain = index.search(
			SearchRequest.create()
				.withQuery(Query.field("id", Matchers.equalTo("popular")))
				.withSignals(SearchRequest.Signals.replace())
				.build()
		);

		/*
		 * Both signals read `purchases` at its pivot, so the one the search
		 * brought is worth 1.25 on its own. Two of them compounded would have
		 * been 1.875, and the index's alone 1.5.
		 */
		assertThat(
			(double) added.hits().get(0).score(),
			is(closeTo(plain.hits().get(0).score() * 1.25, PRECISION))
		);
	}

	@Test
	public void testASearchCanRankByNothingAtAll() throws IOException {
		var index = products(purchases(50));

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("runner"))
				.withSignals(SearchRequest.Signals.replace())
				.build()
		);

		var scores = result.hits().collect(SearchResult.Hit::score);
		assertThat((double) scores.get(0), is(closeTo(scores.get(1), PRECISION)));
	}

	@Test
	public void testASortOfItsOwnIsLeftAlone() throws IOException {
		var index = products(purchases(50));

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("runner"))
				.withSort(SortBy.field("id", SortBy.Order.DESCENDING))
				.build()
		);

		// Ordered by the field the search asked for, not by what sells
		assertThat(ids(result), contains("quiet", "popular"));
	}

	@Test
	public void testAListingIsRankedByTheSignalAlone() throws IOException {
		var index = products(purchases(50));

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.field("category", Matchers.equalTo("shoes")))
				.build()
		);

		/*
		 * Every document matches the category the same way, so the signal is
		 * the whole of the ranking - which is what turns browsing a category
		 * into what sells best first. The two holding nothing to rank by are
		 * left tied at the end.
		 */
		assertThat(ids(result).subList(0, 2), contains("popular", "some"));
		assertThat(ids(result).subList(2, 4), containsInAnyOrder("quiet", "none"));
	}

	@Test
	public void testASignalOnAnUnknownFieldIsRefused() throws IOException {
		var index = products(purchases(50));

		assertThrows(
			IndexFieldNotFoundException.class,
			() -> index.search(
				SearchRequest.create()
					.withSignals(RankingSignal.saturation("missing", 10))
					.build()
			)
		);
	}

	@Test
	public void testASignalOnAFieldWithoutSortIsRefused() throws IOException {
		var index = products(purchases(50));

		assertThrows(
			IndexFieldUsageException.class,
			() -> index.search(
				SearchRequest.create()
					.withSignals(RankingSignal.saturation("views", 10))
					.build()
			)
		);
	}

	@Test
	public void testAShapeAFieldCanNotAnswerForIsRefused() throws IOException {
		var index = products(purchases(50));

		assertThrows(
			IndexInvalidQueryTypeException.class,
			() -> index.search(
				SearchRequest.create()
					.withSignals(RankingSignal.decay("purchases", Duration.ofDays(7)))
					.build()
			)
		);
	}

	private static RankingConfig.Signal purchases(double pivot) {
		return RankingConfig.Signal.newBuilder()
			.setField("purchases")
			.setSaturation(RankingConfig.Signal.Saturation.newBuilder().setPivot(pivot))
			.build();
	}

	/**
	 * The primary keys of the hits, in the order they came back.
	 */
	private static List<Object> ids(SearchResult result) {
		return result.hits().collect(SearchResult.Hit::id).toList();
	}

	/**
	 * Four products matching two searches, so that documents which match
	 * equally well are told apart by nothing but the signal.
	 */
	private Index products(RankingConfig.Signal signal) throws IOException {
		var index = create(
			"products",
			IndexDef.newBuilder()
				.putFields(
					"id",
					FieldDef.newBuilder()
						.setPrimaryKey(true)
						.setType(
							FieldTypeDef.newBuilder()
								.setString(StringFieldTypeDef.getDefaultInstance())
						)
						.setFilter(FilterConfig.getDefaultInstance())
						.setSort(SortConfig.getDefaultInstance())
						.build()
				)
				.putFields(
					"name",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setString(
									StringFieldTypeDef.newBuilder()
										.setMatching(
											StringFieldTypeDef.TextUsageConfig
												.getDefaultInstance()
										)
								)
						)
						.build()
				)
				.putFields(
					"category",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setString(StringFieldTypeDef.getDefaultInstance())
						)
						.setFilter(FilterConfig.getDefaultInstance())
						.build()
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
					"views",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setInt32(Int32FieldTypeDef.getDefaultInstance())
						)
						.build()
				)
				.putFields(
					"published",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setTimestamp(TimestampFieldTypeDef.getDefaultInstance())
						)
						.setSort(SortConfig.getDefaultInstance())
						.build()
				)
				.setRanking(RankingConfig.newBuilder().addSignals(signal))
		);

		var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

		index.addDocument(
			new Document(
				new Document.Value("id", "popular"),
				new Document.Value("name", "Trail runner"),
				new Document.Value("category", "shoes"),
				new Document.Value("purchases", 50),
				new Document.Value("published", now.minus(Duration.ofDays(365)).toString())
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "quiet"),
				new Document.Value("name", "Trail runner"),
				new Document.Value("category", "shoes"),
				new Document.Value("purchases", 0),
				new Document.Value("published", now.toString())
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "some"),
				new Document.Value("name", "City sneaker"),
				new Document.Value("category", "shoes"),
				new Document.Value("purchases", 10),
				new Document.Value("published", now.minus(Duration.ofDays(30)).toString())
			)
		);

		// Holds no count at all, which a signal has to leave alone
		index.addDocument(
			new Document(
				new Document.Value("id", "none"),
				new Document.Value("name", "City sneaker"),
				new Document.Value("category", "shoes"),
				new Document.Value("published", now.minus(Duration.ofDays(30)).toString())
			)
		);

		index.commit();
		return index;
	}
}
