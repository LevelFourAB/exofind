package se.l4.exofind.engine.api.v1alpha1.admin;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.api.auth.AuthContext;
import se.l4.exofind.engine.auth.Grant;
import se.l4.exofind.engine.auth.Permission;
import se.l4.exofind.engine.auth.Principal;
import se.l4.exofind.engine.index.state.IndexerLeadershipUnreadableException;
import se.l4.exofind.engine.index.state.IndexerOwnership;
import se.l4.exofind.engine.index.state.LocalIndexerOwnership;

public class IndexerResourceTest {
	Instant expiry;
	IndexerOwnership ownership;
	AuthContext auth;
	IndexerResource resource;

	@BeforeEach
	void setup() {
		expiry = Instant.parse("2026-08-21T12:00:30Z");

		ownership = mock(IndexerOwnership.class);
		when(ownership.overview()).thenReturn(Optional.of(new IndexerOwnership.Overview(
			List.of(
				new IndexerOwnership.Candidate("node-1", Optional.of("http://node-1:8080"), expiry),
				new IndexerOwnership.Candidate("node-2", Optional.empty(), expiry)
			),
			List.of(
				new IndexerOwnership.Claim("books", "node-1", Optional.of("http://node-1:8080"), expiry),
				new IndexerOwnership.Claim("events", "node-2", Optional.empty(), expiry)
			)
		)));

		auth = new AuthContext();
		auth.set(Principal.unchecked());

		resource = new IndexerResource(ownership, auth);
	}

	@Test
	public void testListsCandidatesAndClaims() {
		var response = resource.list();

		assertThat(response.candidates().size(), is(2));
		assertThat(response.candidates().get(0).node(), is("node-1"));
		assertThat(response.candidates().get(0).address(), is("http://node-1:8080"));
		assertThat(response.candidates().get(0).expiresAt(), is("2026-08-21T12:00:30Z"));
		assertThat(response.candidates().get(1).node(), is("node-2"));
		assertThat(response.candidates().get(1).address(), is((String) null));

		assertThat(response.claims().size(), is(2));
		assertThat(response.claims().get(0).index(), is("books"));
		assertThat(response.claims().get(0).node(), is("node-1"));
		assertThat(response.claims().get(0).address(), is("http://node-1:8080"));
		assertThat(response.claims().get(0).expiresAt(), is("2026-08-21T12:00:30Z"));
		assertThat(response.claims().get(1).index(), is("events"));
	}

	/**
	 * A claim on an index the key was granted nothing on is left out, the
	 * same way listing the indexes leaves the index out.
	 */
	@Test
	public void testClaimsAreNarrowedToWhatTheKeySees() {
		auth.set(new Principal(
			"limited",
			Lists.immutable.of(new Grant(
				Sets.immutable.of(Permission.INDEXES_READ),
				Lists.immutable.of("books")
			)),
			false
		));

		var response = resource.list();

		assertThat(response.candidates().size(), is(2));
		assertThat(response.claims().size(), is(1));
		assertThat(response.claims().get(0).index(), is("books"));
	}

	/**
	 * Not knowing who writes is refused rather than answered as nobody
	 * writing anything.
	 */
	@Test
	public void testAnUnreadableTableIsRefusedNotEmpty() {
		when(ownership.overview()).thenReturn(Optional.empty());

		assertThrows(IndexerLeadershipUnreadableException.class, () -> resource.list());
	}

	/**
	 * A node storing locally knows the answer - there are no other nodes -
	 * and answers with nothing in either list rather than refusing.
	 */
	@Test
	public void testStoringLocallyAnswersEmpty() {
		var local = new IndexerResource(new LocalIndexerOwnership(), auth);
		var response = local.list();

		assertThat(response.candidates(), is(empty()));
		assertThat(response.claims(), is(empty()));
	}
}
