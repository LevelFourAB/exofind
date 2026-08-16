package se.l4.exofind.engine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

public class NodeStateTest {
	@Test
	void testCandidateIsNotIndexerUntilGranted() {
		var state = new NodeState(true);
		assertThat(state.isIndexerCandidate(), is(true));
		assertThat(state.isIndexer(), is(false));

		state.updateOwnership(true);
		assertThat(state.isIndexer(), is(true));

		state.updateOwnership(false);
		assertThat(state.isIndexer(), is(false));
	}

	/**
	 * Being granted the role means nothing to a node that was never allowed
	 * to index, whatever grants it is talking to the wrong node.
	 */
	@Test
	void testNonCandidateIsNeverIndexer() {
		var state = new NodeState(false);
		state.updateOwnership(true);

		assertThat(state.isIndexer(), is(false));
	}

	/**
	 * Listeners hear about every change to the answer of isIndexer, and only
	 * about changes - granting a role that is already held is not news.
	 */
	@Test
	void testListenersAreNotifiedOfChanges() {
		var state = new NodeState(true);
		List<Boolean> seen = new ArrayList<>();
		state.addListener(s -> seen.add(s.isIndexer()));

		state.updateOwnership(true);
		state.updateOwnership(true);
		state.updateOwnership(false);

		assertThat(seen, contains(true, false));
	}

	@Test
	void testRemovedListenerIsNotNotified() {
		var state = new NodeState(true);
		List<Boolean> seen = new ArrayList<>();
		NodeState.Listener listener = s -> seen.add(s.isIndexer());

		state.addListener(listener);
		state.removeListener(listener);
		state.updateOwnership(true);

		assertThat(seen, is(List.of()));
	}

	/**
	 * Removing a listener that was never added must leave the registered
	 * ones alone.
	 */
	@Test
	void testRemovingUnknownListenerChangesNothing() {
		var state = new NodeState(true);
		List<Boolean> seen = new ArrayList<>();
		state.addListener(s -> seen.add(s.isIndexer()));

		state.removeListener(s -> {
		});
		state.updateOwnership(true);

		assertThat(seen, contains(true));
	}
}
