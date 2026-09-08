package se.l4.exofind.engine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.index.FacetWarmer;
import se.l4.exofind.engine.index.SearchThreads;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.LocalRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.state.NoopSyncProvider;
import se.l4.exofind.engine.index.state.RecordingIndexRemovals;
import se.l4.exofind.engine.metrics.RequestMetrics;
import se.l4.exofind.engine.storage.StorageMode;

/**
 * What a node does with the index directories it holds when the registry beside
 * them lists no indexes. In local mode those directories are the only copy of
 * the data, so deleting them on a lost registry destroys the deployment.
 */
public class IndexesLostRegistryTest {
	/**
	 * A readiness wait long enough that nothing in a test outlasts it.
	 */
	private static final Duration LONG_WAIT = Duration.ofMinutes(10);

	@TempDir
	Path storageDirectory;

	private final List<Indexes> nodes = new ArrayList<>();

	@AfterEach
	void cleanup() {
		for(var node : nodes) {
			node.close();
		}
	}

	/**
	 * The shape of a directory restored from a backup that did not carry the
	 * registry file.
	 */
	@Test
	public void testCopiesAreKeptWhenTheRegistryIsGone() throws Exception {
		createIndexes("books", "films");
		Files.delete(registryFile());

		var node = node();
		node.refresh();

		assertThat(Files.isDirectory(dir("books")), is(true));
		assertThat(Files.isDirectory(dir("films")), is(true));
	}

	/**
	 * What a power loss between the rename of the registry and its contents
	 * reaching the disk leaves behind. An empty file parses as a registry that
	 * lists no indexes.
	 */
	@Test
	public void testCopiesAreKeptWhenTheRegistryIsEmpty() throws Exception {
		createIndexes("books", "films");
		Files.write(registryFile(), new byte[0]);

		var node = node();
		node.refresh();

		assertThat(Files.isDirectory(dir("books")), is(true));
		assertThat(Files.isDirectory(dir("films")), is(true));
	}

	/**
	 * A registry that lists at least one index is trusted, so a directory it
	 * does not name is still removed. This is how a node finds out that an
	 * index was removed somewhere else.
	 */
	@Test
	public void testACopyIsRemovedWhileTheRegistryNamesAnother() throws Exception {
		createIndexes("books");
		Files.createDirectories(dir("ghost"));

		var node = node();
		node.refresh();

		assertThat(Files.isDirectory(dir("books")), is(true));
		assertThat(Files.exists(dir("ghost")), is(false));
	}

	/**
	 * Deleting the last index of a deployment leaves a registry that lists
	 * nothing. A node that read the index before the delete knows the
	 * difference between that and a lost registry, so it removes its copy.
	 */
	@Test
	public void testTheLastIndexIsRemovedAfterTheNodeSawIt() throws Exception {
		createIndexes("books");

		var node = node();
		node.refresh();

		assertThat(Files.isDirectory(dir("books")), is(true));

		// Stands in for another node deleting the last index of the deployment
		registry().remove("books");

		node.refresh();

		assertThat(Files.exists(dir("books")), is(false));
	}

	/**
	 * A node with no index directories has nothing to keep and nothing to
	 * remove, whether or not the registry lists anything.
	 */
	@Test
	public void testAnEmptyDeploymentIsLeftAlone() throws Exception {
		var node = node();
		node.refresh();

		try(var paths = Files.list(indexRoot())) {
			assertThat(paths.toList(), is(empty()));
		}
	}

	/**
	 * Create the given indexes and shut the node down again, leaving the
	 * directories a restarted node finds.
	 */
	private void createIndexes(String... names) throws IOException {
		var seeder = node();
		try {
			for(var name : names) {
				seeder.create(name, IndexDef.getDefaultInstance());
			}
		} finally {
			seeder.close();
		}
	}

	/**
	 * A node over the shared directory. It opens no index before a request asks
	 * for one, so only the sweep decides what stays on disk.
	 */
	private Indexes node() throws IOException {
		var state = new NodeState(true);
		state.updateOwnership(true);

		var registry = registry();

		var node = new Indexes(
			state,
			new NoopSyncProvider(),
			registry,
			new RegistryHints(registry, StorageMode.LOCAL),
			new RecordingIndexRemovals(),
			RequestMetrics.none(),
			storageDirectory,
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
			Duration.ofHours(1),
			Optional.empty(),
			16,
			0,
			Duration.ofMinutes(5),
			LONG_WAIT,
			SearchThreads.inline(),
			FacetWarmer.none()
		);

		nodes.add(node);
		return node;
	}

	/**
	 * A registry over the shared file. A second one stands for another node
	 * reading and writing the same registry.
	 */
	private IndexRegistry registry() {
		return new IndexRegistry(
			new LocalRegistryStorage(registryFile()),
			Duration.ofMinutes(5)
		);
	}

	private Path registryFile() {
		return storageDirectory.resolve("registry.ef.bin");
	}

	private Path indexRoot() {
		return storageDirectory.resolve("indexes");
	}

	/**
	 * The directory of an index, which is the directory of its first
	 * generation.
	 */
	private Path dir(String name) {
		return indexRoot().resolve(name + "@1");
	}
}
