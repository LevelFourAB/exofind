package se.l4.exofind.engine.api.v1alpha1.search;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.api.v1alpha1.search.model.Clause;
import se.l4.exofind.engine.api.v1alpha1.search.model.Matcher;
import se.l4.exofind.engine.api.v1alpha1.search.model.SearchRequest;
import se.l4.exofind.engine.api.v1alpha1.search.model.Signal;
import se.l4.exofind.engine.api.v1alpha1.search.model.Sort;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.query.DecaySignal;
import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.FieldQuery;
import se.l4.exofind.engine.query.FieldSort;
import se.l4.exofind.engine.query.NestedQuery;
import se.l4.exofind.engine.query.OrQuery;
import se.l4.exofind.engine.query.SaturationSignal;
import se.l4.exofind.engine.query.ScoreSort;
import se.l4.exofind.engine.query.SortBy;
import se.l4.exofind.engine.query.SortKey;
import se.l4.exofind.engine.query.TextQuery;
import se.l4.exofind.engine.query.matchers.EqualsMatcher;
import se.l4.exofind.engine.query.matchers.RangeMatcher;
import se.l4.exofind.engine.query.matchers.TextMatcher;
import se.l4.exofind.engine.query.matchers.UnderMatcher;

/**
 * Tests for turning a search as received over the API into the request the
 * engine runs - and for a bad request being answered with every problem it
 * has, each pointing into the body.
 */
public class SearchRequestMapperTest {
	private static final int MAX_DEPTH = 10_000;

	private static SearchRequest withQuery(Clause... clauses) {
		return new SearchRequest(
			Arrays.asList(clauses),
			null, null, null, null, null, null, null, null, null, null, null, null, null, null
		);
	}

	private static List<String> pathsOf(ValidationException e) {
		return e.getErrors().collect(m -> m.getLocation().describe()).toList();
	}

	private static List<String> codesOf(ValidationException e) {
		return e.getErrors().collect(m -> m.getCode()).toList();
	}

	@Test
	public void testNoBodyMatchesEverything() {
		var mapped = SearchRequestMapper.toEngine(null, MAX_DEPTH);

		assertThat(mapped.request().query().isEmpty(), is(true));
		assertThat(
			mapped.request().limit(),
			is(se.l4.exofind.engine.query.SearchRequest.DEFAULT_LIMIT)
		);
		assertThat(mapped.request().offset(), is(0));
		assertThat(
			mapped.request().total(),
			is(se.l4.exofind.engine.query.SearchRequest.Total.ESTIMATE)
		);
		assertThat(mapped.pagesMax(), is(nullValue()));
	}

	@Test
	public void testFieldClauseWithShortForms() {
		var mapped = SearchRequestMapper.toEngine(
			withQuery(new Clause.Field("published", new Matcher.Equals(true))),
			MAX_DEPTH
		);

		var query = (FieldQuery) mapped.request().query().get(0);
		assertThat(query.field(), is("published"));
		assertThat(query.matcher(), is(new EqualsMatcher(true)));
	}

	@Test
	public void testRangeIsFoldedIntoBounds() {
		var mapped = SearchRequestMapper.toEngine(
			withQuery(new Clause.Field("price", new Matcher.Range(10, null, null, 20))),
			MAX_DEPTH
		);

		var query = (FieldQuery) mapped.request().query().get(0);
		assertThat(query.matcher(), is(new RangeMatcher(10, true, 20, false)));
	}

	@Test
	public void testTextClauseCarriesOptionsAndFields() {
		var fields = new HashMap<String, Float>();
		fields.put("name", 3f);
		fields.put("description", null);

		var mapped = SearchRequestMapper.toEngine(
			withQuery(
				new Clause.Text(
					"silent spr",
					fields,
					Matcher.Text.Match.ANY,
					Matcher.Text.Prefix.OFF,
					null,
					null,
					null,
					null
				)
			),
			MAX_DEPTH
		);

		var query = (TextQuery) mapped.request().query().get(0);
		assertThat(query.text(), is("silent spr"));
		assertThat(query.matcher().match(), is(TextMatcher.Match.ANY));
		assertThat(query.matcher().prefix(), is(TextMatcher.Prefix.OFF));
		assertThat(query.matcher().typos(), is(TextMatcher.Typos.AUTO));
		assertThat(query.fields().get("name"), is(3f));
		assertThat(query.fields().containsKey("description"), is(true));
		assertThat(query.fields().get("description"), is(nullValue()));
		assertThat(query.combine(), is(TextQuery.Combine.TERM));
	}

	@Test
	public void testTextClauseCarriesPhraseMatch() {
		var mapped = SearchRequestMapper.toEngine(
			withQuery(
				new Clause.Text(
					"apple watch",
					null,
					Matcher.Text.Match.PHRASE,
					null,
					null,
					null,
					null,
					null
				)
			),
			MAX_DEPTH
		);

		var query = (TextQuery) mapped.request().query().get(0);
		assertThat(query.matcher().match(), is(TextMatcher.Match.PHRASE));
	}

