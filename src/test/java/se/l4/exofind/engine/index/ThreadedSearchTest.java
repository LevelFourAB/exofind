package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.DoubleFieldTypeDef;
import se.l4.exofind.engine.index.schema.FacetConfig;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.Int32FieldTypeDef;
import se.l4.exofind.engine.index.schema.SortConfig;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.metrics.RequestMetrics;
import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.FieldQuery;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.SortBy;
import se.l4.exofind.engine.query.matchers.Matchers;

/**
 * Tests for a search spread over the search threads of a node - that it
 * answers what the same search answers on one thread, over an index of
 * several segments, and that a budget spent on one of the threads stops the
 * search as a whole.
 */
public class ThreadedSearchTest extends AbstractIndexTest {
	private static final String[] CATEGORIES = { "shoes", "clothes", "bags", "hats" };
	private static final String[] TAGS = { "outdoor", "sale", "new", "vegan", "kids" };

	private SearchThreads threads;

	@AfterEach
	void closeThreads() {
		if(threads != null) {
			threads.close();
		}
	}

	/**
	 * A search that asks for a bit of everything: text over a filter, a
	 * facet sideways of that filter, a facet over multiple values, a range
	 * facet and a numeric facet, with hits in an order nothing about the
	 * threads can change.
	 */
	private static SearchRequest everything() {
		return SearchRequest.create()
			.withQuery(Query.text("item").withField("name"))
			.addFilter(new FieldQuery("category", Matchers.equalTo("shoes")))
			.addFacet(Facet.of("category"))
			.addFacet(Facet.of("tags"))
			.addFacet(Facet.of("stock"))
			.addFacet(
				Facet.of("price").withRanges(
					new Facet.Range(null, 50.0),
					new Facet.Range(50.0, 150.0),
					new Facet.Range(150.0, null)
				)
			)
			.withSort(SortBy.field("price"))
			.withLimit(25)
			.build();
	}

	/**
	 * A search that only counts, the way a filtering UI refreshes its counts.
	 */
	private static SearchRequest countsOnly() {
		return SearchRequest.create()
			.addFacet(Facet.of("category"))
			.addFacet(Facet.of("tags"))
			.addFacet(Facet.of("stock"))
			.withLimit(0)
			.build();
	}

	@Test
	public void testThreadsAnswerWhatOneThreadAnswers() throws IOException {
		var alone = fill(create("alone"));
		var spread = fill(create("spread", open(4)));

		for(var request : List.of(everything(), countsOnly())) {
			var expected = alone.search(request);
			var actual = spread.search(request);

			assertThat(actual.total(), is(expected.total()));
			assertThat(actual.facets(), is(expected.facets()));
			assertThat(ids(actual), is(ids(expected)));
		}
	}

	@Test
	public void testCountsAreAnsweredTheSameWayTwice() throws IOException {
		var spread = fill(create("spread", open(4)));

		// The second search is answered from what the first kept
		var first = spread.search(everything());
		var second = spread.search(everything());

		assertThat(second.facets(), is(first.facets()));
		assertThat(second.total(), is(first.total()));
	}

	@Test
	public void testASpentBudgetStopsTheSearchOnEveryThread() throws IOException {
		var alone = fill(create("alone"));
		var spread = fill(create("spread", open(4)));

		/*
		 * A search that collects its matches, which is where the budget is
		 * asked - a search counting everything the index holds reads what the
		 * segments already know and never asks.
		 */
		try(var scope = SearchDeadline.start(Duration.ofNanos(1))) {
			spread.search(everything());

			assertThat(scope.exceeded(), is(true));
		}

		// What the stopped search counted was not kept: the next search counts it whole
		var whole = spread.search(everything());
		var expected = alone.search(everything());
		assertThat(whole.total(), is(expected.total()));
		assertThat(whole.facets(), is(expected.facets()));
	}

	/**
	 * Open a pool that takes every piece however small, so that an index of
	 * sixty documents spreads its searches the way a large one would.
	 */
	private SearchThreads open(int count) {
		threads = new SearchThreads(RequestMetrics.none(), count, 0);
		return threads;
	}

	private static List<Object> ids(SearchResult result) {
		return result.hits().collect(SearchResult.Hit::id).castToList();
	}

	/**
	 * Fill an index with sixty documents over six commits, so that the search
	 * has several segments to spread over.
	 */
	private static Index fill(Index index) throws IOException {
		index.updateDefinition(
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(
								StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
							)
					).build()
				)
				.putFields("category", faceted(string()).build())
				.putFields("tags", faceted(string()).setMultiple(true).build())
				.putFields(
					"price",
					faceted(doubleField()).setSort(SortConfig.getDefaultInstance()).build()
				)
				.putFields("stock", faceted(int32()).build())
				.build()
		);

		for(var i = 0; i < 60; i++) {
			index.addDocument(new Document(
				new Document.Value("id", Integer.toString(i)),
				new Document.Value("name", "Item " + i + (i % 3 == 0 ? " runner" : "")),
				new Document.Value("category", CATEGORIES[i % CATEGORIES.length]),
				new Document.Value("tags", TAGS[i % TAGS.length]),
				new Document.Value("tags", TAGS[(i * 7) % TAGS.length]),
				new Document.Value("price", i * 3.5),
				new Document.Value("stock", i % 7)
			));

			if(i % 10 == 9) {
				index.commit();
			}
		}

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

	private static FieldDef.Builder int32() {
		return FieldDef.newBuilder()
			.setType(FieldTypeDef.newBuilder().setInt32(Int32FieldTypeDef.newBuilder()));
	}

	private static FieldDef.Builder doubleField() {
		return FieldDef.newBuilder()
			.setType(FieldTypeDef.newBuilder().setDouble(DoubleFieldTypeDef.newBuilder()));
	}
}
