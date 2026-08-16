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
import se.l4.exofind.engine.query.matchers.Matchers;

/**
 * What conditions on the values inside an object field cost.
 *
 * <p>A nested clause joins the values back to the documents holding them once,
 * however many conditions it carries. {@code twoNestedClauses} is the same two
 * conditions as two clauses instead of one - a different question, since each
 * may be satisfied by a different value, and the difference against
 * {@code twoConditions} is what the second join costs.
 *
 * <p>Needs a corpus with an object field, which only {@code catalogue} has.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = { JvmArgs.VECTOR, JvmArgs.NATIVE_ACCESS })
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
@State(Scope.Thread)
public class NestedBenchmark {
	private SearchRequest oneCondition;
	private SearchRequest twoConditions;
	private SearchRequest twoNestedClauses;
	private SearchRequest facetOnNestedField;
	private SearchRequest nestedWithText;

	@Setup(Level.Trial)
	public void requests(LoadedIndex state) {
		var nested = state.spec.require(state.roles.nested(), "object");

		oneCondition = SearchRequest.create()
			.withQuery(
				Query.nested(nested, Query.field(nested + ".color", Matchers.equalTo("color-0")))
			)
			.build();

		twoConditions = SearchRequest.create()
			.withQuery(
				Query.nested(
					nested,
					Query.field(nested + ".color", Matchers.equalTo("color-0")),
					Query.field(nested + ".price", Matchers.atMost(250d))
				)
			)
			.build();

		twoNestedClauses = SearchRequest.create()
			.withQuery(
				Query.nested(nested, Query.field(nested + ".color", Matchers.equalTo("color-0"))),
				Query.nested(nested, Query.field(nested + ".price", Matchers.atMost(250d)))
			)
			.build();

		facetOnNestedField = SearchRequest.create()
			.addFacet(Facet.of(nested + ".color"))
			.build();

		nestedWithText = state.roles.text().isEmpty()
			? null
			: SearchRequest.create()
				.withQuery(
					Query.text(state.commonTerm()),
					Query.nested(
						nested,
						Query.field(nested + ".color", Matchers.equalTo("color-0"))
					)
				)
				.build();
	}

	@Benchmark
	public SearchResult oneCondition(LoadedIndex state) throws IOException {
		return state.index.search(oneCondition);
	}

	@Benchmark
	public SearchResult twoConditions(LoadedIndex state) throws IOException {
		return state.index.search(twoConditions);
	}

	@Benchmark
	public SearchResult twoNestedClauses(LoadedIndex state) throws IOException {
		return state.index.search(twoNestedClauses);
	}

	@Benchmark
	public SearchResult facetOnNestedField(LoadedIndex state) throws IOException {
		return state.index.search(facetOnNestedField);
	}

	@Benchmark
	public SearchResult nestedWithText(LoadedIndex state) throws IOException {
		if(nestedWithText == null) {
			throw new IllegalStateException(
				"The " + state.spec.name() + " corpus has no text fields; run this benchmark"
					+ " with a corpus that has them"
			);
		}

		return state.index.search(nestedWithText);
	}
}
