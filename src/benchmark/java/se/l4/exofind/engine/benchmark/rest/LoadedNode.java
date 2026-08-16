package se.l4.exofind.engine.benchmark.rest;

import java.util.ArrayList;
import java.util.Map;

import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import se.l4.exofind.engine.api.v1alpha1.admin.IndexDefinitionMapper;
import se.l4.exofind.engine.benchmark.corpus.Corpora;
import se.l4.exofind.engine.benchmark.corpus.Corpus;
import se.l4.exofind.engine.benchmark.corpus.Words;
import se.l4.exofind.engine.index.Document;

/**
 * A running node holding an index of a corpus, for the benchmarks that measure
 * the API rather than the engine.
 *
 * <p>The node has to be started and reachable before the benchmark runs, and
 * has to be the indexer - a node that is not answers every write with the index
 * being readonly. Setup defines the index and fills it if it does not already
 * hold the right number of documents, so a second run against the same node
 * starts measuring immediately.
 *
 * <p>Nothing is removed afterwards. The index keeps the name it was given, and
 * is what a later run reuses.
 */
@State(Scope.Benchmark)
public class LoadedNode {
	private static final int LOAD_BATCH = 2_000;

	/**
	 * Where the node answers.
	 */
	@Param({ "http://localhost:8080" })
	public String node;

	/**
	 * The credential to send, or empty for a node that checks none.
	 */
	@Param({ "" })
	public String key;

	/**
	 * Which corpus to search, by the names {@link Corpora#of(String)} takes.
	 */
	@Param({ "catalogue" })
	public String corpus;

	/**
	 * How many documents the index holds.
	 */
	@Param({ "100000" })
	public int size;

	public Node client;
	public Corpus spec;
	public Words words;
	public Corpus.Roles roles;

	/**
	 * The name of the index the search benchmarks read, which holds the corpus
	 * and is never written to while they run.
	 */
	public String index;

	/**
	 * The name of the index the indexing benchmarks write to, kept apart so
	 * that writing to it cannot change what a search benchmark measures.
	 */
	public String scratch;

	@Setup(Level.Trial)
	public void load() {
		client = new Node(node, key);
		spec = Corpora.of(corpus);
		words = spec.words();
		roles = spec.roles();

		index = "benchmark-" + spec.name().replace(':', '-') + "-" + size;
		scratch = index + "-scratch";

		define(index);
		define(scratch);

		if(count(index) != size) {
			fill(index, 0, size);
			commit(index);
		}
	}

	/**
	 * Send the definition of the corpus, which creates the index the first time
	 * and changes nothing on a later run.
	 */
	private void define(String name) {
		client.put(
			"/v1alpha1/admin/indexes/" + name,
			"application/json",
			Documents.write(IndexDefinitionMapper.toApi(spec.definition()))
		);
	}

	/**
	 * Put the documents at ordinals {@code from} up to {@code to} into an
	 * index, in batches a node will accept as one request.
	 */
	public void fill(String name, int from, int to) {
		for(var start = from; start < to; start += LOAD_BATCH) {
			var batch = new ArrayList<Document>(Math.min(LOAD_BATCH, to - start));
			for(var ordinal = start; ordinal < Math.min(start + LOAD_BATCH, to); ordinal++) {
				batch.add(spec.document(ordinal));
			}

			client.post(
				"/v1alpha1/indexes/" + name + "/documents",
				"application/x-ndjson",
				Documents.ndjson(batch)
			);
		}
	}

	public void commit(String name) {
		client.post("/v1alpha1/admin/indexes/" + name + "/actions/commit", "application/json", "");
	}

	/**
	 * Get how many documents an index holds, counted exactly.
	 */
	public long count(String name) {
		var answer = Documents.read(
			client.post(
				"/v1alpha1/indexes/" + name + "/search",
				"application/json",
				"{\"limit\":0,\"total\":\"exact\"}"
			)
		);

		@SuppressWarnings("unchecked")
		var total = (Map<String, Object>) answer.get("total");
		return ((Number) total.get("count")).longValue();
	}
}
