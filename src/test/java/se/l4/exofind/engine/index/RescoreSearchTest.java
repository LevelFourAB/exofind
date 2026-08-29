package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
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
import se.l4.exofind.engine.query.BoostQuery;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.RankingSignal;
import se.l4.exofind.engine.query.Rescore;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.SortBy;
import se.l4.exofind.engine.query.matchers.Matchers;

/**
 * Tests for a second pass over the best results - what it may reorder, what it
 * may not reach, and how a search continues past the window it covers.
 */
public class RescoreSearchTest extends AbstractIndexTest {
	/**
	 * How much of the second score counts. High enough that the second pass
	 * decides the order on its own, so a test states an order rather than a
	 * margin.
	 */
	private static final float DECIDING = 100f;

	@Test
	public void testTheWindowIsReorderedByABoost() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("runner"))
				.withLimit(3)
				.withRescore(
					Rescore.of(3, Query.field("brand", Matchers.equalTo("aurora")))
						.withWeight(DECIDING)
				)
				.build()
		);

		// `third` was the last of the window and the only one the boost reached
		assertThat(ids(result), contains("third", "first", "second"));
	}

	@Test
	public void testABoostCanNotReachBelowTheWindow() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("runner"))
				.withLimit(3)
				.withRescore(
					Rescore.of(3, Query.field("brand", Matchers.equalTo("nadir")))
						.withWeight(DECIDING)
				)
				.build()
		);

		/*
		 * `fifth` carries the brand, and would outrank everything if the boost
		 * took part in retrieval. It ranked below the window, so the page is the
		 * one relevance decided.
		 */
		assertThat(ids(result), contains("first", "second", "third"));
	}

	@Test
	public void testSignalsReorderInsideTheWindow() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("runner"))
				.withLimit(3)
				.withRescore(
					Rescore.ofSignals(3, RankingSignal.decay("published", Duration.ofDays(7)))
						.withWeight(DECIDING)
				)
				.build()
		);

		// The three the window holds, newest first
		assertThat(ids(result), contains("third", "second", "first"));
	}

	@Test
	public void testTheOrderIsUntouchedWithoutASecondPass() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("runner"))
				.withLimit(3)
				.build()
		);

		assertThat(ids(result), contains("first", "second", "third"));
	}

	@Test
	public void testASortOfItsOwnIsLeftAlone() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("runner"))
				.withSort(SortBy.field("id", SortBy.Order.ASCENDING))
				.withLimit(5)
				.withRescore(
					Rescore.of(5, Query.field("brand", Matchers.equalTo("nadir")))
						.withWeight(DECIDING)
				)
				.build()
		);

		// Ordered by the field the search asked for, which a second pass says nothing about
		assertThat(ids(result), contains("fifth", "first", "fourth", "second", "third"));
	}

	@Test
	public void testTheWindowNarrowsNothing() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("runner"))
				.withLimit(3)
				.withRescore(
					Rescore.of(3, Query.field("brand", Matchers.equalTo("aurora")))
						.withWeight(DECIDING)
				)
				.build()
		);

		// Every match is still counted, however few of them the boost reached
		assertThat(result.total().count(), is(5L));
		assertThat(result.hits().size(), is(3));
	}

	@Test
	public void testTheResultsBelowTheWindowAreContinuedTo() throws IOException {
		var index = products();

		var page = index.search(
			SearchRequest.create()
				.withQuery(Query.text("runner"))
				.withLimit(3)
				.withRescore(
					Rescore.of(3, Query.field("brand", Matchers.equalTo("aurora")))
						.withWeight(DECIDING)
				)
				.build()
		);

		assertThat(page.windowEnd(), is(notNullValue()));

		var below = index.search(
			SearchRequest.create()
				.withQuery(Query.text("runner"))
				.withLimit(3)
				.withAfter(page.windowEnd())
				.withRescore(
					Rescore.of(3, Query.field("brand", Matchers.equalTo("nadir")))
						.withWeight(DECIDING)
				)
				.build()
		);

		/*
		 * `fifth` carries the boosted brand and would lead if the second pass
		 * reached here. It is past the window, so the results carry on in the
		 * order relevance ranked.
		 */
		assertThat(ids(below), contains("fourth", "fifth"));
	}

	@Test
	public void testResultsEndingInsideTheWindowHaveNothingBelowIt() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("runner"))
				.withLimit(10)
				.withRescore(
					Rescore.of(10, Query.field("brand", Matchers.equalTo("aurora")))
						.withWeight(DECIDING)
				)
				.build()
		);

		assertThat(result.hits().size(), is(5));
		assertThat(result.windowEnd(), is(nullValue()));
	}

	@Test
	public void testABoostWeighsAgainstAnother() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("runner"))
				.withLimit(3)
				.withRescore(
					Rescore.of(
						3,
						BoostQuery.of(1f, Query.field("brand", Matchers.equalTo("borealis"))),
						BoostQuery.of(5f, Query.field("brand", Matchers.equalTo("aurora")))
					).withWeight(DECIDING)
				)
				.build()
		);

		/*
		 * Both brands sit in the window and both are lifted, so the heavier of
		 * the two clauses decides between them. `second` was reached by neither.
		 */
		assertThat(ids(result), contains("third", "first", "second"));
	}

	@Test
	public void testAPageReachingPastTheWindowIsRefused() {
		assertThrows(
			IllegalArgumentException.class,
			() -> SearchRequest.create()
				.withLimit(10)
				.withOffset(20)
				.withRescore(Rescore.of(3, Query.field("brand", Matchers.equalTo("aurora"))))
				.build()
		);
	}

	@Test
	public void testHitsThatAreValuesCanNotRescore() {
		assertThrows(
			IllegalArgumentException.class,
			() -> SearchRequest.create()
				.withHits("variants")
				.withRescore(Rescore.of(10, Query.field("brand", Matchers.equalTo("aurora"))))
				.build()
		);
	}

	@Test
	public void testASecondPassWithNothingToReorderByIsRefused() {
		assertThrows(IllegalArgumentException.class, () -> Rescore.of(10));
	}

	@Test
	public void testABoostOnAnUnknownFieldIsRefused() throws IOException {
		var index = products();

		assertThrows(
			IndexFieldNotFoundException.class,
			() -> index.search(
				SearchRequest.create()
					.withQuery(Query.text("runner"))
					.withRescore(Rescore.of(10, Query.field("missing", Matchers.equalTo("x"))))
					.build()
			)
		);
	}

	@Test
	public void testASignalInTheWindowOnAFieldWithoutSortIsRefused() throws IOException {
		var index = products();

		assertThrows(
			IndexFieldUsageException.class,
			() -> index.search(
				SearchRequest.create()
					.withQuery(Query.text("runner"))
					.withRescore(Rescore.ofSignals(10, RankingSignal.saturation("views", 10)))
					.build()
			)
		);
	}

	/**
	 * The primary keys of the hits, in the order they came back.
	 */
	private static List<Object> ids(SearchResult result) {
		return result.hits().collect(SearchResult.Hit::id).toList();
	}

	/**
	 * Five products matching one search equally well, ranked by what sells so
	 * that the order of the first pass is known before a second one runs.
	 */
	private Index products() throws IOException {
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
					"brand",
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
				.setRanking(
					RankingConfig.newBuilder()
						.addSignals(
							RankingConfig.Signal.newBuilder()
								.setField("purchases")
								.setSaturation(
									RankingConfig.Signal.Saturation.newBuilder().setPivot(50)
								)
						)
				)
		);

		var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

		/*
		 * The same text in the same field throughout, so what sells is the whole
		 * of the ranking. The ages run the other way, so a signal in the window
		 * reorders rather than agrees.
		 */
		add(index, "first", "borealis", 1000, now.minus(Duration.ofDays(365)));
		add(index, "second", "cobalt", 400, now.minus(Duration.ofDays(30)));
		add(index, "third", "aurora", 200, now);
		add(index, "fourth", "zenith", 60, now.minus(Duration.ofDays(400)));
		add(index, "fifth", "nadir", 10, now.minus(Duration.ofDays(500)));

		index.commit();
		return index;
	}

	private static void add(
		Index index,
		String id,
		String brand,
		int purchases,
		Instant published
	) throws IOException {
		index.addDocument(
			new Document(
				new Document.Value("id", id),
				new Document.Value("name", "Trail runner"),
				new Document.Value("brand", brand),
				new Document.Value("purchases", purchases),
				new Document.Value("published", published.toString())
			)
		);
	}
}