	@Test
	public void testTextClauseCarriesSlop() {
		var mapped = SearchRequestMapper.toEngine(
			withQuery(
				new Clause.Text(
					"apple watch",
					null,
					Matcher.Text.Match.PHRASE,
					null,
					null,
					2,
					null,
					null
				)
			),
			MAX_DEPTH
		);

		var query = (TextQuery) mapped.request().query().get(0);
		assertThat(query.matcher().slop(), is(2));
	}

	@Test
	public void testTextClauseCarriesUserMatch() {
		var mapped = SearchRequestMapper.toEngine(
			withQuery(
				new Clause.Text(
					"shoes -leather",
					null,
					Matcher.Text.Match.USER,
					null,
					null,
					null,
					null,
					null
				)
			),
			MAX_DEPTH
		);

		var query = (TextQuery) mapped.request().query().get(0);
		assertThat(query.matcher().match(), is(TextMatcher.Match.USER));
		assertThat(query.matcher().slop(), is(0));
	}

	@Test
	public void testNegativeSlopIsRefused() {
		var e = assertThrows(
			ValidationException.class,
			() -> SearchRequestMapper.toEngine(
				withQuery(
					new Clause.Text(
						"apple watch",
						null,
						Matcher.Text.Match.PHRASE,
						null,
						null,
						-1,
						null,
						null
					)
				),
				MAX_DEPTH
			)
		);

		assertThat(codesOf(e), contains("search:clause:slop_invalid"));
		assertThat(pathsOf(e), contains("/query/0/slop"));
	}

	/**
	 * Only a phrase has words that sit apart. A search that asked for
	 * something the engine quietly does nothing with looks the same to its
	 * caller as one that worked.
	 */
	@Test
	public void testSlopWithoutAPhraseIsRefused() {
		var e = assertThrows(
			ValidationException.class,
			() -> SearchRequestMapper.toEngine(
				withQuery(
					new Clause.Text(
						"apple watch",
						null,
						Matcher.Text.Match.ALL,
						null,
						null,
						2,
						null,
						null
					)
				),
				MAX_DEPTH
			)
		);

		assertThat(codesOf(e), contains("search:clause:slop_not_applicable"));
		assertThat(pathsOf(e), contains("/query/0/slop"));
	}

	@Test
	public void testTextMatcherCarriesSlopAndUserMatch() {
		var mapped = SearchRequestMapper.toEngine(
			withQuery(
				new Clause.Field(
					"name",
					new Matcher.Text(
					"shoes -leather",
					Matcher.Text.Match.USER,
					null,
					null,
					2,
					null
				)
				)
			),
			MAX_DEPTH
		);

		var query = (FieldQuery) mapped.request().query().get(0);
		var matcher = (TextMatcher) query.matcher();
		assertThat(matcher.match(), is(TextMatcher.Match.USER));
		assertThat(matcher.slop(), is(2));
	}

	@Test
	public void testTextClauseCarriesCombine() {
		var mapped = SearchRequestMapper.toEngine(
			withQuery(
				new Clause.Text(
					"silent spring",
					null,
					null,
					null,
					null,
					null,
					null,
					Clause.Text.Combine.FIELD
				)
			),
			MAX_DEPTH
		);

		var query = (TextQuery) mapped.request().query().get(0);
		assertThat(query.combine(), is(TextQuery.Combine.FIELD));
	}

	@Test
	public void testNestedClauseIsMapped() {
		var mapped = SearchRequestMapper.toEngine(
			withQuery(
				new Clause.Nested(
					"variants",
					List.of(new Clause.Field("variants.color", new Matcher.Equals("red"))),
					null
				)
			),
			MAX_DEPTH
		);

		var query = (NestedQuery) mapped.request().query().get(0);
		assertThat(query.path(), is("variants"));
		assertThat(query.score(), is(NestedQuery.Score.MAX));

		var inner = (FieldQuery) query.clauses().get(0);
		assertThat(inner.field(), is("variants.color"));
		assertThat(inner.matcher(), is(new EqualsMatcher("red")));
	}

	@Test
	public void testNestedClauseNeedsAPath() {
		var e = assertThrows(
			ValidationException.class,
			() -> SearchRequestMapper.toEngine(
				withQuery(
					new Clause.Nested(
						null,
						List.of(new Clause.Field("a", new Matcher.Equals("x"))),
						null
					)
				),
				MAX_DEPTH
			)
		);

		assertThat(codesOf(e), contains("search:clause:path_required"));
		assertThat(pathsOf(e), contains("/query/0/path"));
	}

