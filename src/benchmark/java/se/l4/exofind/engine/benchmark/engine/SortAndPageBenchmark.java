package se.l4.exofind.engine.benchmark.engine;

import java.io.IOException;
import java.time.Duration;
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
import se.l4.exofind.engine.query.RankingSignal;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.SortBy;

/**
 * What the order results come back in costs, and what it costs to reach a page
 * far into them.
 *
 * <p>Ordering by a field reads a value per match instead of scoring it, and
 * paging by offset walks everything before the window while a cursor starts at
 * it - which is what {@code deepOffset} and {@code deepCursor} are there to
 * compare.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = { JvmArgs.VECTOR, JvmArgs.NATIVE_ACCESS })
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
@State(Scope.Thread)
public class SortAndPageBenchmark {
	/**
	 * The furthest a page is asked for, as a share of the index - a page a
	 * crawler or an export reaches, rather than one a reader clicks to. Taken
	 * from the size so that the deep benchmarks stay inside the results however
	 * many documents the index holds.
	 */
	private static final int DEEP_FRACTION = 10;

	private SearchRequest byScore;
	private SearchRequest byField;
	private SearchRequest byFieldDescending;
	private SearchRequest byTwoFields;
	private SearchRequest byDistance;
	private SearchRequest withSignals;
	private SearchRequest firstPage;
	private SearchRequest deepOffset;
	private SearchRequest deepCursor;
	private SearchRequest storedFields;
	private SearchRequest oneStoredField;

	@Setup(Level.Trial)
	public void requests(LoadedIndex state) throws IOException {
		var roles = state.roles;
		var text = roles.text().isEmpty() ? null : Query.text(state.commonTerm());
		var sortField = roles.number() == null ? roles.timestamp() : roles.number();
		state.spec.require(sortField, "sortable");

		byScore = text == null
			? null
			: SearchRequest.create().withQuery(text).withSort(SortBy.score()).build();
		byField = SearchRequest.create().withSort(SortBy.field(sortField)).build();
		byFieldDescending = SearchRequest.create()
			.withSort(SortBy.field(sortField, SortBy.Order.DESCENDING))
			.build();
		byTwoFields = SearchRequest.create()
			.withSort(
				SortBy.field(state.spec.require(roles.keyword(), "keyword")),
				SortBy.field(sortField, SortBy.Order.DESCENDING)
			)
			.build();

		var geo = roles.geo();
		byDistance = geo == null
			? null
			: SearchRequest.create().withSort(SortBy.distance(geo, 59.33, 18.06)).build();

		var timestamp = roles.timestamp();
		withSignals = text == null || timestamp == null
			? null
			: SearchRequest.create()
				.withQuery(text)
				.withSignals(
					RankingSignal.saturation(sortField, 50),
					RankingSignal.decay(timestamp, Duration.ofDays(90))
				)
				.build();

		var deep = Math.max(1, state.size / DEEP_FRACTION);

		firstPage = SearchRequest.create()
			.withSort(SortBy.field(sortField))
			.withLimit(20)
			.build();
		deepOffset = SearchRequest.create()
			.withSort(SortBy.field(sortField))
			.withLimit(20)
			.withOffset(deep)
			.build();

		/*
		 * The cursor is taken from the window the offset would have to walk to,
		 * so both benchmarks answer the same page from the same order.
		 */
		var window = state.index.search(
			SearchRequest.create()
				.withSort(SortBy.field(sortField))
				.withLimit(1)
				.withOffset(deep - 1)
				.build()
		);
		deepCursor = SearchRequest.create()
			.withSort(SortBy.field(sortField))
			.withLimit(20)
			.withAfter(window.hits().getLast().key())
			.build();

		storedFields = SearchRequest.create().withLimit(100).build();
		oneStoredField = SearchRequest.create()
			.withLimit(100)
			.withFields(sortField)
			.build();
	}

	@Benchmark
	public SearchResult byScore(LoadedIndex state) throws IOException {
		return state.index.search(required(state, byScore, "text"));
	}

	@Benchmark
	public SearchResult byField(LoadedIndex state) throws IOException {
		return state.index.search(byField);
	}

	@Benchmark
	public SearchResult byFieldDescending(LoadedIndex state) throws IOException {
		return state.index.search(byFieldDescending);
	}

	@Benchmark
	public SearchResult byTwoFields(LoadedIndex state) throws IOException {
		return state.index.search(byTwoFields);
	}

	@Benchmark
	public SearchResult byDistance(LoadedIndex state) throws IOException {
		return state.index.search(required(state, byDistance, "geo point"));
	}

	@Benchmark
	public SearchResult withSignals(LoadedIndex state) throws IOException {
		return state.index.search(required(state, withSignals, "text and timestamp"));
	}

	@Benchmark
	public SearchResult firstPage(LoadedIndex state) throws IOException {
		return state.index.search(firstPage);
	}

	@Benchmark
	public SearchResult deepOffset(LoadedIndex state) throws IOException {
		return state.index.search(deepOffset);
	}

	@Benchmark
	public SearchResult deepCursor(LoadedIndex state) throws IOException {
		return state.index.search(deepCursor);
	}

	@Benchmark
	public SearchResult storedFields(LoadedIndex state) throws IOException {
		return state.index.search(storedFields);
	}

	@Benchmark
	public SearchResult oneStoredField(LoadedIndex state) throws IOException {
		return state.index.search(oneStoredField);
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
