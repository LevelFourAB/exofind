package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

import java.io.IOException;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.DoubleValuesSource;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopScoreDocCollectorManager;
import org.apache.lucene.search.TotalHits;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.eclipse.collections.api.factory.Lists;
import org.junit.jupiter.api.Test;

/**
 * Tests for the query that multiplies signals into scores without giving up
 * skipping: a search allowed to skip what cannot compete has to find exactly
 * what a search that scores everything finds. The corpus is large enough, and
 * the collector's threshold low enough, for skipping to actually happen -
 * which the total is asserted to prove, since a pruned search that quietly
 * scored everything would pass the comparison without testing anything.
 */
public class SignalScoreQueryTest {
	private static final int DOCUMENTS = 5000;
	private static final int PAGE = 20;

	/**
	 * How close two scores have to be to count as the same. Scores are floats
	 * that have been through a multiplication, so they are compared as numbers
	 * rather than for equality.
	 */
	private static final double PRECISION = 0.0001;

	@Test
	public void testSkippingFindsWhatScoringEverythingFinds() throws IOException {
		try(var directory = new ByteBuffersDirectory()) {
			index(directory);

			try(var reader = DirectoryReader.open(directory)) {
				var searcher = new IndexSearcher(reader);
				var query = query();

				var everything = searcher.search(
					query,
					new TopScoreDocCollectorManager(PAGE, Integer.MAX_VALUE)
				);
				var pruned = searcher.search(
					query,
					new TopScoreDocCollectorManager(PAGE, PAGE)
				);

				assertThat(
					"nothing was skipped, so the search this guards is untested",
					pruned.totalHits.value(),
					is(lessThan(everything.totalHits.value()))
				);
				assertThat(
					pruned.totalHits.relation(),
					is(TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO)
				);

				assertThat(pruned.scoreDocs.length, is(everything.scoreDocs.length));
				for(var i = 0; i < everything.scoreDocs.length; i++) {
					assertThat(pruned.scoreDocs[i].doc, is(everything.scoreDocs[i].doc));
					assertThat(
						(double) pruned.scoreDocs[i].score,
						is(closeTo(everything.scoreDocs[i].score, PRECISION))
					);
				}
			}
		}
	}

	@Test
	public void testExplainAnswersTheScore() throws IOException {
		try(var directory = new ByteBuffersDirectory()) {
			index(directory);

			try(var reader = DirectoryReader.open(directory)) {
				var searcher = new IndexSearcher(reader);
				var query = query();

				var top = searcher.search(query, 1).scoreDocs[0];
				var explanation = searcher.explain(query, top.doc);

				assertThat(explanation.isMatch(), is(true));
				assertThat(
					explanation.getValue().doubleValue(),
					is(closeTo(top.score, PRECISION))
				);
			}
		}
	}

	/**
	 * A corpus where how well a document matches and how strong its signal is
	 * pull in different directions, so the right page cannot be found from
	 * either alone. Every fifth document carries no value at all, so skipping
	 * also runs over documents the signal says nothing about.
	 *
	 * <p>Three things make the corpus skippable at all, and losing any of them
	 * turns the guard on the total red: the term frequency runs in stretches
	 * far longer than a postings block, so whole blocks have a low ceiling
	 * rather than a strong document in every one; the strong stretches come
	 * first, so the page fills with scores the rest of the corpus has to beat;
	 * and every document is padded to the same length, so the length norm does
	 * not press the scores into a band narrower than the signal's own slack.
	 */
	private void index(ByteBuffersDirectory directory) throws IOException {
		try(var writer = new IndexWriter(directory, new IndexWriterConfig())) {
			for(var i = 0; i < DOCUMENTS; i++) {
				var frequency = (DOCUMENTS - 1 - i) / 400 + 1;

				var document = new Document();
				document.add(new TextField(
					"text",
					"term ".repeat(frequency) + "filler ".repeat(14 - frequency),
					Field.Store.NO
				));

				if(i % 5 != 0) {
					document.add(new NumericDocValuesField("purchases", (i * 37) % 1000));
				}

				writer.addDocument(document);
			}
		}
	}

	private SignalScoreQuery query() {
		return new SignalScoreQuery(
			new TermQuery(new Term("text", "term")),
			RankingSignals.of(Lists.immutable.of(
				new RankingSignals.Applied(
					"purchases",
					DoubleValuesSource.fromLongField("purchases"),
					new RankingSignals.Saturation(50),
					1f
				)
			))
		);
	}
}
