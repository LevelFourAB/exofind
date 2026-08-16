package se.l4.exofind.engine.benchmark.engine;

import java.io.IOException;
import java.nio.file.Path;

import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import se.l4.exofind.engine.benchmark.corpus.Corpora;
import se.l4.exofind.engine.benchmark.corpus.Corpus;
import se.l4.exofind.engine.benchmark.corpus.Words;
import se.l4.exofind.engine.index.Index;

/**
 * A committed index of a corpus, opened for the length of a trial.
 *
 * <p>The index is built once per corpus and size and copied for each trial, so
 * what a search benchmark measures is the search rather than the filling of the
 * index it runs against. Nothing here writes to the index.
 */
@State(Scope.Benchmark)
public class LoadedIndex {
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

	public Corpus spec;
	public Index index;
	public Words words;
	public Corpus.Roles roles;

	private Path directory;

	@Setup(Level.Trial)
	public void open() throws IOException {
		spec = Corpora.of(corpus);
		words = spec.words();
		roles = spec.roles();
		directory = BenchmarkIndexes.copyOf(BenchmarkIndexes.template(spec, size));
		index = BenchmarkIndexes.open(spec.name(), directory);
	}

	@TearDown(Level.Trial)
	public void close() throws IOException {
		index.close(false);
		BenchmarkIndexes.delete(directory);
	}

	/**
	 * Get a term held by roughly one document in ten - what a search that has
	 * to rank a large part of the index looks for.
	 */
	public String commonTerm() {
		return words.byRank(8);
	}

	/**
	 * Get a term held by a middling number of documents, long enough to be cut
	 * short into a prefix or given a typo.
	 */
	public String mediumTerm() {
		return words.byRankAtLeast(300, 6);
	}

	/**
	 * Get a term held by a handful of documents, where finding them costs more
	 * than ranking them.
	 */
	public String rareTerm() {
		return words.byRank(6_000);
	}
}
