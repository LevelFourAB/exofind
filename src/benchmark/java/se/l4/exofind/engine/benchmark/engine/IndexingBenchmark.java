package se.l4.exofind.engine.benchmark.engine;

import java.io.IOException;
import java.nio.file.Path;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import se.l4.exofind.engine.benchmark.JvmArgs;
import se.l4.exofind.engine.benchmark.corpus.Corpora;
import se.l4.exofind.engine.benchmark.corpus.Corpus;
import se.l4.exofind.engine.index.Document;
import se.l4.exofind.engine.index.Index;

/**
 * What it costs to put documents into an empty index.
 *
 * <p>Every measurement writes {@code batch} documents into an index of its own,
 * so a result is per batch rather than per document - divide by the batch size
 * for a rate. The documents are generated before the timing starts, so what is
 * measured is analysis and writing rather than making them up.
 *
 * <p>Running this over {@code minimal} and then over {@code catalogue} or
 * {@code articles} is how the cost of the usages a field declares is told from
 * the cost of holding a document at all.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = { JvmArgs.VECTOR, JvmArgs.NATIVE_ACCESS })
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 5)
@State(Scope.Thread)
public class IndexingBenchmark {
	/**
	 * Which corpus to index, by the names {@link Corpora#of(String)} takes.
	 */
	@Param({ "minimal", "catalogue", "articles" })
	public String corpus;

	/**
	 * How many documents one measurement writes.
	 */
	@Param({ "2000" })
	public int batch;

	private Corpus spec;
	private List<Document> documents;

	private Path directory;
	private Index index;

	@Setup(Level.Trial)
	public void generate() {
		spec = Corpora.of(corpus);
		documents = Corpora.documents(spec, batch);
	}

	@Setup(Level.Invocation)
	public void openEmpty() throws IOException {
		directory = BenchmarkIndexes.workDirectory();
		index = BenchmarkIndexes.empty(spec, directory);
	}

	@TearDown(Level.Invocation)
	public void closeEmpty() throws IOException {
		index.close(false);
		BenchmarkIndexes.delete(directory);
	}

	/**
	 * Writing documents and leaving them uncommitted, which is what a bulk load
	 * spends nearly all of its time in.
	 */
	@Benchmark
	public void add() throws IOException {
		for(var document : documents) {
			index.addDocument(document);
		}
	}

	/**
	 * The same documents with a commit at the end - the difference against
	 * {@link #add()} is what making a batch searchable costs.
	 */
	@Benchmark
	public void addAndCommit() throws IOException {
		for(var document : documents) {
			index.addDocument(document);
		}

		index.commit();
	}

	/**
	 * The same documents committed every hundred, which is the shape of a load
	 * that keeps the index searchable while it runs.
	 */
	@Benchmark
	public void addCommittingOften() throws IOException {
		var written = 0;
		for(var document : documents) {
			index.addDocument(document);

			if(++written % 100 == 0) {
				index.commit();
			}
		}

		index.commit();
	}
}
