package se.l4.exofind.engine.benchmark.engine;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import se.l4.exofind.engine.benchmark.JvmArgs;
import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.matchers.Matchers;

/**
 * What counting the matches per value costs on top of finding them.
 *
 * <p>Every facet walks the matches a second time, so these are read against the
 * same search without facets in {@link FilterBenchmark}. The counted set is
 * everything unless a benchmark says otherwise, which is the widest a facet
 * gets asked to count.
 *
 * <p>{@link #everyFacetNarrowed} is the same page counted over a chosen share
 * of the index, set with {@code -p ratio=}, which is what tells how the cost
 * of counting grows with the matches - and where a way of counting that does
 * not walk them starts to pay. {@link #nestedFacetNarrowed} asks the same
 * question of a facet over a field inside an object, whose matches are the
 * values of that object rolled up into the documents holding them.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = { JvmArgs.VECTOR, JvmArgs.NATIVE_ACCESS })
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
@State(Scope.Thread)
public class FacetBenchmark {
	private SearchRequest oneFacet;
	private SearchRequest oneFacetHundredValues;
	private SearchRequest multiValuedFacet;
	private SearchRequest rangeFacet;
	private SearchRequest hierarchyFacet;
	private SearchRequest hierarchyFacetThreeLevels;
	private SearchRequest hierarchyFacetDrilled;
	private SearchRequest everyFacetDrilled;
	private SearchRequest everyFacet;
	private SearchRequest everyFacetOnText;
	private SearchRequest everyFacetCountsOnly;
	private SearchRequest sidewaysFacet;

	@Setup(Level.Trial)
	public void requests(LoadedIndex state) {
		var roles = state.roles;
		var keyword = state.spec.require(roles.keyword(), "keyword");

		oneFacet = SearchRequest.create().addFacet(Facet.of(keyword)).build();
		oneFacetHundredValues = SearchRequest.create()
			.addFacet(Facet.of(keyword).withLimit(100))
			.build();

		var tags = roles.tags();
		multiValuedFacet = tags == null
			? null
			: SearchRequest.create().addFacet(Facet.of(tags)).build();

		var number = roles.number();
		rangeFacet = number == null
			? null
			: SearchRequest.create()
				.addFacet(
					Facet.of(number)
						.withRanges(
							new Facet.Range(null, 100d),
							new Facet.Range(100d, 250d),
							new Facet.Range(250d, 500d),
							new Facet.Range(500d, null)
						)
				)
				.build();

		var hierarchy = roles.hierarchy();
		hierarchyFacet = hierarchy == null
			? null
			: SearchRequest.create().addFacet(Facet.of(hierarchy)).build();
		hierarchyFacetThreeLevels = hierarchy == null
			? null
			: SearchRequest.create().addFacet(Facet.of(hierarchy).withDepth(3)).build();

		/*
		 * A category page - the search narrowed to the most common subtree and
		 * the facet counting the levels below it. The filter is on the facet's
		 * own field, so the facet counts sideways of it.
		 */
		var hierarchyPath = state.spec.hierarchyPath(0);
		hierarchyFacetDrilled = hierarchy == null
			? null
			: SearchRequest.create()
				.addFacet(Facet.of(hierarchy).withPath(hierarchyPath))
				.addFilter(Query.field(hierarchy, Matchers.under(hierarchyPath)))
				.build();

		/*
		 * What a filtering page actually sends - every facet the corpus can
		 * offer at once, which is where the cost of counting shows.
		 */
		var page = SearchRequest.create().addFacet(Facet.of(keyword));
		if(tags != null) {
			page = page.addFacet(Facet.of(tags));
		}
		if(number != null) {
			page = page.addFacet(Facet.of(number).withRanges(new Facet.Range(null, 250d), new Facet.Range(250d, null)));
		}

		var all = hierarchy == null ? page : page.addFacet(Facet.of(hierarchy));

		everyFacet = all.build();
		everyFacetOnText = roles.text().isEmpty()
			? null
			: all.withQuery(Query.text(state.commonTerm())).build();

		/*
		 * The same filtering page after opening a category - every facet, the
		 * hierarchy counting below the opened level instead of from the top,
		 * and the subtree filter narrowing everything but its own facet.
		 */
		everyFacetDrilled = hierarchy == null
			? null
			: page
				.addFacet(Facet.of(hierarchy).withPath(hierarchyPath))
				.addFilter(Query.field(hierarchy, Matchers.under(hierarchyPath)))
				.build();

		/*
		 * How a filtering page refreshes its counts without fetching hits - the
		 * same facets with nothing to rank or read.
		 */
		everyFacetCountsOnly = all.withLimit(0).build();

		/*
		 * A ticked refinement beside a facet on the same field, which is the
		 * one facet that can not share the matches of the search - it counts
		 * sideways of the filter, so its scope is collected on its own.
		 */
		sidewaysFacet = SearchRequest.create()
			.addFacet(Facet.of(keyword))
			.addFilter(Query.field(keyword, Matchers.equalTo(state.spec.keywordValue(0))))
			.build();
	}

	@Benchmark
	public SearchResult oneFacet(LoadedIndex state) throws IOException {
		return state.index.search(oneFacet);
	}

	@Benchmark
	public SearchResult oneFacetHundredValues(LoadedIndex state) throws IOException {
		return state.index.search(oneFacetHundredValues);
	}

	@Benchmark
	public SearchResult multiValuedFacet(LoadedIndex state) throws IOException {
		return state.index.search(required(state, multiValuedFacet, "multi-valued"));
	}

	@Benchmark
	public SearchResult rangeFacet(LoadedIndex state) throws IOException {
		return state.index.search(required(state, rangeFacet, "number"));
	}

	@Benchmark
	public SearchResult hierarchyFacet(LoadedIndex state) throws IOException {
		return state.index.search(required(state, hierarchyFacet, "hierarchy"));
	}

	@Benchmark
	public SearchResult hierarchyFacetThreeLevels(LoadedIndex state) throws IOException {
		return state.index.search(required(state, hierarchyFacetThreeLevels, "hierarchy"));
	}

	@Benchmark
	public SearchResult hierarchyFacetDrilled(LoadedIndex state) throws IOException {
		return state.index.search(required(state, hierarchyFacetDrilled, "hierarchy"));
	}

	@Benchmark
	public SearchResult everyFacetDrilled(LoadedIndex state) throws IOException {
		return state.index.search(required(state, everyFacetDrilled, "hierarchy"));
	}

	@Benchmark
	public SearchResult everyFacet(LoadedIndex state) throws IOException {
		return state.index.search(everyFacet);
	}

	@Benchmark
	public SearchResult everyFacetOnText(LoadedIndex state) throws IOException {
		return state.index.search(required(state, everyFacetOnText, "text"));
	}

	@Benchmark
	public SearchResult everyFacetCountsOnly(LoadedIndex state) throws IOException {
		return state.index.search(everyFacetCountsOnly);
	}

	@Benchmark
	public SearchResult sidewaysFacet(LoadedIndex state) throws IOException {
		return state.index.search(sidewaysFacet);
	}

	@Benchmark
	public SearchResult everyFacetNarrowed(LoadedIndex state, Narrowed narrowed)
		throws IOException
	{
		return state.index.search(narrowed.request);
	}

	@Benchmark
	public SearchResult nestedFacetNarrowed(LoadedIndex state, Narrowed narrowed)
		throws IOException
	{
		return state.index.search(required(state, narrowed.nested, "object"));
	}

	/**
	 * The filtering page narrowed to a share of the index.
	 *
	 * <p>The number field of the corpus is drawn evenly between 0 and 1000, so a
	 * filter keeping everything below {@code ratio × 10} matches that share of
	 * the documents, and the ratio is what the match count follows. The range
	 * facet on that field is told to exclude no filter, so it counts inside the
	 * narrowed scope with the others rather than sideways of the only filter -
	 * which would answer it from the whole-index counts and leave three facets
	 * to measure instead of four.
	 *
	 * <p>{@code nested} is the same narrowing with one facet over a field inside
	 * the object field: the filter is on a field of the document, so every
	 * value of a matched document is among the matches and the facet counts
	 * documents, however many of their values hold each colour.
	 */
	@State(Scope.Thread)
	public static class Narrowed {
		/**
		 * The share of the index the page is narrowed to, in percent.
		 */
		@Param({ "1", "10", "50", "90" })
		public int ratio;

		private SearchRequest request;
		private SearchRequest nested;

		@Setup(Level.Trial)
		public void request(LoadedIndex state) {
			var roles = state.roles;
			var keyword = state.spec.require(roles.keyword(), "keyword");
			var number = state.spec.require(roles.number(), "number");

			var page = SearchRequest.create()
				.addFacet(Facet.of(keyword))
				.addFacet(
					Facet.of(number)
						.withRanges(new Facet.Range(null, 250d), new Facet.Range(250d, null))
						.withExcludeFilters()
				)
				.addFilter(Query.field(number, Matchers.lessThan(ratio * 10d)));

			if(roles.tags() != null) {
				page = page.addFacet(Facet.of(roles.tags()));
			}

			if(roles.hierarchy() != null) {
				page = page.addFacet(Facet.of(roles.hierarchy()));
			}

			request = page.build();

			nested = roles.nested() == null
				? null
				: SearchRequest.create()
					.addFacet(Facet.of(roles.nested() + ".color"))
					.addFilter(Query.field(number, Matchers.lessThan(ratio * 10d)))
					.build();
		}
	}

	private static SearchRequest required(LoadedIndex state, SearchRequest request, String part) {
		if(request == null) {
			throw new IllegalStateException(
				"The " + state.spec.name() + " corpus has no " + part + " field; run this"
					+ " benchmark with a corpus that has one"
			);
		}

		return request;
	}
}
