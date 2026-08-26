package se.l4.exofind.engine.benchmark.engine;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.eclipse.collections.api.factory.Sets;
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
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.matchers.Matchers;

/**
 * What asking which values of an object field matched costs, beside each hit.
 *
 * <p>The answer is found for the whole page in one pass and read out of the
 * copy of each document, so the cost scales with the page rather than with the
 * index - {@code -p page=} is the knob. Each request here is the same search
 * as {@code withoutMatched} with only the asking added, so the difference
 * against it is what the answer costs.
 *
 * <p>{@code matchedUnconditioned} asks with no nested clause in the search, so
 * every value of every hit matched - the walk visits all children instead of
 * the few a condition picked out.
 *
 * <p>Needs a corpus with an object field, which only {@code catalogue} has.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = { JvmArgs.VECTOR, JvmArgs.NATIVE_ACCESS })
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
@State(Scope.Thread)
public class MatchedBenchmark {
	/**
	 * How many hits the page holds, which is what the matched pass scales
	 * with.
	 */
	@Param({ "10", "100" })
	public int page;

	private SearchRequest withoutMatched;
	private SearchRequest matched;
	private SearchRequest matchedUnconditioned;
	private SearchRequest matchedOneField;

	@Setup(Level.Trial)
	public void requests(LoadedIndex state) {
		var nested = state.spec.require(state.roles.nested(), "object");
		var keyword = state.spec.require(state.roles.keyword(), "keyword");

		var condition = Query.nested(
			nested,
			Query.field(nested + ".color", Matchers.equalTo("color-0"))
		);

		withoutMatched = SearchRequest.create()
			.withQuery(condition)
			.withLimit(page)
			.build();

		matched = SearchRequest.create()
			.withQuery(condition)
			.withLimit(page)
			.addMatched(nested)
			.build();

		matchedUnconditioned = SearchRequest.create()
			.withQuery(Query.field(keyword, Matchers.equalTo(state.spec.keywordValue(0))))
			.withLimit(page)
			.addMatched(nested)
			.build();

		matchedOneField = SearchRequest.create()
			.withQuery(condition)
			.withLimit(page)
			.addMatched(
				nested,
				new SearchRequest.Matched(
					SearchRequest.Matched.DEFAULT_LIMIT,
					Sets.immutable.of(nested + ".color")
				)
			)
			.build();
	}

	@Benchmark
	public SearchResult withoutMatched(LoadedIndex state) throws IOException {
		return state.index.search(withoutMatched);
	}

	@Benchmark
	public SearchResult matched(LoadedIndex state) throws IOException {
		return state.index.search(matched);
	}

	@Benchmark
	public SearchResult matchedUnconditioned(LoadedIndex state) throws IOException {
		return state.index.search(matchedUnconditioned);
	}

	@Benchmark
	public SearchResult matchedOneField(LoadedIndex state) throws IOException {
		return state.index.search(matchedOneField);
	}
}
