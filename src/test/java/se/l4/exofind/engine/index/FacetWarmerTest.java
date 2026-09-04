package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.lucene.index.IndexReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.DoubleFieldTypeDef;
import se.l4.exofind.engine.index.schema.FacetConfig;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.ObjectFieldTypeDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.metrics.Meters;
import se.l4.exofind.engine.metrics.RequestMetrics;
import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Tests for preparing the facet state of a reader after it is reopened - that
 * a warm leaves every faceted field ready for the first search, that a search
 * arriving while an ordinal map is being built waits for that build rather
 * than making its own, and that a warmer with nothing to warm does no harm.
 */
public class FacetWarmerTest extends AbstractIndexTest {
	private FacetWarmer warmer;
	private SimpleMeterRegistry registry;

	@AfterEach
	void closeWarmer() {
		if(warmer != null) {
			warmer.close();
		}
	}

	private FacetWarmer open(int threads) {
		registry = new SimpleMeterRegistry();
		warmer = new FacetWarmer(new RequestMetrics(registry, false), threads);
		return warmer;
	}

	@Test
	public void testAWarmPreparesEveryFacetedFieldOfTheReader() throws Exception {
		var index = products(open(1));

		assertThat(warmer.awaitIdle(Duration.ofSeconds(30)), is(true));
		assertThat(warms(Meters.OUTCOME_SUCCESS) >= 1, is(true));

		try(var handle = index.searcherManager().acquire()) {
			var reader = handle.getSearcher().getIndexReader();

			var category = FieldNames.name("category", null, FieldNames.VALUES);
			assertThat(FacetStates.holdsStringOrds(reader, category), is(true));
			assertThat(
				kept(reader, category, FacetMatches.Mode.DOCUMENTS, int[].class),
				is(true)
			);

			var price = FieldNames.name("price", null, FieldNames.VALUES);
			assertThat(
				kept(reader, price, FacetMatches.Mode.DOCUMENTS, Object.class),
				is(true)
			);

			var place = FieldNames.name("place", null, FieldNames.HIERARCHY);
			assertThat(
				kept(reader, place, FacetMatches.Mode.DOCUMENTS, long[].class),
				is(true)
			);

			var color = FieldNames.name("variants.color", null, FieldNames.VALUES);
			assertThat(FacetStates.holdsStringOrds(reader, color), is(true));
			assertThat(
				kept(reader, color, FacetMatches.Mode.EVERY_VALUE, int[].class),
				is(true)
			);
		}
	}

	@Test
	public void testTheFirstSearchAfterAWarmAnswersTheSameCounts() throws Exception {
		var index = products(open(1));
		assertThat(warmer.awaitIdle(Duration.ofSeconds(30)), is(true));

		var result = index.search(
			SearchRequest.create()
				.addFacet(Facet.of("category"))
				.addFacet(Facet.of("price"))
				.addFacet(Facet.of("place"))
				.addFacet(Facet.of("variants.color"))
				.build()
		);

		assertThat(result.total().count(), is(3L));
		assertThat(
			result.facets().get("category").values(),
			containsInAnyOrder(
				new SearchResult.Facet.Value("shoes", 2),
				new SearchResult.Facet.Value("clothes", 1)
			)
		);
		assertThat(
			result.facets().get("variants.color").values(),
			containsInAnyOrder(
				new SearchResult.Facet.Value("red", 3),
				new SearchResult.Facet.Value("blue", 1)
			)
		);
	}

	@Test
	public void testACommitQueuesTheIndexAgain() throws Exception {
		var index = products(open(1));
		assertThat(warmer.awaitIdle(Duration.ofSeconds(30)), is(true));
		var before = warms(Meters.OUTCOME_SUCCESS) + warms(Meters.OUTCOME_SUPERSEDED);

		index.addDocument(
			new Document(
				new Document.Value("id", "4"),
				new Document.Value("category", "shoes"),
				new Document.Value("price", 55.0),
				new Document.Value("place", "Store > Floor 1"),
				new Document.Value("variants", variant("green"))
			)
		);
		index.commit();

		assertThat(warmer.awaitIdle(Duration.ofSeconds(30)), is(true));
		assertThat(
			warms(Meters.OUTCOME_SUCCESS) + warms(Meters.OUTCOME_SUPERSEDED) > before,
			is(true)
		);

		try(var handle = index.searcherManager().acquire()) {
			var reader = handle.getSearcher().getIndexReader();
			var category = FieldNames.name("category", null, FieldNames.VALUES);
			assertThat(FacetStates.holdsStringOrds(reader, category), is(true));
		}
	}

