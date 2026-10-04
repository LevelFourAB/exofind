package se.l4.exofind.engine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.registry.InMemoryRegistryStorage;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.RegistryException;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.state.NoopSyncProvider;
import se.l4.exofind.engine.index.state.RecordingIndexRemovals;
import se.l4.exofind.engine.storage.StorageMode;

/**
 * That a create which clears the prefix of its name does not take the mark of
 * a delete that came after the create registered the index.
 *
 * <p>The create clears what an earlier delete left under the prefix, mark and
 * all. A delete on another node can remove the index from the registry and
 * leave its mark before that clearing runs. Without the mark nothing removes
 * the objects, and a later create of the name finds them held.
 */
public class IndexesCreateDuringDeleteTest {
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
	void aCreateDoesNotTakeTheMarkOfADeleteThatCameAfterIt() throws IOException {
		// Given two nodes over one registry and one storage
		var storage = new InMemoryRegistryStorage();
		var registryA = new IndexRegistry(storage, Duration.ofMinutes(5));
		var registryB = new IndexRegistry(storage, Duration.ofMinutes(5));

		var hook = new AtomicReference<Runnable>();
		var removals = new RecordingIndexRemovals() {
			@Override
			public void prepareForIndex(String index) {
				var run = hook.getAndSet(null);
				if(run != null) {
					run.run();
				}

				super.prepareForIndex(index);
			}
		};

		var nodeA = newNode(storageDirectory.resolve("a"), registryA, removals);
		var nodeB = newNode(storageDirectory.resolve("b"), registryB, removals);
		try {
			// And node B deletes the index right after node A registered it
			hook.set(() -> {
				try {
					nodeB.delete("books");
				} catch(IOException e) {
					throw new RuntimeException(e);
				}
			});

			// When node A goes on with the create, it answers with a conflict
			assertThrows(RegistryException.class, () -> nodeA.create("books", keyed()));

			// Then the registry does not name the index
			registryB.refresh();
			assertThat(registryB.get("books").isPresent(), is(false));

			// And a mark of the delete still stands over it
			assertThat(removals.markedAt(IndexName.of("books")).isPresent(), is(true));
		} finally {
			nodeA.close();
			nodeB.close();
		}
	}

	@Test
	void aCreateWithNoDeleteMeanwhileOpensTheIndex() throws IOException {
		var storage = new InMemoryRegistryStorage();
		var registry = new IndexRegistry(storage, Duration.ofMinutes(5));
		var removals = new RecordingIndexRemovals();

		var node = newNode(storageDirectory.resolve("a"), registry, removals);
		try {
			var created = node.create("books", keyed());

			assertThat(created.getId(), is("books@1"));
			assertThat(removals.markedAt(IndexName.of("books")).isPresent(), is(false));
		} finally {
			node.close();
		}
	}
}
