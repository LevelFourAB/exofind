package se.l4.exofind.engine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.zip.CRC32C;

import org.apache.lucene.index.SegmentInfos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.LocalRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.state.IndexUsage;
import se.l4.exofind.engine.index.state.IndexUsageFile;
import se.l4.exofind.engine.index.state.LocalCopy;
import se.l4.exofind.engine.index.state.Manifest;
import se.l4.exofind.engine.index.state.ManifestFile;
import se.l4.exofind.engine.index.state.NoopSyncProvider;
import se.l4.exofind.engine.storage.StorageMode;

/**
 * The sweep that bounds what local copies take on disk. Copies are made cold
 * by writing their usage records directly, and made fully pushed by writing a
 * manifest describing the directory as it is - what a completed push leaves
 * behind.
 */
public class IndexesDiskSweepTest {
	@TempDir
	Path storageDirectory;

	@Test
	public void testColdCopyOverBudgetIsRemovedButStaysKnown() throws IOException {
		createIndexes("books");
		writeSyncedManifest("books");
		ageUsage("books", Duration.ofDays(30), 1);

		var node = open("1", Duration.ofHours(24));
		try {
			node.sweepDisk();

			assertThat(Files.exists(dir("books")), is(false));
			assertThat(node.getIndexNames(), contains("books"));
		} finally {
			node.close();
		}
	}

	@Test
	public void testCopiesWithinBudgetAreLeftAlone() throws IOException {
		createIndexes("books");
		writeSyncedManifest("books");
		ageUsage("books", Duration.ofDays(30), 1);

		var node = open("1T", Duration.ofHours(24));
		try {
			node.sweepDisk();

			assertThat(Files.exists(dir("books")), is(true));
		} finally {
			node.close();
		}
	}

	@Test
	public void testRecentlyUsedCopyIsKept() throws IOException {
		// Closing the seeding node stamps the copy as used just now
		createIndexes("books");
		writeSyncedManifest("books");

		var node = open("1", Duration.ofHours(24));
		try {
			node.sweepDisk();

			assertThat(Files.exists(dir("books")), is(true));
		} finally {
			node.close();
		}
	}

	@Test
	public void testLeastOpenedCopyGoesFirst() throws IOException {
		createIndexes("hot", "cold");
		writeSyncedManifest("hot");
		writeSyncedManifest("cold");
		ageUsage("hot", Duration.ofDays(30), 8);
		ageUsage("cold", Duration.ofDays(30), 1);

		// Over budget by a single byte, so removing one copy is enough
		var budget = sizeOf(dir("hot")) + sizeOf(dir("cold")) - 1;

		var node = open(Long.toString(budget), Duration.ofHours(24));
		try {
			node.sweepDisk();

			assertThat(Files.exists(dir("hot")), is(true));
			assertThat(Files.exists(dir("cold")), is(false));
		} finally {
			node.close();
		}
	}

	@Test
	public void testCopyWithUnpushedCommitIsKept() throws IOException {
		createIndexes("books");
		writeSyncedManifest("books");
		doctorManifest("books", m -> m.setLatestSegment(m.getLatestSegment() - 1));
		ageUsage("books", Duration.ofDays(30), 1);

		var node = open("1", Duration.ofHours(24));
		try {
			node.sweepDisk();

			assertThat(Files.exists(dir("books")), is(true));
		} finally {
			node.close();
		}
	}

	@Test
	public void testCopyWithChangedDefinitionIsKept() throws IOException {
		createIndexes("books");
		writeSyncedManifest("books");
		doctorManifest("books", m -> {
			for(var i = 0; i < m.getFilesCount(); i++) {
				if(m.getFiles(i).getName().equals("definition.ef.bin")) {
					m.setFiles(i, m.getFiles(i).toBuilder().setChecksum(m.getFiles(i).getChecksum() + 1));
				}
			}
			return m;
		});
		ageUsage("books", Duration.ofDays(30), 1);

		var node = open("1", Duration.ofHours(24));
		try {
			node.sweepDisk();

			assertThat(Files.exists(dir("books")), is(true));
		} finally {
			node.close();
		}
	}