	@Test
	public void testSortShortFormsAndDefaults() {
		var mapped = SearchRequestMapper.toEngine(
			new SearchRequest(
				null, null, null,
				List.of(new Sort.Score(null), new Sort.Field("name", Sort.Order.DESC)),
				null, null, null, null, null, null, null, null, null, null, null
			),
			MAX_DEPTH
		);

		assertThat(
			mapped.request().sort(),
			contains(
				new ScoreSort(SortBy.Order.DESCENDING),
				new FieldSort("name", SortBy.Order.DESCENDING)
			)
		);
	}

	private static SearchRequest withSignals(List<Signal> signals) {
		return new SearchRequest(
			null, null, null, null, null, null, null, null, null, null, null, null, null, null,
			signals
		);
	}

	@Test
	public void testSignalsAreLeftToTheIndexWhenNoneAreGiven() {
		var mapped = SearchRequestMapper.toEngine(withSignals(null), MAX_DEPTH);

		// Absent, rather than empty, is what leaves the index's ranking in place
		assertThat(mapped.request().signals(), is(nullValue()));
	}

	@Test
	public void testAnEmptySignalListRanksByNothing() {
		var mapped = SearchRequestMapper.toEngine(withSignals(List.of()), MAX_DEPTH);

		assertThat(mapped.request().signals().isEmpty(), is(true));
	}

	@Test
	public void testSignalShapesAreMapped() {
		var mapped = SearchRequestMapper.toEngine(
			withSignals(
				List.of(
					new Signal("purchases", new Signal.Saturation(50.0), null, 0.5f),
					new Signal("published", null, new Signal.Decay(604800L), null)
				)
			),
			MAX_DEPTH
		);

		assertThat(
			mapped.request().signals(),
			contains(
				new SaturationSignal("purchases", 50.0, 0.5f),
				new DecaySignal("published", Duration.ofDays(7), 1f)
			)
		);
	}

	@Test
	public void testInvalidSignalsAreReported() {
		var e = assertThrows(
			ValidationException.class,
			() -> SearchRequestMapper.toEngine(
				withSignals(
					Arrays.asList(
						new Signal(null, new Signal.Saturation(50.0), null, null),
						new Signal("purchases", null, null, null),
						new Signal(
							"purchases",
							new Signal.Saturation(50.0),
							new Signal.Decay(1L),
							null
						),
						new Signal("purchases", new Signal.Saturation(0.0), null, null),
						new Signal("published", null, new Signal.Decay(0L), null),
						new Signal("purchases", new Signal.Saturation(50.0), null, -1f)
					)
				),
				MAX_DEPTH
			)
		);

		assertThat(
			codesOf(e),
			contains(
				"search:signal:field_required",
				"search:signal:shape_invalid",
				"search:signal:shape_invalid",
				"search:signal:pivot_invalid",
				"search:signal:half_life_invalid",
				"search:signal:weight_invalid"
			)
		);
		assertThat(
			pathsOf(e),
			contains(
				"/signals/0/field",
				"/signals/1",
				"/signals/2",
				"/signals/3/saturation/pivot",
				"/signals/4/decay/halfLife",
				"/signals/5/weight"
			)
		);
	}

	@Test
	public void testPagesImplyExactTotal() {
		var mapped = SearchRequestMapper.toEngine(
			new SearchRequest(
				null, null, null, null, null, null, null, null, 20, null, null, null,
				new SearchRequest.Pages(null),
				null, null
			),
			MAX_DEPTH
		);

		assertThat(mapped.pagesMax(), is(SearchRequestMapper.DEFAULT_PAGES_MAX));
		assertThat(
			mapped.request().total(),
			is(se.l4.exofind.engine.query.SearchRequest.Total.EXACT)
		);
	}

	private static int fingerprintOfDefaultSort() {
		return SearchCursor.fingerprintOf(
			org.eclipse.collections.api.factory.Lists.immutable.empty()
		);
	}

	private static SortKey key(Object... values) {
		return new SortKey(
			org.eclipse.collections.api.factory.Lists.immutable.of(values),
			17
		);
	}

	@Test
	public void testOffsetCursorBecomesOffset() {
		var token = new SearchCursor.Offset(fingerprintOfDefaultSort(), 40).encode();

		var mapped = SearchRequestMapper.toEngine(
			new SearchRequest(
				null, null, null, null, null, null, null, null, 20, null, token, null, null, null, null
			),
			MAX_DEPTH
		);

		assertThat(mapped.request().offset(), is(40));
		assertThat(mapped.request().after(), is(nullValue()));
	}

	@Test
	public void testKeysetAfterCursorBecomesAfter() {
		var token = new SearchCursor.Keyset(fingerprintOfDefaultSort(), key(1.5f)).encode();

		var mapped = SearchRequestMapper.toEngine(
			new SearchRequest(
				null, null, null, null, null, null, null, null, 20, null, token, null, null, null, null
			),
			MAX_DEPTH
		);

		assertThat(mapped.request().offset(), is(0));
		assertThat(mapped.request().after(), is(key(1.5f)));
		assertThat(mapped.request().before(), is(nullValue()));
	}