	@Test
	public void testSearchesAskingAtOnceShareOneBuild() throws Exception {
		var index = products(FacetWarmer.none());

		var field = FieldNames.name("category", null, FieldNames.VALUES);
		var threads = 8;
		var pool = Executors.newFixedThreadPool(threads);
		try(var handle = index.searcherManager().acquire()) {
			var reader = handle.getSearcher().getIndexReader();
			assertThat(FacetStates.holdsStringOrds(reader, field), is(false));

			var ready = new CountDownLatch(threads);
			var go = new CountDownLatch(1);
			var asking = new ArrayList<Future<FacetStates.StringOrds>>();
			for(var i = 0; i < threads; i++) {
				asking.add(pool.submit((Callable<FacetStates.StringOrds>) () -> {
					ready.countDown();
					go.await(10, TimeUnit.SECONDS);
					return FacetStates.stringOrdsOf(reader, field);
				}));
			}

			ready.await(10, TimeUnit.SECONDS);
			go.countDown();

			var first = asking.get(0).get(10, TimeUnit.SECONDS);
			assertThat(first, is(notNullValue()));
			for(var future : asking) {
				assertThat(future.get(10, TimeUnit.SECONDS), is(sameInstance(first)));
			}

			assertThat(FacetStates.holdsStringOrds(reader, field), is(true));
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	public void testAClosedIndexIsGivenUp() throws Exception {
		open(1);
		var index = products(FacetWarmer.none());
		index.close();

		warmer.warm(index);
		assertThat(warmer.awaitIdle(Duration.ofSeconds(30)), is(true));

		assertThat(warms(Meters.OUTCOME_SUPERSEDED), is(1L));
		assertThat(warms(Meters.OUTCOME_ERROR), is(0L));
	}

	@Test
	public void testNoThreadsWarmsNothing() throws Exception {
		var none = FacetWarmer.none();
		var index = products(none);

		assertThat(none.threads(), is(0));
		assertThat(none.queued(), is(0));
		assertThat(none.awaitIdle(Duration.ofSeconds(1)), is(true));

		try(var handle = index.searcherManager().acquire()) {
			var reader = handle.getSearcher().getIndexReader();
			var category = FieldNames.name("category", null, FieldNames.VALUES);
			assertThat(FacetStates.holdsStringOrds(reader, category), is(false));
		}
	}

	/**
	 * How many warms ended the given way.
	 */
	private long warms(String outcome) {
		var timer = registry.find(Meters.FACET_WARM)
			.tag(Meters.TAG_OUTCOME, outcome)
			.timer();
		return timer == null ? 0 : timer.count();
	}

	/**
	 * Whether every segment of the reader holds what it counted for the field
	 * over everything the reader holds.
	 */
	private static boolean kept(
		IndexReader reader,
		String field,
		FacetMatches.Mode mode,
		Class<?> type
	) {
		if(reader.leaves().isEmpty()) {
			return false;
		}

		for(var context : reader.leaves()) {
			if(FacetStates.segmentCountsOf(context, field, mode, null, type) == null) {
				return false;
			}
		}

		return true;
	}

	/**
	 * A shop with a faceted string, a faceted number, a faceted tree and a
	 * faceted field inside an object, committed once so that the warmer has
	 * one reader to prepare.
	 */
	private Index products(FacetWarmer facetWarmer) throws IOException {
		var index = create("products", SearchThreads.inline(), facetWarmer);
		index.updateDefinition(
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields("category", faceted(string()).build())
				.putFields("price", faceted(doubleField()).build())
				.putFields(
					"place",
					faceted(
						string(
							StringFieldTypeDef.newBuilder()
								.setHierarchy(
									StringFieldTypeDef.HierarchyConfig.newBuilder()
										.setSeparator(" > ")
								)
						)
					).build()
				)
				.putFields(
					"variants",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder().setObject(
								ObjectFieldTypeDef.newBuilder()
									.putFields("color", faceted(string()).setRequired(true).build())
									.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
							)
						)
						.setMultiple(true)
						.build()
				)
				.build()
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("category", "shoes"),
				new Document.Value("price", 120.0),
				new Document.Value("place", "Store > Floor 2 > Shoes"),
				new Document.Value("variants", variant("red")),
				new Document.Value("variants", variant("blue"))
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("category", "shoes"),
				new Document.Value("price", 80.0),
				new Document.Value("place", "Store > Floor 2 > Shoes"),
				new Document.Value("variants", variant("red"))
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "3"),
				new Document.Value("category", "clothes"),
				new Document.Value("price", 80.0),
				new Document.Value("place", "Store > Floor 1 > Clothes"),
				new Document.Value("variants", variant("red"))
			)
		);

		index.commit();
		return index;
	}

	private static Document variant(String color) {
		return new Document(new Document.Value("color", color));
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

	private static FieldDef.Builder doubleField() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setDouble(DoubleFieldTypeDef.getDefaultInstance())
			);
	}
}
