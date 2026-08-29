package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.errors.ValidationException;

/**
 * Tests for taking apart the path that one change of a patch names.
 */
public class DocumentPathTest {
	@Test
	public void aNameOnItsOwnIsTheWholeField() {
		var path = DocumentPath.parse("price");

		assertThat(path.field(), is("price"));
		assertThat(path.selectorField(), is(nullValue()));
		assertThat(path.selectorValue(), is(nullValue()));
		assertThat(path.inner(), is(nullValue()));
	}

	/**
	 * Without brackets there is nothing to say where a field name ends and a
	 * path through an object begins, so the whole name is carried as it was
	 * written and the definition decides.
	 */
	@Test
	public void aDottedNameWithoutBracketsIsCarriedWhole() {
		var path = DocumentPath.parse("dimensions.width");

		assertThat(path.field(), is("dimensions.width"));
		assertThat(path.inner(), is(nullValue()));
	}

	@Test
	public void bracketsHoldingNothingSayAValueIsAdded() {
		var path = DocumentPath.parse("variants[]");

		assertThat(path.field(), is("variants"));
		assertThat(path.selectorField(), is(nullValue()));
		assertThat(path.selectorValue(), is(""));
		assertThat(path.inner(), is(nullValue()));
	}

	/**
	 * A selector with no {@code =} is one word, and which of the two things a
	 * word can mean is left to the field it was given to.
	 */
	@Test
	public void aSelectorWithoutAnEqualsIsOneWord() {
		var path = DocumentPath.parse("title[sv]");

		assertThat(path.field(), is("title"));
		assertThat(path.selectorField(), is(nullValue()));
		assertThat(path.selectorValue(), is("sv"));
	}

	@Test
	public void anEqualsSplitsASelectorIntoAFieldAndWhatItReads() {
		var path = DocumentPath.parse("variants[sku=V-2].price");

		assertThat(path.field(), is("variants"));
		assertThat(path.selectorField(), is("sku"));
		assertThat(path.selectorValue(), is("V-2"));
		assertThat(path.inner(), is("price"));
	}

	/**
	 * Only the first splits, so a value is free to hold as many as it likes.
	 */
	@Test
	public void aSecondEqualsBelongsToTheValue() {
		var path = DocumentPath.parse("variants[sku=a=b]");

		assertThat(path.selectorField(), is("sku"));
		assertThat(path.selectorValue(), is("a=b"));
	}

	/**
	 * Nothing before the {@code =} is not a field name, so the selector stays
	 * one word rather than becoming an empty name.
	 */
	@Test
	public void anEqualsAtTheStartIsPartOfTheWord() {
		var path = DocumentPath.parse("variants[=V-2]");

		assertThat(path.selectorField(), is(nullValue()));
		assertThat(path.selectorValue(), is("=V-2"));
	}

	@Test
	public void aBackslashStandsForTheCharacterAfterIt() {
		var path = DocumentPath.parse("variants[sku=a\\]b].price");

		assertThat(path.selectorField(), is("sku"));
		assertThat(path.selectorValue(), is("a]b"));
		assertThat(path.inner(), is("price"));
	}

	/**
	 * An escaped {@code =} is text, which is what lets one word hold one.
	 */
	@Test
	public void anEscapedEqualsDoesNotSplit() {
		var path = DocumentPath.parse("variants[a\\=b]");

		assertThat(path.selectorField(), is(nullValue()));
		assertThat(path.selectorValue(), is("a=b"));
	}

	@Test
	public void aPathIsWrittenBackTheWayItWasGiven() {
		for(var text : new String[] {
			"price",
			"dimensions.width",
			"variants[]",
			"title[sv]",
			"variants[V-2]",
			"variants[V-2].price",
			"variants[a\\=b]",
			"variants[sku=V-2].price",
			"variants[sku=a=b]",
			"variants[sku=a\\]b].price"
		}) {
			assertThat(DocumentPath.parse(text).toString(), is(text));
		}
	}

	@Test
	public void aBracketThatIsNeverClosedIsRefused() {
		assertThat(codeOfRefusing("variants[sku=V-2"), is("request:update:path_invalid"));
	}

	@Test
	public void aSelectorWithNoFieldBeforeItIsRefused() {
		assertThat(codeOfRefusing("[sku=V-2]"), is("request:update:path_invalid"));
	}

	@Test
	public void anythingButAFieldAfterTheBracketsIsRefused() {
		assertThat(codeOfRefusing("variants[sku=V-2]price"), is("request:update:path_invalid"));
	}

	@Test
	public void aSecondSelectorIsRefused() {
		assertThat(
			codeOfRefusing("variants[sku=V-2].price[sv]"),
			is("request:update:path_invalid")
		);
	}

	@Test
	public void anEmptyPathIsRefused() {
		assertThat(codeOfRefusing(""), is("request:update:path_invalid"));
	}

	private static String codeOfRefusing(String text) {
		var e = assertThrows(ValidationException.class, () -> DocumentPath.parse(text));
		return e.getErrors().get(0).getCode();
	}
}