	@Test
	public void testKeysetBeforeCursorBecomesBefore() {
		var token = new SearchCursor.Keyset(fingerprintOfDefaultSort(), key(1.5f)).encode();

		var mapped = SearchRequestMapper.toEngine(
			new SearchRequest(
				null, null, null, null, null, null, null, null, 20, null, null, token, null, null, null
			),
			MAX_DEPTH
		);

		assertThat(mapped.request().offset(), is(0));
		assertThat(mapped.request().after(), is(nullValue()));
		assertThat(mapped.request().before(), is(key(1.5f)));
	}

	@Test
	public void testKeysetCursorUnderDifferentSortIsRefused() {
		var token = new SearchCursor.Keyset(fingerprintOfDefaultSort(), key(1.5f)).encode();

		var e = assertThrows(
			ValidationException.class,
			() -> SearchRequestMapper.toEngine(
				new SearchRequest(
					null, null, null,
					List.of(new Sort.Field("name", null)),
					null, null, null, null, null, null, token, null, null, null, null
				),
				MAX_DEPTH
			)
		);

		assertThat(codesOf(e), contains("search:cursor:sort_mismatch"));
		assertThat(pathsOf(e), contains("/after"));
	}

	@Test
	public void testOffsetCursorKeepsWorkingUnderAnotherSort() {
		/*
		 * An offset counts the same whatever the order, so a position is
		 * deliberately kept when only the sort changes - unlike a keyset
		 * cursor, whose values mean nothing there.
		 */
		var token = new SearchCursor.Offset(fingerprintOfDefaultSort(), 40).encode();

		var mapped = SearchRequestMapper.toEngine(
			new SearchRequest(
				null, null, null,
				List.of(new Sort.Field("name", null)),
				null, null, null, null, null, null, token, null, null, null, null
			),
			MAX_DEPTH
		);

		assertThat(mapped.request().offset(), is(40));
	}

	@Test
	public void testKeysetCursorIsNotCappedByPageDepth() {
		// Continuing past a hit skips nothing, which is what the cap is on
		var token = new SearchCursor.Keyset(fingerprintOfDefaultSort(), key(1.5f)).encode();

		var mapped = SearchRequestMapper.toEngine(
			new SearchRequest(
				null, null, null, null, null, null, null, null, 10, null, token, null, null, null, null
			),
			10
		);

		assertThat(mapped.request().after(), is(key(1.5f)));
	}

	@Test
	public void testPagesFromAKeysetCursorAreRefused() {
		var token = new SearchCursor.Keyset(fingerprintOfDefaultSort(), key(1.5f)).encode();

		var e = assertThrows(
			ValidationException.class,
			() -> SearchRequestMapper.toEngine(
				new SearchRequest(
					null, null, null, null, null, null, null, null, 10, null, token, null,
					new SearchRequest.Pages(null),
					null, null
				),
				MAX_DEPTH
			)
		);

		assertThat(codesOf(e), contains("search:pages:without_offset"));
		assertThat(pathsOf(e), contains("/pages"));
	}

	@Test
	public void testGarbageCursorIsRefused() {
		var e = assertThrows(
			ValidationException.class,
			() -> SearchRequestMapper.toEngine(
				new SearchRequest(
					null, null, null, null, null, null, null, null, null, null, null,
					"??not-a-cursor??", null, null, null
				),
				MAX_DEPTH
			)
		);

		assertThat(codesOf(e), contains("search:cursor:invalid"));
		assertThat(pathsOf(e), contains("/before"));
	}

	@Test
	public void testOffsetTogetherWithCursorIsRefused() {
		var e = assertThrows(
			ValidationException.class,
			() -> SearchRequestMapper.toEngine(
				new SearchRequest(
				null, null, null, null, null, null, null, null, null, 20, "token", null, null, null, null
			),
				MAX_DEPTH
			)
		);

		assertThat(codesOf(e), contains("search:page:conflicting"));
	}

	@Test
	public void testPagingPastTheCapIsRefused() {
		var e = assertThrows(
			ValidationException.class,
			() -> SearchRequestMapper.toEngine(
				new SearchRequest(
				null, null, null, null, null, null, null, null, 10, 95, null, null, null, null, null
			),
				100
			)
		);

		assertThat(codesOf(e), contains("search:page:too_deep"));
		assertThat(e.getErrors().get(0).getArguments().get("max"), is(100));
	}

	@Test
	public void testEveryProblemIsCollectedWithItsPath() {
		var e = assertThrows(
			ValidationException.class,
			() -> SearchRequestMapper.toEngine(
				withQuery(
					new Clause.Field("category", new Matcher.Equals(null)),
					new Clause.Or(
						List.of(new Clause.Field(null, new Matcher.Equals("x")))
					),
					new Clause.Field("price", new Matcher.Range(null, null, null, null)),
					new Clause.Field("price", new Matcher.Range(10, 10, null, null))
				),
				MAX_DEPTH
			)
		);

		assertThat(
			pathsOf(e),
			containsInAnyOrder(
				"/query/0/match/value",
				"/query/1/clauses/0/field",
				"/query/2/match",
				"/query/3/match"
			)
		);
		assertThat(
			codesOf(e),
			containsInAnyOrder(
				"search:matcher:value_required",
				"search:clause:field_required",
				"search:matcher:range_empty",
				"search:matcher:range_conflicting"
			)
		);
	}