	/**
	 * With no manifest nothing proves the remote holds the index - which is
	 * every index when there is no remote at all - so the copy stays.
	 */
	@Test
	public void testCopyWithoutManifestIsKept() throws IOException {
		createIndexes("books");
		ageUsage("books", Duration.ofDays(30), 1);

		var node = open("1", Duration.ofHours(24));
		try {
			node.sweepDisk();

			assertThat(Files.exists(dir("books")), is(true));
		} finally {
			node.close();
		}
	}

	@Test
	public void testOpenIndexIsKept() throws IOException {
		createIndexes("open", "closed");
		writeSyncedManifest("open");
		writeSyncedManifest("closed");

		var node = open("1", Duration.ZERO);
		try {
			node.getOrThrow("open");

			// Aged after the open, so only being open protects the copy
			ageUsage("open", Duration.ofDays(30), 1);
			ageUsage("closed", Duration.ofDays(30), 1);

			node.sweepDisk();

			assertThat(Files.exists(dir("open")), is(true));
			assertThat(Files.exists(dir("closed")), is(false));
		} finally {
			node.close();
		}
	}

	/**
	 * A directory from before usage was recorded is stamped as used now and
	 * kept, so an upgrade can never remove copies wholesale.
	 */
	@Test
	public void testCopyWithoutUsageRecordIsSeededAndKept() throws IOException {
		createIndexes("books");
		writeSyncedManifest("books");
		Files.delete(dir("books").resolve(IndexUsageFile.NAME));

		var node = open("1", Duration.ofHours(24));
		try {
			node.sweepDisk();

			assertThat(Files.exists(dir("books")), is(true));
			assertThat(IndexUsageFile.read(dir("books")).hasLastUsedMs(), is(true));
		} finally {
			node.close();
		}
	}

	@Test
	public void testOpensAreCountedAcrossNodes() throws IOException {
		createIndexes("books");

		var first = IndexUsageFile.read(dir("books"));
		assertThat(first.getDecayedOpens(), greaterThanOrEqualTo(1.0));

		var node = open("1T", Duration.ofHours(24));
		try {
			node.getOrThrow("books");
		} finally {
			node.close();
		}

		var second = IndexUsageFile.read(dir("books"));
		assertThat(second.getDecayedOpens(), closeTo(first.getDecayedOpens() + 1, 0.01));
	}

	@Test
	public void testParseSize() {
		assertThat(Indexes.parseSize("123"), is(123L));
		assertThat(Indexes.parseSize("64K"), is(64L * 1024));
		assertThat(Indexes.parseSize("10m"), is(10L * 1024 * 1024));
		assertThat(Indexes.parseSize("2G"), is(2L * 1024 * 1024 * 1024));
		assertThat(Indexes.parseSize("1T"), is(1024L * 1024 * 1024 * 1024));
		assertThat(Indexes.parseSize(" 5 G "), is(5L * 1024 * 1024 * 1024));

		assertThrows(IllegalArgumentException.class, () -> Indexes.parseSize("ten"));
		assertThrows(IllegalArgumentException.class, () -> Indexes.parseSize("-1G"));
		assertThrows(IllegalArgumentException.class, () -> Indexes.parseSize(""));
	}

	/**
	 * Create the given indexes and shut the node down again, leaving their
	 * directories on disk the way a node that went cold would.
	 */
	private void createIndexes(String... names) throws IOException {
		var seeder = open(null, Duration.ofHours(24));
		try {
			for(var name : names) {
				seeder.create(name, IndexDef.getDefaultInstance());
			}

			assertThat(seeder.getIndexNames(), containsInAnyOrder(names));
		} finally {
			seeder.close();
		}
	}

