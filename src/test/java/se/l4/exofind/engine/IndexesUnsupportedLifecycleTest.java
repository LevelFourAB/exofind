package se.l4.exofind.engine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.IndexUnsupportedException;
import se.l4.exofind.engine.index.registry.GenerationEntry;
import se.l4.exofind.engine.index.registry.InMemoryRegistryStorage;
import se.l4.exofind.engine.index.registry.IndexEntry;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.IndexRegistryStore;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.state.IndexRemovals;
import se.l4.exofind.engine.index.state.NoopSyncProvider;
import se.l4.exofind.engine.index.state.RecordingIndexRemovals;
import se.l4.exofind.engine.index.state.StateSyncProvider;
import se.l4.exofind.engine.storage.StorageMode;

/**
 * Tests of the index lifecycle in {@link Indexes} for an index whose registry
 * entry needs features this build does not have.
 */
public class IndexesUnsupportedLifecycleTest {
	@TempDir
	Path storageDirectory;

	private static NodeState nodeState(boolean indexer) {
		var state = new NodeState(indexer);
		state.updateOwnership(indexer);
		return state;
	}

	private static Indexes newNode(
		Path directory,
		IndexRegistry registry,
		IndexRemovals removals
	) throws IOException {
		return newNode(directory, registry, removals, new NoopSyncProvider(), Duration.ofMinutes(5));
	}

	private static Indexes newNode(
		Path directory,
		IndexRegistry registry,
		IndexRemovals removals,
		StateSyncProvider syncProvider,
		Duration refreshInterval
	) throws IOException {
		return new Indexes(
			nodeState(true),
			syncProvider,
			registry,
			new RegistryHints(registry, StorageMode.LOCAL),
			removals,
			directory,
			OptionalInt.empty(),
			refreshInterval,
			Duration.ofMinutes(10),
			4,
			Duration.ofMillis(100),
			0,
			Duration.ZERO,
			Optional.empty(),
			Optional.empty(),
			Duration.ofHours(24),
			Duration.ofHours(168),
			Duration.ofHours(1)
		);
	}

	/**
	 * A registry that holds {@code books} with two generations, the first
	 * live, and a feature this build does not know.
	 */
	private static InMemoryRegistryStorage unsupportedBooks() {
		var storage = new InMemoryRegistryStorage();
		storage.set(
			IndexRegistryStore.newBuilder()
				.addIndexes(
					IndexEntry.newBuilder()
						.setName("books")
						.addGenerations(GenerationEntry.newBuilder().setName("1"))
						.addGenerations(GenerationEntry.newBuilder().setName("2"))
						.setLive("1")
						.addRequiredFeatures("generations.written-to")
				)
				.build()
		);
		return storage;
	}

	/**
	 * Promoting a generation changes what the entry means, and this build
	 * does not know what the missing feature changes, so the promote is
	 * refused before the registry changes.
	 */
	@Test
	public void promoteOfAnIndexThisBuildDoesNotSupportIsRefusedBeforeTheRegistryChanges() throws IOException {
		// Given
		var storage = unsupportedBooks();
		var registry = new IndexRegistry(storage, Duration.ofMinutes(5));
		var node = newNode(storageDirectory.resolve("a"), registry, new RecordingIndexRemovals());
		try {
			node.refresh();

			// When
			assertThrows(IndexUnsupportedException.class, () -> node.promote("books@2"));

			// Then
			assertThat(storage.stored().getIndexes(0).getLive(), is("1"));
		} finally {
			node.close();
		}
	}

	@Test
	public void deleteOfAGenerationOfAnIndexThisBuildDoesNotSupportIsRefused() throws IOException {
		// Given
		var storage = unsupportedBooks();
		var registry = new IndexRegistry(storage, Duration.ofMinutes(5));
		var removals = new RecordingIndexRemovals();
		var node = newNode(storageDirectory.resolve("a"), registry, removals);
		try {
			node.refresh();

			// When
			assertThrows(IndexUnsupportedException.class, () -> node.delete("books@2"));

			// Then
			assertThat(storage.stored().getIndexes(0).getGenerationsCount(), is(2));
			assertThat(removals.markedAt(IndexName.of("books", "2")), is(Optional.empty()));
		} finally {
			node.close();
		}
	}

	/**
	 * Deleting the whole index needs no knowledge of its generations, so an
	 * operator can still remove an index that this build can not use.
	 */
	@Test
	public void deleteOfAWholeIndexThisBuildDoesNotSupportIsAccepted() throws IOException {
		// Given
		var storage = unsupportedBooks();
		var registry = new IndexRegistry(storage, Duration.ofMinutes(5));
		var node = newNode(storageDirectory.resolve("a"), registry, new RecordingIndexRemovals());
		try {
			node.refresh();

			// When
			node.delete("books");

			// Then
			assertThat(storage.stored().getIndexesCount(), is(0));
		} finally {
			node.close();
		}
	}
}
