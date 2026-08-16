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
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.matchers.Matchers;

/**
 * What narrowing a search costs, with nothing to rank.
 *
 * <p>A filter is exact and takes no part in ranking, so these say what the floor
 * of a request is - what a search costs before anything has to be scored.
 * {@code everything} against {@code everythingExactTotal} is what counting
 * every match rather than stopping at a lower bound costs.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = { JvmArgs.VECTOR, JvmArgs.NATIVE_ACCESS })
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
@State(Scope.Thread)
public class FilterBenchmark {
	private SearchRequest everything;
	private SearchRequest everythingExactTotal;
	private SearchRequest keyword;
	private SearchRequest keywordIn;
	private SearchRequest keywordPrefix;
	private SearchRequest numberRange;
	private SearchRequest timestampRange;
	private SearchRequest severalClauses;
	private SearchRequest negated;
	private SearchRequest underPath;
	private SearchRequest withinDistance;
	private SearchRequest hundredHits;

	@Setup(Level.Trial)
	public void requests(LoadedIndex state) {
		var roles = state.roles;
		var keywordField = state.spec.require(roles.keyword(), "keyword");

		everything = SearchRequest.all();
		everythingExactTotal = SearchRequest.create()
			.withTotal(SearchRequest.Total.EXACT)
			.build();

		keyword = query(Query.field(keywordField, Matchers.equalTo(value(state, 0))));
		keywordIn = query(
			Query.field(
				keywordField,
				Matchers.in(value(state, 0), value(state, 1), value(state, 2))
			)
		);
		keywordPrefix = query(Query.field(keywordField, Matchers.prefix(prefix(state))));

		var numberField = roles.number();
		numberRange = numberField == null
			? null
			: query(Query.field(numberField, Matchers.between(100d, 400d)));

		var timestampField = roles.timestamp();
		timestampRange = timestampField == null
			? null
			: query(
				Query.field(
					timestampField,
					Matchers.between("2022-01-01T00:00:00Z", "2023-01-01T00:00:00Z")
				)
			);

		severalClauses = numberField == null
			? query(Query.field(keywordField, Matchers.equalTo(value(state, 0))))
			: query(
				Query.field(keywordField, Matchers.equalTo(value(state, 0))),
				Query.field(numberField, Matchers.atLeast(100d))
			);

		negated = query(Query.not(Query.field(keywordField, Matchers.equalTo(value(state, 0)))));

		var hierarchy = roles.hierarchy();
		underPath = hierarchy == null
			? null
			: query(Query.field(hierarchy, Matchers.under("cat-0")));

		var geo = roles.geo();
		withinDistance = geo == null
			? null
			: query(Query.field(geo, Matchers.withinDistance(59.33, 18.06, 500_000)));

		hundredHits = SearchRequest.create()
			.withQuery(Query.field(keywordField, Matchers.equalTo(value(state, 0))))
			.withLimit(100)
			.build();
	}

	@Benchmark
	public SearchResult everything(LoadedIndex state) throws IOException {
		return state.index.search(everything);
	}

	@Benchmark
	public SearchResult everythingExactTotal(LoadedIndex state) throws IOException {
		return state.index.search(everythingExactTotal);
	}

	@Benchmark
	public SearchResult keyword(LoadedIndex state) throws IOException {
		return state.index.search(keyword);
	}

	@Benchmark
	public SearchResult keywordIn(LoadedIndex state) throws IOException {
		return state.index.search(keywordIn);
	}

	@Benchmark
	public SearchResult keywordPrefix(LoadedIndex state) throws IOException {
		return state.index.search(keywordPrefix);
	}

	@Benchmark
	public SearchResult numberRange(LoadedIndex state) throws IOException {
		return state.index.search(required(state, numberRange, "number"));
	}

	@Benchmark
	public SearchResult timestampRange(LoadedIndex state) throws IOException {
		return state.index.search(required(state, timestampRange, "timestamp"));
	}

	@Benchmark
	public SearchResult severalClauses(LoadedIndex state) throws IOException {
		return state.index.search(severalClauses);
	}

	@Benchmark
	public SearchResult negated(LoadedIndex state) throws IOException {
		return state.index.search(negated);
	}

	@Benchmark
	public SearchResult underPath(LoadedIndex state) throws IOException {
		return state.index.search(required(state, underPath, "hierarchy"));
	}

	@Benchmark
	public SearchResult withinDistance(LoadedIndex state) throws IOException {
		return state.index.search(required(state, withinDistance, "geo point"));
	}

	@Benchmark
	public SearchResult hundredHits(LoadedIndex state) throws IOException {
		return state.index.search(hundredHits);
	}

	private static String value(LoadedIndex state, int rank) {
		return state.spec.keywordValue(rank);
	}

	/**
	 * A prefix several values start with, which is what makes the clause walk
	 * the terms rather than look one up.
	 */
	private static String prefix(LoadedIndex state) {
		return state.spec.keywordValue(1);
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

	private static SearchRequest query(Query... clauses) {
		return SearchRequest.create().withQuery(clauses).build();
	}
}