	@Test
	public void testKnnClauseIsValidated() {
		var e = assertThrows(
			ValidationException.class,
			() -> SearchRequestMapper.toEngine(
				withQuery(new Clause.Knn("embedding", null, 0, null)),
				MAX_DEPTH
			)
		);

		assertThat(
			codesOf(e),
			containsInAnyOrder("search:clause:vector_required", "search:clause:k_invalid")
		);
	}

	@Test
	public void testBoostNeedsAWeight() {
		var e = assertThrows(
			ValidationException.class,
			() -> SearchRequestMapper.toEngine(
				withQuery(
					new Clause.Boost(
						null,
						List.of(new Clause.Field("staffPick", new Matcher.Equals(true)))
					)
				),
				MAX_DEPTH
			)
		);

		assertThat(codesOf(e), contains("search:clause:weight_invalid"));
		assertThat(pathsOf(e), contains("/query/0/weight"));
	}

	@Test
	public void testValidClausesNextToABadOneAreStillMapped() {
		var e = assertThrows(
			ValidationException.class,
			() -> SearchRequestMapper.toEngine(
				withQuery(
					new Clause.Field("category", new Matcher.Equals("fiction")),
					new Clause.Field("price", new Matcher.Range(null, null, null, null))
				),
				MAX_DEPTH
			)
		);

		// Only the bad clause is reported, not knock-on problems from the good one
		assertThat(codesOf(e), contains("search:matcher:range_empty"));
	}

	@Test
	public void testUnsupportedLocaleIsRefused() {
		var e = assertThrows(
			ValidationException.class,
			() -> SearchRequestMapper.toEngine(
				new SearchRequest(
				null, null, null, null, "xx", null, null, null, null, null, null, null, null, null, null
			),
				MAX_DEPTH
			)
		);

		assertThat(codesOf(e), contains("search:locale:unsupported"));
		assertThat(pathsOf(e), contains("/locale"));
	}

	@Test
	public void testSupportedLocalePassesThrough() {
		var mapped = SearchRequestMapper.toEngine(
			new SearchRequest(
				null, null, null, null, "sv", null, null, null, null, null, null, null, null, null, null
			),
			MAX_DEPTH
		);

		assertThat(mapped.request().locale(), is("sv"));
	}

	@Test
	public void testEmptyOrIsAllowed() {
		// An empty or matches nothing, which the engine has an opinion on - not the API
		var mapped = SearchRequestMapper.toEngine(
			withQuery(new Clause.Or(List.of())),
			MAX_DEPTH
		);

		var query = (OrQuery) mapped.request().query().get(0);
		assertThat(query.clauses().castToList(), is(empty()));
	}

	@Test
	public void testFiltersBecomeFieldQueries() {
		var mapped = SearchRequestMapper.toEngine(
			new SearchRequest(
				null,
				List.of(new SearchRequest.Filter("category", new Matcher.Equals("fiction"))),
				null, null, null, null, null, null, null, null, null, null, null, null, null
			),
			MAX_DEPTH
		);

		assertThat(
			mapped.request().filters().castToList(),
			contains(new FieldQuery("category", new EqualsMatcher("fiction")))
		);
		assertThat(mapped.request().query().isEmpty(), is(true));
	}

	@Test
	public void testFacetFillsInTheDefaults() {
		var mapped = SearchRequestMapper.toEngine(
			new SearchRequest(
				null, null,
				List.of(new SearchRequest.Facet(null, "category", null, null, null, null, null)),
				null, null, null, null, null, null, null, null, null, null, null, null
			),
			MAX_DEPTH
		);

		var facet = mapped.request().facets().get(0);
		assertThat(facet.name(), is("category"));
		assertThat(facet.limit(), is(Facet.DEFAULT_LIMIT));
		assertThat(facet.order(), is(Facet.Order.COUNT));
	}

	@Test
	public void testFacetCarriesWhatWasAsked() {
		var mapped = SearchRequestMapper.toEngine(
			new SearchRequest(
				null, null,
				List.of(
					new SearchRequest.Facet(
						"alpha", "category", 5, SearchRequest.Facet.Order.VALUE, null, null, null
					)
				),
				null, null, null, null, null, null, null, null, null, null, null, null
			),
			MAX_DEPTH
		);

		assertThat(
			mapped.request().facets().get(0),
			is(new Facet("alpha", "category", 5, Facet.Order.VALUE))
		);
	}

