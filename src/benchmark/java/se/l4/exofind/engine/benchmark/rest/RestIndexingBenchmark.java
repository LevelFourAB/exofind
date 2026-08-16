package se.l4.exofind.engine.benchmark.rest;

import java.util.ArrayList;
import java.util.List;
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
import se.l4.exofind.engine.index.Document;

/**
 * What putting documents into an index over the API costs.
 *
 * <p>Every measurement sends the same batch, so after the first one each
 * document replaces the one already there and the index stays the size it
 * settled at rather than growing through the run. It writes to the scratch
 * index of {@link LoadedNode}, so a search benchmark run against the same node
 * is unaffected.
 *
 * <p>Read against the engine's indexing benchmark of the same corpus and batch:
 * the difference is what reading the request and mapping the documents adds.
 *
 * <p>Needs a node running as the indexer.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = { JvmArgs.VECTOR, JvmArgs.NATIVE_ACCESS })
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 5)
@State(Scope.Thread)
public class RestIndexingBenchmark {
	/**
	 * How many documents one request carries.
	 */
	@Param({ "1000" })
	public int batch;

	private String documentsPath;
	private String commitPath;
	private String ndjson;
	private String json;

	@Setup(Level.Trial)
	public void requests(LoadedNode state) {
		documentsPath = "/v1alpha1/indexes/" + state.scratch + "/documents";
		commitPath = "/v1alpha1/admin/indexes/" + state.scratch + "/actions/commit";

		/*
		 * Ordinals past the corpus the search benchmarks read, so that what is
		 * written here is never a document one of them expects to find.
		 */
		var documents = new ArrayList<Document>(batch);
		for(var i = 0; i < batch; i++) {
			documents.add(state.spec.document(state.size + i));
		}

		ndjson = Documents.ndjson(documents);
		json = "{\"documents\":[" + String.join(",", asJson(documents)) + "]}";
	}

	/**
	 * A batch sent as newline delimited JSON, which is what a bulk load uses.
	 */
	@Benchmark
	public String addNdjson(LoadedNode state) {
		return state.client.post(documentsPath, "application/x-ndjson", ndjson);
	}

	/**
	 * The same batch sent as one JSON object, which is what a caller with a
	 * handful of documents sends.
	 */
	@Benchmark
	public String addJson(LoadedNode state) {
		return state.client.post(documentsPath, "application/json", json);
	}

	/**
	 * A batch and then a commit, which is what makes it searchable.
	 */
	@Benchmark
	public String addAndCommit(LoadedNode state) {
		state.client.post(documentsPath, "application/x-ndjson", ndjson);
		return state.client.post(commitPath, "application/json", "");
	}

	private static List<String> asJson(List<Document> documents) {
		return documents.stream().map(Documents::json).toList();
	}
}
