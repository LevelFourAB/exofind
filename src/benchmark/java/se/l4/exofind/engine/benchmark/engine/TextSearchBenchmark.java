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
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.TextQuery;
import se.l4.exofind.engine.query.matchers.TextMatcher;

/**
 * What text typed into a search box costs to answer.
 *
 * <p>Each request is built before the trial starts, so what is measured is
 * compiling and running the query rather than describing it. The terms come
 * from the vocabulary of the corpus by rank, which is what decides how many
 * documents a clause has to walk.
 *
 * <p>Needs a corpus with text fields, which {@code minimal} is not.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = { JvmArgs.VECTOR, JvmArgs.NATIVE_ACCESS })
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
@State(Scope.Thread)
public class TextSearchBenchmark {
	private SearchRequest commonWord;
	private SearchRequest rareWord;
	private SearchRequest twoWords;
	private SearchRequest fourWords;
	private SearchRequest fourWordsRelaxed;
	private SearchRequest asYouType;
	private SearchRequest withTypos;
	private SearchRequest withoutTypos;
	private SearchRequest phrase;
	private SearchRequest userSyntax;
	private SearchRequest oneField;
	private SearchRequest highlighted;
	private SearchRequest completing;

	@Setup(Level.Trial)
	public void requests(LoadedIndex state) {
		var roles = state.roles;
		if(roles.text().isEmpty()) {
			throw new IllegalStateException(
				"The " + state.spec.name() + " corpus has no text fields; run this benchmark"
					+ " with a corpus that has them"
			);
		}

		var field = roles.text().get(0);
		var common = state.commonTerm();
		var rare = state.rareTerm();
		var medium = state.mediumTerm();

		commonWord = query(Query.text(common));

		/*
		 * Relaxation off, so that the cost is finding the few documents holding
		 * the word rather than the second pass a search that found nothing
		 * makes - which is what fourWordsRelaxed measures instead.
		 */
		rareWord = query(
			Query.text(TextMatcher.of(rare).withRelax(TextMatcher.Relax.OFF))
		);
		twoWords = query(Query.text(common + " " + medium));
		fourWords = query(
			Query.text(
				TextMatcher.of(common + " " + medium + " " + rare + " " + state.words.byRank(19_000))
					.withRelax(TextMatcher.Relax.OFF)
			)
		);

		/*
		 * The same words with relaxation left on. The last of them is rare
		 * enough that nothing holds all four, which is what makes the search
		 * fall back rather than answer from the first pass.
		 */
		fourWordsRelaxed = query(
			Query.text(
				TextMatcher.of(common + " " + medium + " " + rare + " " + state.words.byRank(19_000))
					.withRelax(TextMatcher.Relax.WORDS)
			)
		);

		asYouType = query(Query.text(common + " " + medium.substring(0, 3)));
		withTypos = query(Query.text(TextMatcher.of(typo(medium)).withPrefix(TextMatcher.Prefix.OFF)));
		withoutTypos = query(
			Query.text(
				TextMatcher.of(typo(medium))
					.withPrefix(TextMatcher.Prefix.OFF)
					.withTypos(TextMatcher.Typos.OFF)
			)
		);
		phrase = query(
			Query.text(
				TextMatcher.of(common + " " + medium).withMatch(TextMatcher.Match.PHRASE)
			)
		);
		userSyntax = query(
			Query.text(
				TextMatcher.of("\"" + common + " " + medium + "\" -" + rare)
					.withMatch(TextMatcher.Match.USER)
			)
		);
		oneField = query(Query.text(common).withField(field));

		highlighted = SearchRequest.create()
			.withQuery(Query.text(common))
			.addHighlight(field)
			.build();

		var autocomplete = roles.autocomplete();
		completing = autocomplete == null
			? null
			: query(Query.text(medium.substring(0, 3)).withField(autocomplete));
	}

	@Benchmark
	public SearchResult commonWord(LoadedIndex state) throws IOException {
		return state.index.search(commonWord);
	}

	@Benchmark
	public SearchResult rareWord(LoadedIndex state) throws IOException {
		return state.index.search(rareWord);
	}

	@Benchmark
	public SearchResult twoWords(LoadedIndex state) throws IOException {
		return state.index.search(twoWords);
	}

	@Benchmark
	public SearchResult fourWords(LoadedIndex state) throws IOException {
		return state.index.search(fourWords);
	}

	@Benchmark
	public SearchResult fourWordsRelaxed(LoadedIndex state) throws IOException {
		return state.index.search(fourWordsRelaxed);
	}

	@Benchmark
	public SearchResult asYouType(LoadedIndex state) throws IOException {
		return state.index.search(asYouType);
	}

	@Benchmark
	public SearchResult withTypos(LoadedIndex state) throws IOException {
		return state.index.search(withTypos);
	}

	@Benchmark
	public SearchResult withoutTypos(LoadedIndex state) throws IOException {
		return state.index.search(withoutTypos);
	}

	@Benchmark
	public SearchResult phrase(LoadedIndex state) throws IOException {
		return state.index.search(phrase);
	}

	@Benchmark
	public SearchResult userSyntax(LoadedIndex state) throws IOException {
		return state.index.search(userSyntax);
	}

	@Benchmark
	public SearchResult oneField(LoadedIndex state) throws IOException {
		return state.index.search(oneField);
	}

	@Benchmark
	public SearchResult highlighted(LoadedIndex state) throws IOException {
		return state.index.search(highlighted);
	}

	@Benchmark
	public SearchResult completing(LoadedIndex state) throws IOException {
		if(completing == null) {
			throw new IllegalStateException(
				"The " + state.spec.name() + " corpus has no autocomplete field; run this"
					+ " benchmark with a corpus that has one"
			);
		}

		return state.index.search(completing);
	}

	private static SearchRequest query(TextQuery text) {
		return SearchRequest.create().withQuery(text).build();
	}

	/**
	 * Misspell a word by swapping the middle two letters, which is a mistake
	 * within the one edit typo tolerance forgives.
	 */
	private static String typo(String word) {
		var middle = word.length() / 2;
		return word.substring(0, middle - 1) + word.charAt(middle)
			+ word.charAt(middle - 1) + word.substring(middle + 1);
	}
}