	@Test
	public void testFacetCountsOneLevelFromTheTopWhenNothingIsAsked() {
		var mapped = SearchRequestMapper.toEngine(
			new SearchRequest(
				null, null,
				List.of(new SearchRequest.Facet(null, "category", null, null, null, null, null)),
				null, null, null, null, null, null, null, null, null, null, null, null
			),
			MAX_DEPTH
		);

		var facet = mapped.request().facets().get(0);
		assertThat(facet.path(), is(nullValue()));
		assertThat(facet.depth(), is(Facet.DEFAULT_DEPTH));
	}

	@Test
	public void testFacetCarriesThePathAndDepthOfATree() {
		var mapped = SearchRequestMapper.toEngine(
			new SearchRequest(
				null, null,
				List.of(
					new SearchRequest.Facet(
						null, "category", null, null, null, "Men/Shoes", 3
					)
				),
				null, null, null, null, null, null, null, null, null, null, null, null
			),
			MAX_DEPTH
		);

		var facet = mapped.request().facets().get(0);
		assertThat(facet.path(), is("Men/Shoes"));
		assertThat(facet.depth(), is(3));
	}

	@Test
	public void testFacetTreeProblemsAreCollectedWithTheirPaths() {
		var e = assertThrows(
			ValidationException.class,
			() -> SearchRequestMapper.toEngine(
				new SearchRequest(
					null, null,
					List.of(
						new SearchRequest.Facet(null, "category", null, null, null, " ", null),
						new SearchRequest.Facet(
							"deep", "category", null, null, null, null, Facet.MAX_DEPTH + 1
						),
						new SearchRequest.Facet(
							"bucketed", "price", null, null,
							List.of(new SearchRequest.Facet.Range(0, 10)), "Men", null
						)
					),
					null, null, null, null, null, null, null, null, null, null, null, null
				),
				MAX_DEPTH
			)
		);

		assertThat(
			codesOf(e),
			contains(
				"search:facet:path_invalid",
				"search:facet:depth_invalid",
				"search:facet:ranges_on_a_tree"
			)
		);
		assertThat(
			pathsOf(e),
			contains(
				"/facets/0/path",
				"/facets/1/depth",
				"/facets/2"
			)
		);
	}

	@Test
	public void testUnderMatcherCarriesAcross() {
		var mapped = SearchRequestMapper.toEngine(
			withQuery(new Clause.Field("category", new Matcher.Under("Men/Shoes"))),
			MAX_DEPTH
		);

		assertThat(
			mapped.request().query().get(0),
			is(new FieldQuery("category", new UnderMatcher("Men/Shoes")))
		);
	}

	@Test
	public void testUnderMatcherNeedsAPath() {
		var e = assertThrows(
			ValidationException.class,
			() -> SearchRequestMapper.toEngine(
				withQuery(new Clause.Field("category", new Matcher.Under(null))),
				MAX_DEPTH
			)
		);

		assertThat(pathsOf(e), contains("/query/0/match/path"));
	}

	@Test
	public void testFacetRangesCarryAcross() {
		var mapped = SearchRequestMapper.toEngine(
			new SearchRequest(
				null, null,
				List.of(
					new SearchRequest.Facet(
						null, "price", null, null,
						List.of(
							new SearchRequest.Facet.Range(null, 100.0),
							new SearchRequest.Facet.Range(100.0, null)
						), null, null
					)
				),
				null, null, null, null, null, null, null, null, null, null, null, null
			),
			MAX_DEPTH
		);

		assertThat(
			mapped.request().facets().get(0).ranges(),
			is(Lists.immutable.of(
				new Facet.Range(null, 100.0),
				new Facet.Range(100.0, null)
			))
		);
	}

	@Test
	public void testFacetRangeProblemsAreCollectedWithTheirPaths() {
		var e = assertThrows(
			ValidationException.class,
			() -> SearchRequestMapper.toEngine(
				new SearchRequest(
					null, null,
					List.of(
						new SearchRequest.Facet(null, "price", null, null, List.of(), null, null),
						new SearchRequest.Facet(
							"limited", "price", 5, null,
							List.of(new SearchRequest.Facet.Range(0, 10)), null, null
						),
						new SearchRequest.Facet(
							"ordered", "price", null, SearchRequest.Facet.Order.VALUE,
							List.of(new SearchRequest.Facet.Range(0, 10)), null, null
						),
						new SearchRequest.Facet(
							"unbounded", "price", null, null,
							List.of(new SearchRequest.Facet.Range(null, null)), null, null
						)
					),
					null, null, null, null, null, null, null, null, null, null, null, null
				),
				MAX_DEPTH
			)
		);

		assertThat(
			codesOf(e),
			contains(
				"search:facet:ranges_required",
				"search:facet:ranges_conflicting",
				"search:facet:ranges_conflicting",
				"search:facet:range_empty"
			)
		);
		assertThat(
			pathsOf(e),
			contains(
				"/facets/0/ranges",
				"/facets/1",
				"/facets/2",
				"/facets/3/ranges/0"
			)
		);
	}

