package se.l4.exofind.engine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

public class NodeStateTest {
	@Test
	void testCandidateWritesNothingUntilGranted() {
		var state = new NodeState(true);
		assertThat(state.isIndexerCandidate(), is(true));
		assertThat(state.isIndexer("books"), is(false));

		state.updateOwnership("books", true);
		assertThat(state.isIndexer("books"), is(true));
		assertThat(state.isIndexer("games"), is(false));

		state.updateOwnership("books", false);
		assertThat(state.isIndexer("books"), is(false));
	}

	/**
	 * Granting everything at once is how a node that can never be contested
	 * holds indexes without naming them, the ones created later included.
	 */
	@Test
	void testGrantingEverythingCoversAnyName() {
		var state = new NodeState(true);
		state.updateOwnership(true);

		assertThat(state.isIndexer("books"), is(true));
		assertThat(state.isIndexer("never-heard-of"), is(true));

		state.updateOwnership(false);
		assertThat(state.isIndexer("books"), is(false));
	}

	/**
	 * Being granted an index means nothing to a node that was never allowed
	 * to index, whatever grants it is talking to the wrong node.
	 */
	@Test
	void testNonCandidateIsNeverIndexer() {
		var state = new NodeState(false);
		state.updateOwnership(true);
		state.updateOwnership("books", true);

		assertThat(state.isIndexer("books"), is(false));
	}

	/**
	 * Listeners hear about every change to the answer of isIndexer, and only
	 * about changes - granting an index that is already held is not news.
	 */
	@Test
	void testListenersAreNotifiedOfChanges() {
		var state = new NodeState(true);
		List<String> seen = new ArrayList<>();
		state.addListener((s, index) -> seen.add(index + "=" + s.isIndexer(index)));

		state.updateOwnership("books", true);
		state.updateOwnership("books", true);
		state.updateOwnership("games", true);
		state.updateOwnership("books", false);

		assertThat(seen, contains("books=true", "games=true", "books=false"));
	}

	/**
	 * A change to everything at once carries no index name - which indexes it
	 * touches is every one there is.
	 */
	@Test
	void testGrantingEverythingNotifiesWithoutAName() {
		var state = new NodeState(true);
		List<String> seen = new ArrayList<>();
		state.addListener((s, index) -> seen.add(String.valueOf(index)));

		state.updateOwnership(true);

		assertThat(seen, contains("null"));
	}

	/**
	 * A revoked index reaches listeners through the revoked path, which is
	 * what tells them nothing the index holds may be pushed - a listener that
	 * does not tell the two apart hears it as an ordinary loss.
	 */
	@Test
	void testRevokingNotifiesTheRevokedPath() {
		var state = new NodeState(true);
		List<String> seen = new ArrayList<>();
		state.addListener(new NodeState.Listener() {
			@Override
			public void onOwnershipChanged(NodeState s, String index) {
				seen.add("changed:" + index);
			}

			@Override
			public void onOwnershipRevoked(NodeState s, String index) {
				seen.add("revoked:" + index);
			}
		});

		state.updateOwnership("books", true);
		state.revokeOwnership("books");
		state.revokeOwnership("books");
		state.revokeOwnership("never-held");

		assertThat(state.isIndexer("books"), is(false));
		assertThat(seen, contains("changed:books", "revoked:books"));
	}

	@Test
	void testRevokedFallsBackToChangedForPlainListeners() {
		var state = new NodeState(true);
		List<String> seen = new ArrayList<>();
		state.addListener((s, index) -> seen.add(index + "=" + s.isIndexer(index)));

		state.updateOwnership("books", true);
		state.revokeOwnership("books");

		assertThat(seen, contains("books=true", "books=false"));
	}

	@Test
	void testRemovedListenerIsNotNotified() {
		var state = new NodeState(true);
		List<String> seen = new ArrayList<>();
		NodeState.Listener listener = (s, index) -> seen.add(index);

		state.addListener(listener);
		state.removeListener(listener);
		state.updateOwnership("books", true);

		assertThat(seen, is(List.of()));
	}

	/**
	 * Removing a listener that was never added must leave the registered
	 * ones alone.
	 */
	@Test
	void testRemovingUnknownListenerChangesNothing() {
		var state = new NodeState(true);
		List<String> seen = new ArrayList<>();
		state.addListener((s, index) -> seen.add(index));

		state.removeListener((s, index) -> {
		});
		state.updateOwnership("books", true);

		assertThat(seen, contains("books"));
	}
}
