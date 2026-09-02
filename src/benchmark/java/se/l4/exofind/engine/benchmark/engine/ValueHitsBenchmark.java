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
import se.l4.exofind.engine.query.SortBy;
import se.l4.exofind.engine.query.matchers.Matchers;

/**
 * What hits standing for the matched values of an object field cost.
 *
 * <p>{@code documentHits} is the same search answered with document hits, so
 * the difference against it is what ranking values instead of documents and
 * materializing each hit from the document above it costs.
 * {@code parentCondition} asks nothing of the values, so every value of every
 * matching document is a hit. The facet benchmarks put the two kinds of count
 * such a search can ask for beside each other: per value of the hits
 * themselves, and rolled up onto a field of the documents holding them.
 *
 * <p>Needs a corpus with an object field, which only {@code catalogue} has.
 *
 * <p>The facet benchmarks send the same request every invocation, so the fork
 * runs with {@link JvmArgs#NO_FACET_SCOPE_CACHE} and measures counting instead
 * of the answer a node would keep.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(
	value = 1,
	jvmArgsAppend = { JvmArgs.VECTOR, JvmArgs.NATIVE_ACCESS, JvmArgs.NO_FACET_SCOPE_CACHE }
)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
@State(Scope.Thread)
public class ValueHitsBenchmark {
	private SearchRequest documentHits;
	private SearchRequest valueHits;
	private SearchRequest parentCondition;
	private SearchRequest sortedByValueField;
	private SearchRequest facetOnValueField;
	private SearchRequest facetOnDocumentField;
	private SearchRequest exactTotal;

	@Setup(Level.Trial)
	public void requests(LoadedIndex state) {
		var nested = state.spec.require(state.roles.nested(), "object");
		var keyword = state.spec.require(state.roles.keyword(), "keyword");

		var condition = Query.nested(
			nested,
			Query.field(nested + ".color", Matchers.equalTo("color-0"))
		);

		documentHits = SearchRequest.create()
			.withQuery(condition)
			.build();

		valueHits = SearchRequest.create()
			.withQuery(condition)
			.withHits(nested)
			.build();

		parentCondition = SearchRequest.create()
			.withQuery(Query.field(keyword, Matchers.equalTo(state.spec.keywordValue(0))))
			.withHits(nested)
			.build();

		sortedByValueField = SearchRequest.create()
			.withQuery(condition)
			.withHits(nested)
			.withSort(SortBy.field(nested + ".price"))
			.build();

		facetOnValueField = SearchRequest.create()
			.withQuery(condition)
			.withHits(nested)
			.addFacet(Facet.of(nested + ".color"))
			.build();

		facetOnDocumentField = SearchRequest.create()
			.withQuery(condition)
			.withHits(nested)
			.addFacet(Facet.of(keyword))
			.build();

		exactTotal = SearchRequest.create()
			.withQuery(condition)
			.withHits(nested)
			.withTotal(SearchRequest.Total.EXACT)
			.build();
	}

	@Benchmark
	public SearchResult documentHits(LoadedIndex state) throws IOException {
		return state.index.search(documentHits);
	}

	@Benchmark
	public SearchResult valueHits(LoadedIndex state) throws IOException {
		return state.index.search(valueHits);
	}

	@Benchmark
	public SearchResult parentCondition(LoadedIndex state) throws IOException {
		return state.index.search(parentCondition);
	}

	@Benchmark
	public SearchResult sortedByValueField(LoadedIndex state) throws IOException {
		return state.index.search(sortedByValueField);
	}

	@Benchmark
	public SearchResult facetOnValueField(LoadedIndex state) throws IOException {
		return state.index.search(facetOnValueField);
	}

	@Benchmark
	public SearchResult facetOnDocumentField(LoadedIndex state) throws IOException {
		return state.index.search(facetOnDocumentField);
	}

	@Benchmark
	public SearchResult exactTotal(LoadedIndex state) throws IOException {
		return state.index.search(exactTotal);
	}
}