	@Test
	public void testFilterAndFacetProblemsAreCollectedWithTheirPaths() {
		var e = assertThrows(
			ValidationException.class,
			() -> SearchRequestMapper.toEngine(
				new SearchRequest(
					null,
					List.of(new SearchRequest.Filter(null, null)),
					List.of(
						new SearchRequest.Facet(null, null, null, null, null, null, null),
						new SearchRequest.Facet(" ", "category", 0, null, null, null, null),
						new SearchRequest.Facet(null, "published", null, null, null, null, null),
						new SearchRequest.Facet("published", "tags", null, null, null, null, null)
					),
					null, null, null, null, null, null, null, null, null, null, null, null
				),
				MAX_DEPTH
			)
		);

		assertThat(
			codesOf(e),
			contains(
				"search:clause:field_required",
				"search:clause:match_required",
				"search:facet:field_required",
				"search:facet:name_invalid",
				"search:facet:limit_invalid",
				"search:facet:duplicate_name"
			)
		);
		assertThat(
			pathsOf(e),
			contains(
				"/filters/0/field",
				"/filters/0/match",
				"/facets/0/field",
				"/facets/1/name",
				"/facets/1/limit",
				"/facets/3/name"
			)
		);
	}

	private static SearchRequest withHighlight(SearchRequest.Highlight highlight) {
		return new SearchRequest(
			null, null, null, null, null, null, highlight, null, null, null, null, null, null, null, null
		);
	}

	@Test
	public void testHighlightFillsInTheDefaults() {
		var fields = new HashMap<String, SearchRequest.HighlightField>();
		fields.put("name", null);
		fields.put("description", new SearchRequest.HighlightField(1, 40, "[", "]"));

		var mapped = SearchRequestMapper.toEngine(
			withHighlight(new SearchRequest.Highlight(fields)),
			MAX_DEPTH
		);

		var highlight = mapped.request().highlight();
		assertThat(
			highlight.get("name"),
			is(se.l4.exofind.engine.query.SearchRequest.Highlight.defaults())
		);
		assertThat(
			highlight.get("description"),
			is(new se.l4.exofind.engine.query.SearchRequest.Highlight(1, 40, "[", "]"))
		);
	}

	@Test
	public void testHighlightMarkersMayBeEmpty() {
		var fields = new HashMap<String, SearchRequest.HighlightField>();
		fields.put("name", new SearchRequest.HighlightField(null, null, "", ""));

		var mapped = SearchRequestMapper.toEngine(
			withHighlight(new SearchRequest.Highlight(fields)),
			MAX_DEPTH
		);

		assertThat(mapped.request().highlight().get("name").pre(), is(""));
		assertThat(mapped.request().highlight().get("name").post(), is(""));
	}

	@Test
	public void testHighlightWithoutFieldsIsRefused() {
		var e = assertThrows(
			ValidationException.class,
			() -> SearchRequestMapper.toEngine(
				withHighlight(new SearchRequest.Highlight(new HashMap<>())),
				MAX_DEPTH
			)
		);

		assertThat(codesOf(e), contains("search:highlight:fields_required"));
		assertThat(pathsOf(e), contains("/highlight/fields"));
	}

	@Test
	public void testHighlightFragmentCountBelowOneIsRefused() {
		var fields = new HashMap<String, SearchRequest.HighlightField>();
		fields.put("name", new SearchRequest.HighlightField(0, null, null, null));

		var e = assertThrows(
			ValidationException.class,
			() -> SearchRequestMapper.toEngine(
				withHighlight(new SearchRequest.Highlight(fields)),
				MAX_DEPTH
			)
		);

		assertThat(codesOf(e), contains("search:highlight:fragments_invalid"));
		assertThat(pathsOf(e), contains("/highlight/fields/name/fragments"));
	}

	@Test
	public void testHighlightLengthOutOfBoundsIsRefused() {
		var fields = new HashMap<String, SearchRequest.HighlightField>();
		fields.put("name", new SearchRequest.HighlightField(null, 10_001, null, null));

		var e = assertThrows(
			ValidationException.class,
			() -> SearchRequestMapper.toEngine(
				withHighlight(new SearchRequest.Highlight(fields)),
				MAX_DEPTH
			)
		);

		assertThat(codesOf(e), contains("search:highlight:length_invalid"));
		assertThat(pathsOf(e), contains("/highlight/fields/name/length"));
	}

	@Test
	public void testHighlightBlankFieldNameIsRefused() {
		var fields = new HashMap<String, SearchRequest.HighlightField>();
		fields.put(" ", null);

		var e = assertThrows(
			ValidationException.class,
			() -> SearchRequestMapper.toEngine(
				withHighlight(new SearchRequest.Highlight(fields)),
				MAX_DEPTH
			)
		);

		assertThat(codesOf(e), contains("search:highlight:field_required"));
		assertThat(pathsOf(e), contains("/highlight/fields"));
	}

