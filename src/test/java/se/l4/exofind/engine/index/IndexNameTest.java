package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.errors.ValidationException;

public class IndexNameTest {
	@Test
	public void testIndexAloneMeansTheLiveGeneration() {
		var name = IndexName.parse("books");

		assertThat(name.index(), is("books"));
		assertThat(name.generation(), is((String) null));
		assertThat(name.isPinned(), is(false));
		assertThat(name.toString(), is("books"));
	}

	@Test
	public void testGenerationIsNamedAfterTheSeparator() {
		var name = IndexName.parse("books@2");

		assertThat(name.index(), is("books"));
		assertThat(name.generation(), is("2"));
		assertThat(name.isPinned(), is(true));
		assertThat(name.toString(), is("books@2"));
	}

	/**
	 * The separator is what makes a full name take apart the same way every
	 * time, so a name carrying two of them is refused rather than read as one
	 * of the several things it could mean.
	 */
	@Test
	public void testSecondSeparatorIsRefused() {
		assertThrows(ValidationException.class, () -> IndexName.parse("books@2@3"));
	}

	@Test
	public void testNameThatCouldNotBeADirectoryIsRefused() {
		assertThrows(ValidationException.class, () -> IndexName.parse("../escaped"));
		assertThrows(ValidationException.class, () -> IndexName.parse("Books"));
		assertThrows(ValidationException.class, () -> IndexName.parse("-books"));
		assertThrows(ValidationException.class, () -> IndexName.parse(""));
		assertThrows(ValidationException.class, () -> IndexName.parse(null));
	}

	@Test
	public void testGenerationThatCouldNotBeADirectoryIsRefused() {
		assertThrows(ValidationException.class, () -> IndexName.parse("books@"));
		assertThrows(ValidationException.class, () -> IndexName.parse("books@../escaped"));
		assertThrows(ValidationException.class, () -> IndexName.parse("books@V2"));
	}

	/**
	 * Names that come from disk or from storage are passed over rather than
	 * answered with an error, as there is no caller to tell.
	 */
	@Test
	public void testUnusableNameIsPassedOverWhenItDidNotComeFromACaller() {
		assertThat(IndexName.tryParse("../escaped"), is(Optional.empty()));
		assertThat(IndexName.tryParse("books@2").orElseThrow().generation(), is("2"));
	}

	@Test
	public void testGenerationIsAddedToAndTakenFromAName() {
		assertThat(IndexName.parse("books").withGeneration("3").toString(), is("books@3"));
		assertThat(IndexName.parse("books@3").withoutGeneration().toString(), is("books"));
	}
}
