package se.l4.exofind.engine.auth;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.junit.jupiter.api.Test;

public class GrantTest {
	private static Grant grant(Permission permission, String... indexes) {
		return new Grant(Sets.immutable.of(permission), Lists.immutable.of(indexes));
	}

	@Test
	void anExactPatternCoversOnlyThatIndex() {
		var grant = grant(Permission.SEARCH, "books");

		assertThat(grant.allows(Permission.SEARCH, "books"), is(true));
		assertThat(grant.allows(Permission.SEARCH, "book"), is(false));
		assertThat(grant.allows(Permission.SEARCH, "books2"), is(false));
	}

	@Test
	void aTrailingStarCoversThePrefix() {
		var grant = grant(Permission.SEARCH, "books-");

		assertThat(grant.allows(Permission.SEARCH, "books-en"), is(false));

		var wildcard = grant(Permission.SEARCH, "books-*");

		assertThat(wildcard.allows(Permission.SEARCH, "books-en"), is(true));
		assertThat(wildcard.allows(Permission.SEARCH, "books-"), is(true));
		assertThat(wildcard.allows(Permission.SEARCH, "movies-en"), is(false));
	}

	/**
	 * The key an application holds names the index and reaches no generation of
	 * it, so a rollout happens under it without it being able to address - or
	 * even see - the generations it moves between.
	 */
	@Test
	void anIndexPatternDoesNotCoverItsGenerations() {
		var grant = grant(Permission.SEARCH, "books");

		assertThat(grant.allows(Permission.SEARCH, "books"), is(true));
		assertThat(grant.allows(Permission.SEARCH, "books@2"), is(false));
	}

	/**
	 * The separator appears in no name, so a pattern reaching the generations of
	 * one index can never run past it into another.
	 */
	@Test
	void aGenerationPatternCoversOneIndexAlone() {
		var grant = grant(Permission.INDEXES_PROMOTE, "books@*");

		assertThat(grant.allows(Permission.INDEXES_PROMOTE, "books@2"), is(true));
		assertThat(grant.allows(Permission.INDEXES_PROMOTE, "books"), is(false));
		assertThat(grant.allows(Permission.INDEXES_PROMOTE, "books2@1"), is(false));
		assertThat(grant.allows(Permission.INDEXES_PROMOTE, "movies@1"), is(false));
	}

	@Test
	void aStarOnItsOwnCoversEveryIndex() {
		var grant = grant(Permission.SEARCH, "*");

		assertThat(grant.allows(Permission.SEARCH, "books"), is(true));
		assertThat(grant.allows(Permission.SEARCH, ""), is(true));
	}

	@Test
	void aStarAnywhereElseCoversNothing() {
		/*
		 * Nothing creates such a pattern, but a store written by hand could
		 * hold one, and reading it as a wildcard would reach further than
		 * whoever wrote it meant.
		 */
		var grant = grant(Permission.SEARCH, "bo*ks");

		assertThat(grant.allows(Permission.SEARCH, "books"), is(false));
		assertThat(grant.allows(Permission.SEARCH, "bo*ks"), is(false));
	}

	@Test
	void noPatternsCoverNoIndex() {
		var grant = grant(Permission.SEARCH);

		assertThat(grant.allows(Permission.SEARCH, "books"), is(false));
		assertThat(grant.covers("books"), is(false));
	}

	@Test
	void aPermissionThatNamesNoIndexIgnoresThePatterns() {
		var grant = grant(Permission.KEYS_WRITE);

		assertThat(grant.allows(Permission.KEYS_WRITE), is(true));
		assertThat(grant.allows(Permission.KEYS_WRITE, "books"), is(true));
	}

	@Test
	void aPermissionThatNamesAnIndexIsNeverAllowedWithoutOne() {
		var grant = grant(Permission.SEARCH, "*");

		assertThat(grant.allows(Permission.SEARCH), is(false));
	}

	@Test
	void coveringAnIndexIsSeparateFromBeingAllowedOnIt() {
		var grant = grant(Permission.SEARCH, "books");

		assertThat(grant.covers("books"), is(true));
		assertThat(grant.allows(Permission.DOCUMENTS_WRITE, "books"), is(false));
	}
}