	private static SearchRequest withMatched(SearchRequest.Matched matched) {
		return new SearchRequest(
			null, null, null, null, null, null, null, matched, null, null, null, null, null, null, null
		);
	}

	@Test
	public void testMatchedFillsInTheDefaults() {
		var fields = new HashMap<String, SearchRequest.MatchedField>();
		fields.put("variants", null);
		fields.put("chunks", new SearchRequest.MatchedField(5, null));

		var mapped = SearchRequestMapper.toEngine(
			withMatched(new SearchRequest.Matched(fields)),
			MAX_DEPTH
		);

		var matched = mapped.request().matched();
		assertThat(
			matched.get("variants"),
			is(se.l4.exofind.engine.query.SearchRequest.Matched.defaults())
		);
		assertThat(
			matched.get("chunks"),
			is(new se.l4.exofind.engine.query.SearchRequest.Matched(5))
		);
	}

	@Test
	public void testMatchedWithoutFieldsIsRefused() {
		var e = assertThrows(
			ValidationException.class,
			() -> SearchRequestMapper.toEngine(
				withMatched(new SearchRequest.Matched(new HashMap<>())),
				MAX_DEPTH
			)
		);

		assertThat(codesOf(e), contains("search:matched:field_required"));
		assertThat(pathsOf(e), contains("/matched/fields"));
	}

	@Test
	public void testMatchedBlankFieldNameIsRefused() {
		var fields = new HashMap<String, SearchRequest.MatchedField>();
		fields.put(" ", null);

		var e = assertThrows(
			ValidationException.class,
			() -> SearchRequestMapper.toEngine(
				withMatched(new SearchRequest.Matched(fields)),
				MAX_DEPTH
			)
		);

		assertThat(codesOf(e), contains("search:matched:field_required"));
		assertThat(pathsOf(e), contains("/matched/fields"));
	}

	@Test
	public void testMatchedLimitOutOfBoundsIsRefused() {
		var fields = new HashMap<String, SearchRequest.MatchedField>();
		fields.put("variants", new SearchRequest.MatchedField(0, null));
		fields.put("chunks", new SearchRequest.MatchedField(101, null));

		var e = assertThrows(
			ValidationException.class,
			() -> SearchRequestMapper.toEngine(
				withMatched(new SearchRequest.Matched(fields)),
				MAX_DEPTH
			)
		);

		assertThat(
			codesOf(e),
			contains("search:matched:limit_invalid", "search:matched:limit_invalid")
		);
		assertThat(
			pathsOf(e),
			containsInAnyOrder(
				"/matched/fields/variants/limit",
				"/matched/fields/chunks/limit"
			)
		);
	}

	@Test
	public void testMatchedFieldsAreKept() {
		var fields = new HashMap<String, SearchRequest.MatchedField>();
		fields.put(
			"variants",
			new SearchRequest.MatchedField(null, List.of("variants.color", "variants.price"))
		);

		var mapped = SearchRequestMapper.toEngine(
			withMatched(new SearchRequest.Matched(fields)),
			MAX_DEPTH
		);

		var matched = mapped.request().matched().get("variants");
		assertThat(
			matched.fields(),
			is(Sets.immutable.of("variants.color", "variants.price"))
		);
		assertThat(
			matched.limit(),
			is(se.l4.exofind.engine.query.SearchRequest.Matched.DEFAULT_LIMIT)
		);
	}

	@Test
	public void testMatchedEmptyFieldsIsRefused() {
		var fields = new HashMap<String, SearchRequest.MatchedField>();
		fields.put("variants", new SearchRequest.MatchedField(null, List.of()));

		var e = assertThrows(
			ValidationException.class,
			() -> SearchRequestMapper.toEngine(
				withMatched(new SearchRequest.Matched(fields)),
				MAX_DEPTH
			)
		);

		assertThat(codesOf(e), contains("search:matched:fields_empty"));
		assertThat(pathsOf(e), contains("/matched/fields/variants/fields"));
	}

	@Test
	public void testMatchedFieldOutsideThePathIsRefused() {
		var fields = new HashMap<String, SearchRequest.MatchedField>();
		fields.put(
			"variants",
			new SearchRequest.MatchedField(null, List.of("color", "badges.label"))
		);

		var e = assertThrows(
			ValidationException.class,
			() -> SearchRequestMapper.toEngine(
				withMatched(new SearchRequest.Matched(fields)),
				MAX_DEPTH
			)
		);

		assertThat(
			codesOf(e),
			contains(
				"search:matched:field_not_inside",
				"search:matched:field_not_inside"
			)
		);
		assertThat(
			pathsOf(e),
			contains(
				"/matched/fields/variants/fields/0",
				"/matched/fields/variants/fields/1"
			)
		);
	}
}
