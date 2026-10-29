package se.l4.exofind.engine.auth;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.time.Instant;
import java.util.Optional;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.junit.jupiter.api.Test;

public class PrincipalTest {
	private static Key key(Grant... grants) {
		return new Key(
			"0123456789abcdef",
			KeySecret.hash("secret"),
			"",
			Lists.immutable.of(grants),
			Instant.EPOCH,
			null
		);
	}

	private static Grant grant(String index, Permission... permissions) {
		return new Grant(Sets.immutable.of(permissions), Lists.immutable.of(index));
	}

	@Test
	void grantsAreUnioned() {
		var principal = Principal.of(
			key(
				grant("books", Permission.SEARCH),
				grant("movies", Permission.DOCUMENTS_WRITE)
			)
		);

		assertThat(principal.allows(Permission.SEARCH, "books"), is(true));
		assertThat(principal.allows(Permission.DOCUMENTS_WRITE, "movies"), is(true));

		// Neither grant crosses over to the other's index
		assertThat(principal.allows(Permission.SEARCH, "movies"), is(false));
		assertThat(principal.allows(Permission.DOCUMENTS_WRITE, "books"), is(false));
	}

	@Test
	void anIndexNoGrantCoversIsNotVisible() {
		var principal = Principal.of(key(grant("books", Permission.SEARCH)));

		assertThat(principal.covers("books"), is(true));
		assertThat(principal.covers("movies"), is(false));
	}

	@Test
	void holdingAPermissionOnAnyIndexIsItsOwnQuestion() {
		var principal = Principal.of(key(grant("books", Permission.INDEXES_READ)));

		assertThat(principal.allowsAny(Permission.INDEXES_READ), is(true));
		assertThat(principal.allowsAny(Permission.INDEXES_WRITE), is(false));
	}

	@Test
	void aGrantOverNoIndexHoldsNothingOnAnyIndex() {
		var principal = Principal.of(
			key(new Grant(Sets.immutable.of(Permission.INDEXES_READ), Lists.immutable.empty()))
		);

		assertThat(principal.allowsAny(Permission.INDEXES_READ), is(false));
	}

	@Test
	void theUncheckedPrincipalIsAllowedEverything() {
		var principal = Principal.unchecked();

		assertThat(principal.allows(Permission.KEYS_WRITE), is(true));
		assertThat(principal.allows(Permission.INDEXES_DELETE, "anything"), is(true));
		assertThat(principal.covers("anything"), is(true));
	}

	@Test
	void theRootPrincipalIsAllowedEverything() {
		var principal = Principal.root();

		assertThat(principal.allows(Permission.KEYS_WRITE), is(true));
		assertThat(principal.allows(Permission.INDEXES_DELETE, "anything"), is(true));
	}

	@Test
	void nobodyIsAllowedNothing() {
		var principal = Principal.none();

		assertThat(principal.allows(Permission.SEARCH, "books"), is(false));
		assertThat(principal.allowsAny(Permission.SEARCH), is(false));
		assertThat(principal.covers("books"), is(false));
	}

	@Test
	void answeringAsAnonymousDropsWhatAnonymousMayNotDo() {
		/*
		 * The key is checked when a node starts, but it lives in storage and can
		 * be changed afterwards from another node - so what it is honoured as
		 * has to be narrowed at every request rather than only at startup.
		 */
		var principal = Principal.anonymous(
			key(grant("books", Permission.SEARCH, Permission.DOCUMENTS_WRITE))
		);

		assertThat(principal.allows(Permission.SEARCH, "books"), is(true));
		assertThat(principal.allows(Permission.DOCUMENTS_WRITE, "books"), is(false));
	}

	@Test
	void aGrantLeftWithNothingAnonymousMayDoIsDropped() {
		var principal = Principal.anonymous(
			key(grant("books", Permission.INDEXES_WRITE))
		);

		assertThat(principal.grants().isEmpty(), is(true));
		assertThat(principal.covers("books"), is(false));
	}

	@Test
	void aCallerHoldsTheGrantsItCarries() {
		var principal = Principal.of(
			key(
				grant("books*", Permission.SEARCH, Permission.INDEXES_READ),
				new Grant(Sets.immutable.of(Permission.KEYS_WRITE), Lists.immutable.empty())
			)
		);

		var held = Lists.immutable.of(
			grant("books@*", Permission.SEARCH),
			new Grant(Sets.immutable.of(Permission.KEYS_WRITE), Lists.immutable.empty())
		);

		assertThat(principal.firstNotHeld(held), is(Optional.empty()));
	}

	@Test
	void aWiderPatternIsNotHeld() {
		var principal = Principal.of(key(grant("books*", Permission.SEARCH)));

		assertThat(
			principal.firstNotHeld(Lists.immutable.of(grant("*", Permission.SEARCH))),
			is(Optional.of(Permission.SEARCH))
		);
	}

	/**
	 * Each pattern is held on its own terms, so a grant over two indexes is held
	 * by two grants that each cover one.
	 */
	@Test
	void patternsOfOneGrantMayBeHeldByDifferentGrants() {
		var principal = Principal.of(
			key(grant("books", Permission.SEARCH), grant("movies", Permission.SEARCH))
		);

		var grant = new Grant(Sets.immutable.of(Permission.SEARCH), Lists.immutable.of("books", "movies"));
		assertThat(principal.firstNotHeld(Lists.immutable.of(grant)), is(Optional.empty()));
	}

	@Test
	void aDeploymentPermissionTheCallerLacksIsNamed() {
		var principal = Principal.of(key(grant("*", Permission.SEARCH)));

		var grant = new Grant(Sets.immutable.of(Permission.KEYS_READ), Lists.immutable.empty());
		assertThat(
			principal.firstNotHeld(Lists.immutable.of(grant)),
			is(Optional.of(Permission.KEYS_READ))
		);
	}

	@Test
	void theRootPrincipalHoldsEverything() {
		var grant = new Grant(Sets.immutable.of(Permission.values()), Lists.immutable.of("*"));

		assertThat(Principal.root().firstNotHeld(Lists.immutable.of(grant)), is(Optional.empty()));
	}
}
