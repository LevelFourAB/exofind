package se.l4.exofind.engine.errors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

/**
 * The form {@link ObjectLocation} writes a path in, which the API answers with
 * as the {@code path} of an error.
 */
public class ObjectLocationTest {
	@Test
	public void testTheRootIsEmpty() {
		assertThat(ObjectLocation.root().describe(), is(""));
	}

	@Test
	public void testNamesJoinWithADot() {
		assertThat(
			ObjectLocation.root().forField("fields").forField("title").describe(),
			is("fields.title")
		);
	}

	@Test
	public void testAnElementOfAListCarriesItsIndex() {
		assertThat(
			ObjectLocation.root().forField("documents").forIndex(2).forField("name").describe(),
			is("documents[2].name")
		);
	}

	@Test
	public void testAPlainKeyReadsAsAName() {
		assertThat(
			ObjectLocation.root().forField("metadata").forKey("owner").describe(),
			is("metadata.owner")
		);
	}

	/**
	 * A key holding a dot would read as two names without the brackets, and a
	 * client reading the path would look for a field that does not exist.
	 */
	@Test
	public void testAKeyHoldingADotIsQuoted() {
		assertThat(
			ObjectLocation.root().forField("metadata").forKey("build.sha").describe(),
			is("metadata[\"build.sha\"]")
		);
	}

	@Test
	public void testAKeyHoldingABracketIsQuoted() {
		assertThat(
			ObjectLocation.root().forField("metadata").forKey("a[0]").describe(),
			is("metadata[\"a[0]\"]")
		);
	}

	@Test
	public void testAKeyHoldingAQuoteIsEscaped() {
		assertThat(
			ObjectLocation.root().forField("metadata").forKey("say \"hi\"").describe(),
			is("metadata[\"say \\\"hi\\\"\"]")
		);
	}

	@Test
	public void testAKeyHoldingABackslashIsEscaped() {
		assertThat(
			ObjectLocation.root().forField("metadata").forKey("a\\b").describe(),
			is("metadata[\"a\\\\b\"]")
		);
	}

	/**
	 * A wildcard field name is one segment, so a definition keyed by one reports
	 * at the name it was written with.
	 */
	@Test
	public void testAWildcardNameStandsAsItIs() {
		assertThat(
			ObjectLocation.root().forField("fields").forKey("title_*").describe(),
			is("fields.title_*")
		);
	}
}
