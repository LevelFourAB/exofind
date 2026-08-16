package se.l4.exofind.engine.benchmark.rest;

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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import se.l4.exofind.engine.benchmark.JvmArgs;

/**
 * What a search costs over the API, which is the engine plus the request read,
 * the answer written and the round trip between them.
 *
 * <p>Read against the engine benchmark of the same search: the difference is
 * what the API layer adds. {@code searchFromFourClients} is the same request
 * from four connections at once, which is where a node that serializes
 * somewhere shows it.
 *
 * <p>Needs a node running with an index of the corpus - see {@link LoadedNode}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = { JvmArgs.VECTOR, JvmArgs.NATIVE_ACCESS })
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
@State(Scope.Thread)
public class RestSearchBenchmark {
	private String path;

	private String matchAll;
	private String filter;
	private String text;
	private String textWithFacets;
	private String textWithHighlight;
	private String hundredHits;

	@Setup(Level.Trial)
	public void requests(LoadedNode state) {
		path = "/v1alpha1/indexes/" + state.index + "/search";

		var keyword = state.spec.require(state.roles.keyword(), "keyword");
		var word = state.words.byRank(8);

		matchAll = "{}";
		filter = "{\"filters\":[{\"field\":\"" + keyword + "\",\"match\":{\"value\":\""
			+ state.spec.keywordValue(0) + "\"}}]}";
		text = "{\"query\":[{\"type\":\"text\",\"text\":\"" + word + "\"}]}";
		textWithFacets = "{\"query\":[{\"type\":\"text\",\"text\":\"" + word
			+ "\"}],\"facets\":[{\"field\":\"" + keyword + "\"}]}";
		textWithHighlight = "{\"query\":[{\"type\":\"text\",\"text\":\"" + word
			+ "\"}],\"highlight\":{\"fields\":{\"" + state.roles.text().get(0) + "\":{}}}}";
		hundredHits = "{\"query\":[{\"type\":\"text\",\"text\":\"" + word + "\"}],\"limit\":100}";
	}

	@Benchmark
	public String matchAll(LoadedNode state) {
		return state.client.post(path, "application/json", matchAll);
	}

	@Benchmark
	public String filter(LoadedNode state) {
		return state.client.post(path, "application/json", filter);
	}

	@Benchmark
	public String text(LoadedNode state) {
		return state.client.post(path, "application/json", text);
	}

	@Benchmark
	public String textWithFacets(LoadedNode state) {
		return state.client.post(path, "application/json", textWithFacets);
	}

	@Benchmark
	public String textWithHighlight(LoadedNode state) {
		return state.client.post(path, "application/json", textWithHighlight);
	}

	@Benchmark
	public String hundredHits(LoadedNode state) {
		return state.client.post(path, "application/json", hundredHits);
	}

	@Benchmark
	@Threads(4)
	public String searchFromFourClients(LoadedNode state) {
		return state.client.post(path, "application/json", text);
	}
}
