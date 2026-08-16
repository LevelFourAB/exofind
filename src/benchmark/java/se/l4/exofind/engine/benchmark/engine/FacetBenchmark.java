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
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import se.l4.exofind.engine.benchmark.JvmArgs;
import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;

/**
 * What counting the matches per value costs on top of finding them.
 *
 * <p>Every facet walks the matches a second time, so these are read against the
 * same search without facets in {@link FilterBenchmark}. The counted set is
 * everything unless a benchmark says otherwise, which is the widest a facet
 * gets asked to count.
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
	private SearchRequest everyFacet;
	private SearchRequest everyFacetOnText;

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
		 * What a filtering page actually sends - every facet the corpus can
		 * offer at once, which is where the cost of counting shows.
		 */
		var all = SearchRequest.create().addFacet(Facet.of(keyword));
		if(tags != null) {
			all = all.addFacet(Facet.of(tags));
		}
		if(number != null) {
			all = all.addFacet(Facet.of(number).withRanges(new Facet.Range(null, 250d), new Facet.Range(250d, null)));
		}
		if(hierarchy != null) {
			all = all.addFacet(Facet.of(hierarchy));
		}

		everyFacet = all.build();
		everyFacetOnText = roles.text().isEmpty()
			? null
			: all.withQuery(Query.text(state.commonTerm())).build();
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
	public SearchResult everyFacet(LoadedIndex state) throws IOException {
		return state.index.search(everyFacet);
	}

	@Benchmark
	public SearchResult everyFacetOnText(LoadedIndex state) throws IOException {
		return state.index.search(required(state, everyFacetOnText, "text"));
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
