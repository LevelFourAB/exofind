package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;

import org.eclipse.collections.api.factory.Lists;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.FacetConfig;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.SortConfig;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.schema.VectorFieldTypeDef;
import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.FuseQuery;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.SortBy;
import se.l4.exofind.engine.query.matchers.Matchers;

/**
 * Tests for fusing several rankings by rank - what the merged order is, what
 * the clause can reach, and how the rest of a search reads what it found.
 */
public class FuseSearchTest extends AbstractIndexTest {
	/**
	 * Vectors of four documents laid out so that the vector ranking and the
	 * text ranking disagree, which is what a fusion is asked to settle.
	 */
	private static final float[] X = { 1f, 0f, 0f, 0f };
	private static final float[] Y = { 0f, 1f, 0f, 0f };
	private static final float[] Z = { 0f, 0f, 1f, 0f };
	private static final float[] W = { 0f, 0f, 0f, 1f };

	/**
	 * A vector pointing between {@code X} and {@code Y}, nearer to {@code X}.
	 */
	private static final float[] TOWARDS_X = { 0.9f, 0.4f, 0f, 0f };

	@Test
	public void testADocumentBothRankingsFoundOutranksOneOnlyOneFound()
		throws IOException
	{
		var index = books();

		var result = search(
			index,
			Query.fuse(
				FuseQuery.ranking(Query.text("themes")),
				FuseQuery.ranking(Query.knn("embedding", TOWARDS_X, 4))
			)
		);

		/*
		 * `y` is second in the vector ranking and second in the text ranking;
		 * `x` is first in the vector ranking and never matched the text. Two
		 * second places beat one first place, which is the whole point of
		 * fusing by rank.
		 */
		assertThat(ids(result).get(0), is("y"));
	}

	@Test
	public void testARankingIsFoundWithoutTheOthersMatching() throws IOException {
		var index = books();

		var result = search(
			index,
			Query.fuse(
				FuseQuery.ranking(Query.text("themes")),
				FuseQuery.ranking(Query.knn("embedding", W, 1))
			)
		);

		/*
		 * `w` holds none of the text, and would have been left out by the
		 * boolean AND that combining two clauses is. Fusing takes the union,
		 * which is the recall a hybrid search is for.
		 */
		assertThat(ids(result), hasItem("w"));
	}

	@Test
	public void testAWeightDecidesHowMuchARankingCounts() throws IOException {
		var index = books();

		var result = index.search(
			SearchRequest.create()
				.withQuery(
					new FuseQuery(
						Lists.immutable.of(
							FuseQuery.ranking(Query.text("themes")).withWeight(0.01f),
							FuseQuery.ranking(Query.knn("embedding", TOWARDS_X, 4))
						),
						FuseQuery.DEFAULT_DEPTH,
						FuseQuery.DEFAULT_RANK_CONSTANT,
						null
					)
				)
				.build()
		);

		/*
		 * The text ranking counts for almost nothing, so the order is the one
		 * the vector ranking arrived at on its own.
		 */
		assertThat(ids(result).get(0), is("x"));
	}

	@Test
	public void testDepthBoundsWhatTheFusionCanReach() throws IOException {
		var index = books();

		var result = search(
			index,
			Query.fuse(
				FuseQuery.ranking(Query.text("themes")),
				FuseQuery.ranking(Query.knn("embedding", TOWARDS_X, 4))
			).withDepth(1)
		);

		/*
		 * One result per ranking: the nearest vector and the best text match.
		 * Nothing else is a result at all, the way a `knn` clause returns k
		 * documents and no more.
		 */
		assertThat(result.total().count(), is(2L));
		assertThat(ids(result), containsInAnyOrder("x", "y"));
	}

	@Test
	public void testTheFilterNarrowsEveryRanking() throws IOException {
		var index = books();

		var result = search(
			index,
			Query.fuse(
				FuseQuery.ranking(Query.text("themes")),
				FuseQuery.ranking(Query.knn("embedding", TOWARDS_X, 4))
			).withFilter(Query.field("category", Matchers.equalTo("poetry")))
		);

		assertThat(ids(result), not(hasItem("x")));
		assertThat(ids(result), everyItem(is(not("x"))));
	}

	/**
	 * The filter reaches the vector ranking before its neighbours are picked,
	 * so a narrowed ranking still returns as many as it was asked for rather
	 * than the global nearest with the rest filtered away.
	 */
	@Test
	public void testTheFilterReachesAVectorRankingBeforeItsNeighboursArePicked()
		throws IOException
	{
		var index = books();

		var result = search(
			index,
			Query.fuse(
				FuseQuery.ranking(Query.text("nothing here matches")),
				FuseQuery.ranking(Query.knn("embedding", TOWARDS_X, 2))
			).withFilter(Query.field("category", Matchers.equalTo("poetry")))
		);

		/*
		 * The two nearest documents are `x` and `y`, and only `y` is poetry.
		 * Filtering after the fact would have left one result; filtering
		 * before leaves the two nearest of the poetry.
		 */
		assertThat(ids(result), containsInAnyOrder("y", "z"));
	}

	@Test
	public void testAClauseBesideTheFusionNarrowsWhatItFound() throws IOException {
		var index = books();

		var result = search(
			index,
			Query.fuse(
				FuseQuery.ranking(Query.text("themes")),
				FuseQuery.ranking(Query.knn("embedding", TOWARDS_X, 4))
			),
			Query.field("category", Matchers.equalTo("fiction"))
		);

		assertThat(ids(result), contains("x"));
	}

