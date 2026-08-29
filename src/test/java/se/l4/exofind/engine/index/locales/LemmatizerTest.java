package se.l4.exofind.engine.index.locales;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.core.KeywordTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.util.fst.ByteSequenceOutputs;
import org.junit.jupiter.api.Test;

/**
 * Pins what the lemma lookup does with the data the build ships and without
 * any: a word it holds comes back as the form it is looked up under, and a
 * data set that is not installed leaves the stream alone rather than failing.
 */
public class LemmatizerTest {
	/**
	 * Run a single already-folded word through a data set's lookup, the way
	 * it reaches the filter mid-chain.
	 */
	private List<String> stem(String data, String word) throws IOException {
		var terms = new ArrayList<String>();
		var tokenizer = new KeywordTokenizer();
		tokenizer.setReader(new StringReader(word));

		try(var stream = Lemmatizer.forData(data).stem(tokenizer)) {
			var term = stream.addAttribute(CharTermAttribute.class);
			stream.reset();
			while(stream.incrementToken()) {
				terms.add(term.toString());
			}
			stream.end();
		}

		return terms;
	}

	@Test
	public void testShippedIcelandicHasData() {
		assertThat(Lemmatizer.forData("is").isAvailable(), is(true));
	}

	/**
	 * The shipped lookup is readable as the transducer the engine memory
	 * maps, which is the only form it reads. A transducer written by a
	 * different version of Lucene is not readable, and without one the node
	 * reports `is` as unsupported. Failing here is what turns that into a
	 * build error.
	 */
	@Test
	public void testShippedIcelandicLookupIsAReadableTransducer() throws IOException {
		try(var fst = LocaleData.forName("is")
			.readFst("stemming.fst", ByteSequenceOutputs.getSingleton())
			.orElseThrow(() -> new AssertionError("is should ship stemming.fst"))
		) {
			assertThat(fst.fst().getBytesReader(), is(notNullValue()));
		}
	}

	/**
	 * The forms of one word, none of which a rule would reach from another:
	 * the definite dative of a horse, the plural of a book, the plural of a
	 * man.
	 */
	@Test
	public void testIcelandicAnswersWithTheFormAWordIsLookedUpUnder() throws IOException {
		assertThat(stem("is", "hestinum"), contains("hestur"));
		assertThat(stem("is", "bækur"), contains("bók"));
		assertThat(stem("is", "menn"), contains("maður"));
	}

	/**
	 * A word already in the form it is looked up under is not in the file at
	 * all, and comes back as it went in.
	 */
	@Test
	public void testAWordInItsOwnFormIsUnchanged() throws IOException {
		assertThat(stem("is", "hestur"), contains("hestur"));
	}

	@Test
	public void testAWordTheLookupDoesNotHoldIsUnchanged() throws IOException {
		assertThat(stem("is", "blorkurinn"), contains("blorkurinn"));
	}

	/**
	 * A name for which there is no file loads nothing and passes streams
	 * through untouched.
	 */
	@Test
	public void testMissingDataPassesThrough() throws IOException {
		var lemmatizer = Lemmatizer.forData("no-such-data");
		assertThat(lemmatizer.isAvailable(), is(false));

		var tokenizer = new KeywordTokenizer();
		assertThat(lemmatizer.stem(tokenizer), is(sameInstance(tokenizer)));
	}

	@Test
	public void testTheSameDataIsLoadedOnce() {
		assertThat(Lemmatizer.forData("is"), is(sameInstance(Lemmatizer.forData("is"))));
	}
}
