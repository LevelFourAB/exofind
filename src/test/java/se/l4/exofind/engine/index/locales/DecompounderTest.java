package se.l4.exofind.engine.index.locales;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.core.KeywordTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.decompound.Decompounder;

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
		for(var tag : List.of("da", "de", "nl", "sv", "no", "nb", "nn")) {
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
