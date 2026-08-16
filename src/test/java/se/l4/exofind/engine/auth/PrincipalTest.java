package se.l4.exofind.engine.auth;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.time.Instant;

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
}