	@Test
	public void testEveryFusedResultScores() throws IOException {
		var index = books();

		var result = search(
			index,
			Query.fuse(
				FuseQuery.ranking(Query.text("themes")),
				FuseQuery.ranking(Query.knn("embedding", TOWARDS_X, 4))
			)
		);

		assertThat(
			result.hits().collect(SearchResult.Hit::score).toList(),
			everyItem(greaterThan(0f))
		);
	}

	@Test
	public void testTheTotalIsWhatTheRankingsFound() throws IOException {
		var index = books();

		var result = search(
			index,
			Query.fuse(
				FuseQuery.ranking(Query.text("themes")),
				FuseQuery.ranking(Query.knn("embedding", TOWARDS_X, 2))
			)
		);

		// `x` and `y` from the vectors, `z` and `y` from the text
		assertThat(result.total().count(), is(3L));
	}

	@Test
	public void testPagingWalksTheFusedOrder() throws IOException {
		var index = books();

		var whole = search(
			index,
			Query.fuse(
				FuseQuery.ranking(Query.text("themes")),
				FuseQuery.ranking(Query.knn("embedding", TOWARDS_X, 4))
			)
		);

		var first = index.search(
			SearchRequest.create()
				.withQuery(
					Query.fuse(
						FuseQuery.ranking(Query.text("themes")),
						FuseQuery.ranking(Query.knn("embedding", TOWARDS_X, 4))
					)
				)
				.withLimit(1)
				.build()
		);

		var second = index.search(
			SearchRequest.create()
				.withQuery(
					Query.fuse(
						FuseQuery.ranking(Query.text("themes")),
						FuseQuery.ranking(Query.knn("embedding", TOWARDS_X, 4))
					)
				)
				.withLimit(1)
				.withAfter(first.hits().getFirst().key())
				.build()
		);

		assertThat(ids(first), contains(ids(whole).get(0)));
		assertThat(ids(second), contains(ids(whole).get(1)));
	}

	@Test
	public void testFacetsCountWhatTheFusionFound() throws IOException {
		var index = books();

		var result = index.search(
			SearchRequest.create()
				.withQuery(
					Query.fuse(
						FuseQuery.ranking(Query.text("themes")),
						FuseQuery.ranking(Query.knn("embedding", TOWARDS_X, 1))
					)
				)
				.withFacets(Facet.of("category"))
				.build()
		);

		/*
		 * Counted over what the rankings found rather than over the index, the
		 * way a `knn` clause has its counts bounded by k.
		 */
		var counts = result.facets().get("category");
		assertThat(
			counts.values().collect(SearchResult.Facet.Value::value).toList(),
			containsInAnyOrder("fiction", "poetry")
		);
	}

	@Test
	public void testASortOfItsOwnStillReadsWhatTheFusionFound() throws IOException {
		var index = books();

		var result = index.search(
			SearchRequest.create()
				.withQuery(
					Query.fuse(
						FuseQuery.ranking(Query.text("themes")),
						FuseQuery.ranking(Query.knn("embedding", TOWARDS_X, 1))
					)
				)
				.withSort(SortBy.field("id"))
				.build()
		);

		// The fused set, ordered by the field instead of by the merge
		assertThat(ids(result), contains("x", "y", "z"));
	}

	@Test
	public void testExplainingSaysWhereEachRankingPlacedTheDocument()
		throws IOException
	{
		var index = books();

		var explanation = index.explain(
			SearchRequest.create()
				.withQuery(
					Query.fuse(
						FuseQuery.ranking(Query.text("themes")),
						FuseQuery.ranking(Query.knn("embedding", TOWARDS_X, 4))
					)
				)
				.build(),
			"y",
			0,
			null
		);

		assertThat(explanation.matched(), is(true));
		assertThat(explanation.score(), greaterThan(0f));
	}

	@Test
	public void testFusingNeedsMoreThanOneRanking() {
		assertThrows(
			IllegalArgumentException.class,
			() -> Query.fuse(FuseQuery.ranking(Query.text("themes")))
		);
	}

	/**
	 * Four documents whose vectors point along the axes, two of which hold the
	 * word the text ranking looks for.
	 */
	private Index books() throws IOException {
		var index = create(definition());

		index.addDocument(book("x", "A book about nothing", "fiction", X));
		index.addDocument(book("y", "Themes of a kind", "poetry", Y));
		index.addDocument(book("z", "Themes and more themes", "poetry", Z));
		index.addDocument(book("w", "Something else entirely", "poetry", W));

		index.commit();
		return index;
	}

	private static Document book(String id, String title, String category, float[] embedding) {
		return new Document(
			new Document.Value("id", id),
			new Document.Value("title", title),
			new Document.Value("category", category),
			new Document.Value("embedding", embedding)
		);
	}

	private static IndexDef.Builder definition() {
		return IndexDef.newBuilder()
			.putFields(
				"id",
				string()
					.setPrimaryKey(true)
					.setSort(SortConfig.getDefaultInstance())
					.build()
			)
			.putFields(
				"title",
				string()
					.setType(
						FieldTypeDef.newBuilder()
							.setString(
								StringFieldTypeDef.newBuilder()
									.setMatching(
										StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
									)
							)
					)
					.build()
			)
			.putFields(
				"category",
				string()
					.setFilter(FilterConfig.getDefaultInstance())
					.setFacet(FacetConfig.getDefaultInstance())
					.build()
			)
			.putFields(
				"embedding",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder()
							.setVector(VectorFieldTypeDef.newBuilder().setDimensions(4))
					)
					.build()
			);
	}

	private static FieldDef.Builder string() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setString(StringFieldTypeDef.getDefaultInstance())
			);
	}

	private static SearchResult search(Index index, Query... query) throws IOException {
		return index.search(SearchRequest.create().withQuery(query).build());
	}

	private static List<Object> ids(SearchResult result) {
		return result.hits().collect(SearchResult.Hit::id).toList();
	}
}
