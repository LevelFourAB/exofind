package se.l4.exofind.engine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.InMemoryRegistryStorage;
import se.l4.exofind.engine.index.state.RecordingIndexRemovals;
import se.l4.exofind.engine.storage.StorageMode;

/**
 * What the sweep removes: marked storage the registry does not name, once
 * the mark is older than the grace period - and nothing else.
 */
public class IndexRemovalSweeperTest {
	private static final Duration GRACE = Duration.ofHours(1);

	IndexRegistry registry;
	RecordingIndexRemovals removals;
	Instant now;

	@BeforeEach
	void setup() {
		registry = new IndexRegistry(new InMemoryRegistryStorage(), Duration.ofMinutes(5));
		registry.create("books", "1");
		registry.refresh();

		removals = new RecordingIndexRemovals();
		now = Instant.now();
	}

	private IndexRemovalSweeper newSweeper(IndexRegistry registry) {
		var nodeState = new NodeState(true);
		nodeState.updateOwnership(true);

		return new IndexRemovalSweeper(
			registry,
			removals,
			nodeState,
			StorageMode.OBJECT,
			GRACE,
			Duration.ofMinutes(10)
		);
	}

	@Test
	public void testOldMarkOverUnregisteredIndexIsRemoved() {
		removals.marks.put(IndexName.of("movies"), now.minus(GRACE.multipliedBy(2)));

		newSweeper(registry).pass();

		assertThat(removals.removed, contains(IndexName.of("movies")));
		assertThat(removals.marks, not(hasKey(IndexName.of("movies"))));
	}

	/**
	 * The grace period is the undo window, so a mark younger than it is
	 * left however unregistered its index is.
	 */
	@Test
	public void testYoungMarkIsLeftAlone() {
		removals.marks.put(IndexName.of("movies"), now.minus(Duration.ofMinutes(10)));

		newSweeper(registry).pass();

		assertThat(removals.removed, emptyIterable());
		assertThat(removals.marks, hasKey(IndexName.of("movies")));
	}

	/**
	 * A mark over something the registry names is void: the name is in use,
	 * and whatever left the mark did not take the name out of the registry.
	 */
	@Test
	public void testMarkOverRegisteredNameIsLeftAlone() {
		var old = now.minus(GRACE.multipliedBy(2));
		removals.marks.put(IndexName.of("books"), old);
		removals.marks.put(IndexName.of("books", "1"), old);
		removals.marks.put(IndexName.of("books", "2"), old);

		newSweeper(registry).pass();

		// Only the generation the registry does not name goes
		assertThat(removals.removed, contains(IndexName.of("books", "2")));
		assertThat(removals.marks, hasKey(IndexName.of("books")));
		assertThat(removals.marks, hasKey(IndexName.of("books", "1")));
	}

	/**
	 * To a node that has not read the registry every index looks
	 * unregistered, so such a node removes nothing.
	 */
	@Test
	public void testNothingIsRemovedBeforeTheRegistryWasRead() {
		var unread = new IndexRegistry(new InMemoryRegistryStorage(), Duration.ofMinutes(5));
		removals.marks.put(IndexName.of("movies"), now.minus(GRACE.multipliedBy(2)));

		newSweeper(unread).pass();

		assertThat(removals.removed, emptyIterable());
	}

	/**
	 * A removal that stopped because its mark went away is not an error,
	 * and what it left is not tried again in the same pass.
	 */
	@Test
	public void testStoppedRemovalIsLeftForALaterPass() {
		removals.marks.put(IndexName.of("movies"), now.minus(GRACE.multipliedBy(2)));
		removals.stopRemoval = true;

		newSweeper(registry).pass();

		assertThat(removals.removed, contains(IndexName.of("movies")));
		assertThat(removals.marks.size(), is(1));
	}
}