	/**
	 * A node that has read the registry, which is what the sweep needs before
	 * it may remove anything. A node reads it on a thread of its own as well,
	 * but only the read asked for here has happened by the time this returns.
	 */
	private Indexes open(String maxSize, Duration minIdle) throws IOException {
		var state = new NodeState(true);
		state.updateOwnership(true);

		var registry = new IndexRegistry(
			new LocalRegistryStorage(storageDirectory.resolve("registry.ef.bin")),
			Duration.ofMinutes(5)
		);

		var node = new Indexes(
			state,
			new NoopSyncProvider(),
			registry,
			new RegistryHints(registry, StorageMode.LOCAL),
			storageDirectory,
			OptionalInt.empty(),
			Duration.ofMinutes(5),
			Duration.ofMinutes(10),
			4,
			Duration.ofSeconds(10),
			0,
			Duration.ZERO,
			Optional.ofNullable(maxSize),
			Optional.empty(),
			minIdle,
			Duration.ofHours(168),
			Duration.ofHours(1)
		);

		node.refresh();

		return node;
	}

	/**
	 * The directory of an index, which is the directory of its first generation
	 * - the only one anything here creates.
	 */
	private Path dir(String name) {
		return storageDirectory.resolve("indexes").resolve(name + "@1");
	}

	/**
	 * Describe the directory as it is in a manifest, the way a push that got
	 * everything to the remote would have.
	 */
	private void writeSyncedManifest(String name) throws IOException {
		var directory = dir(name);

		var files = new TreeMap<String, Path>();
		try(var listing = Files.list(directory)) {
			for(var path : listing.filter(Files::isRegularFile).toList()) {
				var fileName = path.getFileName().toString();
				if(
					fileName.equals(LocalCopy.MANIFEST_FILE)
						|| fileName.equals(IndexUsageFile.NAME)
						|| fileName.endsWith(".lock")
						|| fileName.endsWith(".tmp")
				) {
					continue;
				}

				files.put(fileName, path);
			}
		}

		var builder = Manifest.newBuilder()
			.setLatestSegment(SegmentInfos.getLastCommitGeneration(files.keySet().toArray(String[]::new)))
			.setVersion(1);

		for(var entry : files.entrySet()) {
			builder.addFiles(
				ManifestFile.newBuilder()
					.setName(entry.getKey())
					.setSize(Files.size(entry.getValue()))
					.setChecksum(checksumOf(entry.getValue()))
					.build()
			);
		}

		writeManifest(name, builder.build());
	}

	private void doctorManifest(String name, UnaryOperator<Manifest.Builder> change) throws IOException {
		Manifest manifest;
		try(var in = Files.newInputStream(dir(name).resolve(LocalCopy.MANIFEST_FILE))) {
			manifest = Manifest.parseFrom(in);
		}

		writeManifest(name, change.apply(manifest.toBuilder()).build());
	}

	private void writeManifest(String name, Manifest manifest) throws IOException {
		try(var out = Files.newOutputStream(dir(name).resolve(LocalCopy.MANIFEST_FILE))) {
			manifest.writeTo(out);
		}
	}

	private void ageUsage(String name, Duration age, double opens) throws IOException {
		var at = Instant.now().minus(age);

		IndexUsageFile.write(
			dir(name),
			IndexUsage.newBuilder()
				.setLastUsedMs(at.toEpochMilli())
				.setDecayedOpens(opens)
				.setDecayedAtMs(at.toEpochMilli())
				.build()
		);
	}

	private static long sizeOf(Path directory) throws IOException {
		try(var files = Files.walk(directory)) {
			var total = 0L;
			for(var file : files.filter(Files::isRegularFile).toList()) {
				total += Files.size(file);
			}

			return total;
		}
	}

	private static int checksumOf(Path path) throws IOException {
		var checksum = new CRC32C();
		checksum.update(Files.readAllBytes(path));
		return (int) checksum.getValue();
	}
}
