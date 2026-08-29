package se.l4.exofind.engine.benchmark.engine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.collections.api.factory.Lists;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import se.l4.exofind.engine.benchmark.JvmArgs;
import se.l4.exofind.engine.benchmark.corpus.Corpora;
import se.l4.exofind.engine.benchmark.corpus.Corpus;
import se.l4.exofind.engine.index.Document;
import se.l4.exofind.engine.index.DocumentPatch;
import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.matchers.Matchers;

/**
 * What changing documents that are already indexed costs.
 *
 * <p>Each measurement runs against a fresh copy of an index holding
 * {@code batch} committed documents, so replacing and deleting always meet the
 * same starting point. The copy is made in setup and not measured.
 *
 * <p>Read against {@link IndexingBenchmark}'s {@code add}: writing a document a
 * second time also has to find the one it replaces, and a patch has to read the
 * kept copy and merge into it before anything is written.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = { JvmArgs.VECTOR, JvmArgs.NATIVE_ACCESS })
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 5)
@State(Scope.Thread)
public class DocumentChangeBenchmark {
	/**
	 * Which corpus to change, by the names {@link Corpora#of(String)} takes.
	 */
	@Param({ "minimal", "catalogue" })
	public String corpus;

	/**
	 * How many documents the index holds, and how many one measurement changes.
	 */
	@Param({ "2000" })
	public int batch;

	private Corpus spec;
	private List<Document> documents;
	private List<DocumentPatch> patches;
	private List<Object> keys;
	private String keyField;

	private Path filled;
	private Path directory;
	private Index index;

	@Setup(Level.Trial)
	public void fill() throws IOException {
		spec = Corpora.of(corpus);
		documents = Corpora.documents(spec, batch);
		keyField = primaryKey(spec);
		keys = documents.stream().map(document -> document.get(keyField)).toList();
		patches = documents.stream().map(document -> patch(spec, keyField, document)).toList();

		filled = BenchmarkIndexes.workDirectory();
		var built = BenchmarkIndexes.empty(spec, filled);
		try {
			for(var document : documents) {
				built.addDocument(document);
			}

			built.commit();
		} finally {
			built.close(false);
		}
	}

	@TearDown(Level.Trial)
	public void removeFilled() throws IOException {
		BenchmarkIndexes.delete(filled);
	}

	@Setup(Level.Invocation)
	public void openCopy() throws IOException {
		directory = BenchmarkIndexes.copyOf(filled);
		index = BenchmarkIndexes.open(spec.name(), directory);
	}

	@TearDown(Level.Invocation)
	public void closeCopy() throws IOException {
		index.close(false);
		BenchmarkIndexes.delete(directory);
	}

	/**
	 * Writing every document again, which replaces the one already indexed.
	 */
	@Benchmark
	public void replace() throws IOException {
		for(var document : documents) {
			index.addDocument(document);
		}
	}

	/**
	 * Changing one field of every document.
	 */
	@Benchmark
	public void update() throws IOException {
		for(var patch : patches) {
			index.updateDocument(patch);
		}
	}

	/**
	 * Taking every document out by its key, one call at a time.
	 */
	@Benchmark
	public void deleteOneByOne() throws IOException {
		for(var key : keys) {
			index.deleteDocument(key);
		}
	}

	/**
	 * Taking every document out in one call, which is what the API does with a
	 * list of keys.
	 */
	@Benchmark
	public int deleteInOneCall() throws IOException {
		return index.deleteDocuments(Lists.immutable.ofAll(keys));
	}

	/**
	 * Taking documents out by what they hold rather than by key, which has to
	 * run the query before anything is removed.
	 */
	@Benchmark
	public int deleteByQuery() throws IOException {
		return index.deleteByQuery(
			Lists.immutable.of(
				Query.field(spec.require(spec.roles().keyword(), "keyword"), Matchers.any())
			),
			null
		);
	}

	private static String primaryKey(Corpus spec) {
		return spec.definition().getFieldsMap().entrySet().stream()
			.filter(entry -> entry.getValue().getPrimaryKey())
			.map(entry -> entry.getKey())
			.findFirst()
			.orElseThrow();
	}

	/**
	 * A patch of one field, which is the shape of the update a stock level or a
	 * price arrives as.
	 */
	private static DocumentPatch patch(Corpus spec, String key, Document document) {
		var roles = spec.roles();
		var changed = roles.number() == null ? roles.keyword() : roles.number();

		return DocumentPatch.replacing(
			Sets.immutable.of(key, changed),
			Lists.immutable.of(
				new Document.Value(key, document.get(key)),
				new Document.Value(changed, document.get(changed))
			)
		);
	}
}
