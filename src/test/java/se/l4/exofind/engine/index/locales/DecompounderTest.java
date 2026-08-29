package se.l4.exofind.engine.index.locales;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.core.KeywordTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.util.fst.NoOutputs;
import org.junit.jupiter.api.Test;

/**
 * Pins the decompounding data the build ships: every locale registered with
 * data has to actually split its language's words, so a missing or broken
 * file fails here instead of quietly indexing compounds whole.
 */
public class DecompounderTest {
	/**
	 * Run a single already-folded word through a locale's decompounding, the
	 * way it reaches the filter mid-chain.
	 */
	private List<String> parts(String locale, String word) throws IOException {
		var support = Locales.get(locale).orElseThrow();

		var terms = new ArrayList<String>();
		var tokenizer = new KeywordTokenizer();
		tokenizer.setReader(new StringReader(word));

		try(var stream = support.decompound(tokenizer)) {
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
	public void testShippedLocalesHaveData() {
		for(var tag : List.of("da", "de", "is", "nl", "sv", "no", "nb", "nn")) {
			assertThat(
				tag + " should decompound",
				Locales.get(tag).orElseThrow().isDecompoundingSupported(),
				is(true)
			);
		}
	}

	@Test
	public void testDanish() throws IOException {
		assertThat(
			parts("da", "regnjakke"),
			hasItems("regnjakke", "regn", "jakke")
		);
	}

	@Test
	public void testGerman() throws IOException {
		assertThat(
			parts("de", "winterjacke"),
			hasItems("winterjacke", "winter", "jacke")
		);
	}

	/**
	 * Icelandic joins a compound at a genitive rather than at a bare stem -
	 * tölva enters tölvupóstur as tölvu - so the word list holds the genitive
	 * forms and the parts come out in them. The stemming that follows the
	 * split is what brings them back to the form they are looked up under.
	 */
	@Test
	public void testIcelandic() throws IOException {
		assertThat(
			parts("is", "tölvupóstur"),
			hasItems("tölvupóstur", "tölvu", "póstur")
		);
		assertThat(
			parts("is", "barnabókasafn"),
			hasItems("barnabókasafn", "barna", "safn")
		);
	}

	@Test
	public void testDutch() throws IOException {
		assertThat(
			parts("nl", "regenjas"),
			hasItems("regenjas", "regen", "jas")
		);
	}

	@Test
	public void testSwedish() throws IOException {
		assertThat(
			parts("sv", "regnjacka"),
			hasItems("regnjacka", "regn", "jacka")
		);
	}

	@Test
	public void testNorwegian() throws IOException {
		assertThat(
			parts("no", "regnjakke"),
			hasItems("regnjakke", "regn", "jakke")
		);
		assertThat(
			parts("nb", "regnjakke"),
			hasItems("regnjakke", "regn", "jakke")
		);
		assertThat(
			parts("nn", "regnjakke"),
			hasItems("regnjakke", "regn", "jakke")
		);
	}

	/**
	 * The German linking s: the span the grammar finds is `arbeits`, which
	 * only matches the list with the binding letter shaved off.
	 */
	@Test
	public void testLinkingLettersAreShavedOff() throws IOException {
		assertThat(
			parts("de", "arbeitszimmer"),
			hasItems("arbeitszimmer", "zimmer")
		);
	}

	/**
	 * Every shipped word list is readable as the transducer the engine memory
	 * maps, which is the only form it reads. A transducer written by a
	 * different version of Lucene is not readable, and a locale without one
	 * stops splitting compounds and indexes them whole rather than failing.
	 * Failing here is what turns that into a build error.
	 */
	@Test
	public void testShippedWordListsAreReadableTransducers() throws IOException {
		for(var data : List.of("da", "de", "is", "nb", "nl", "nn", "sv")) {
			try(var fst = LocaleData.forName(data)
				.readFst("words.fst", NoOutputs.getSingleton())
				.orElseThrow(() -> new AssertionError(data + " should ship words.fst"))
			) {
				assertThat(data + " should hold words", fst.fst().getBytesReader(), is(notNullValue()));
			}
		}
	}

	/**
	 * A name for which the classpath holds no files loads nothing and passes
	 * streams through untouched.
	 */
	@Test
	public void testMissingDataPassesThrough() throws IOException {
		var decompounder = Decompounder.forData("missing");
		assertThat(decompounder.isAvailable(), is(false));

		var tokenizer = new KeywordTokenizer();
		assertThat(decompounder.decompound(tokenizer), is(sameInstance(tokenizer)));
	}
}
