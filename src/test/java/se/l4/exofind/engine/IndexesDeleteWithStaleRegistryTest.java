package se.l4.exofind.engine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.registry.InMemoryRegistryStorage;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.state.NoopSyncProvider;
import se.l4.exofind.engine.index.state.RecordingIndexRemovals;
import se.l4.exofind.engine.storage.StorageMode;

/**
 * That a delete finds an index another node created after this node last read
 * the registry.
 *
 * <p>The copy of the registry a node holds can be a refresh interval behind,
 * and a name it does not know is read from the storage at most once per
 * interval. The delete itself reads the stored registry before it writes, so
 * it is what decides whether the index exists.
 */
public class IndexesDeleteWithStaleRegistryTest {
	@TempDir
	Path storageDirectory;

	private static Indexes newNode(
		Path directory,
		IndexRegistry registry,
		RecordingIndexRemovals removals
	) throws IOException {
		var nodeState = new NodeState(true);
		nodeState.updateOwnership(true);

		return new Indexes(
			nodeState,
			new NoopSyncProvider(),
			registry,
			new RegistryHints(registry, StorageMode.LOCAL),
			removals,
			directory,
			OptionalInt.empty(),
			Duration.ofMinutes(5),
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

	private static IndexDef keyed() {
		return IndexDef.newBuilder()
			.putFields(
				"id",
				FieldDef.newBuilder()
					.setPrimaryKey(true)
					.setType(
						FieldTypeDef.newBuilder()
							.setString(StringFieldTypeDef.getDefaultInstance())
					)
					.build()
			)
			.build();
	}

	@Test
	void deleteOfAnIndexCreatedOnAnotherNodeSucceedsWhenTheLookupBudgetIsUsed()
		throws IOException
	{
		// Given two nodes over one registry
		var storage = new InMemoryRegistryStorage();
		var registryA = new IndexRegistry(storage, Duration.ofMinutes(5));
		var registryB = new IndexRegistry(storage, Duration.ofMinutes(5));

		var removalsA = new RecordingIndexRemovals();
		var nodeA = newNode(storageDirectory.resolve("a"), registryA, removalsA);
		var nodeB = newNode(storageDirectory.resolve("b"), registryB, new RecordingIndexRemovals());
		try {
			nodeA.refresh();

			// And node A used its one lookup of an unknown name for this interval
			assertThat(nodeA.get("unknown").isPresent(), is(false));

			// And node B creates the index after that lookup
			nodeB.create("books", keyed());

			// When node A deletes it
			nodeA.delete("books");

			// Then the stored registry no longer names it, and the delete left its mark
			registryB.refresh();
			assertThat(registryB.get("books").isPresent(), is(false));
			assertThat(removalsA.marks, hasKey(IndexName.of("books")));
		} finally {
			nodeA.close();
			nodeB.close();
		}
	}
}
