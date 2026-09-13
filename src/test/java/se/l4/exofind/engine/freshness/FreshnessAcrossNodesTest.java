package se.l4.exofind.engine.freshness;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.index.Document;
import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.ObjectStorageRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.settings.ObjectStorageSearchSettingsStorage;
import se.l4.exofind.engine.index.settings.SearchSettings;
import se.l4.exofind.engine.index.settings.SearchSettingsStore;
import se.l4.exofind.engine.index.state.ObjectStorageSyncProvider;
import se.l4.exofind.engine.index.state.RecordingIndexRemovals;
import se.l4.exofind.engine.index.state.TestObjectStorage;
import se.l4.exofind.engine.storage.ObjectStorage;
import se.l4.exofind.engine.storage.StorageMode;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

/**
 * Two nodes over one object storage: a change made on the node that writes,
 * and a read on the node that does not, handed the state the change landed
 * in. Neither node polls anything here, so what the reading node learns it
 * learns because the read demanded it.
 */
public class FreshnessAcrossNodesTest {
	@TempDir
	Path directory;

	S3Client s3Client;
	String storagePrefix;

	Node writer;
	Node reader;

	/**
	 * One node: what it holds of the deployment, and the waiter over it.
	 */
	static final class Node {
		final Indexes indexes;
		final SearchSettings searchSettings;
		final FreshnessWaiter waiter;

		Node(Path directory, ObjectStorage storage, boolean indexer) throws IOException {
			var nodeState = new NodeState(indexer);
			nodeState.updateOwnership(indexer);

			var registry = new IndexRegistry(
				new ObjectStorageRegistryStorage(storage),
				Duration.ofMinutes(5)
			);
			var hints = new RegistryHints(registry, StorageMode.OBJECT);

			/*
			 * The writer commits on its own a second after a write. A node
			 * that does not write cannot ask it to commit sooner, so that is
			 * what a read on the other node waits for; the pull that follows
			 * is what the token adds.
			 */
			indexes = new Indexes(
				nodeState,
				new ObjectStorageSyncProvider(storage),
				registry,
				hints,
				new RecordingIndexRemovals(),
				directory,
				OptionalInt.empty(),
				Duration.ofMinutes(5),
				Duration.ofMinutes(10),
				4,
				Duration.ofSeconds(10),
				10000,
				Duration.ofSeconds(1),
				Optional.empty(),
				Optional.empty(),
				Duration.ofHours(24),
				Duration.ofHours(168),
				Duration.ofHours(1)
			);

			searchSettings = new SearchSettings(
				new ObjectStorageSearchSettingsStorage(storage),
				registry,
				hints,
				Duration.ofMinutes(5),
				Duration.ofMinutes(10)
			);

			waiter = new FreshnessWaiter(indexes, searchSettings, Duration.ofSeconds(10));
		}

		void close() {
			indexes.close();
		}
	}

	@BeforeEach
	void setup() throws Exception {
		s3Client = TestObjectStorage.client();
		storagePrefix = "freshness" + RandomStringUtils.insecure().nextAlphabetic(10);

		writer = new Node(directory.resolve("writer"), storage(true), true);
		reader = new Node(directory.resolve("reader"), storage(false), false);
	}

	private ObjectStorage storage(boolean indexer) throws IOException {
		return new ObjectStorage(
			Optional.of(TestObjectStorage.url()),
			TestObjectStorage.auth(),
			Optional.empty(),
			TestObjectStorage.BUCKET,
			Optional.of(storagePrefix),
			indexer
		);
	}

	@AfterEach
	void cleanup() {
		reader.close();
		writer.close();

		var objects = s3Client.listObjectsV2Paginator(
			b -> b.bucket(TestObjectStorage.BUCKET).prefix(storagePrefix)
		).contents().stream().toList();

		for(var object : objects) {
			s3Client.deleteObject(
				DeleteObjectRequest.builder()
					.bucket(TestObjectStorage.BUCKET)
					.key(object.key())
					.build()
			);
		}
	}

	private static IndexDef definition() {
		return IndexDef.newBuilder()
			.putFields(
				"id",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder().setString(StringFieldTypeDef.getDefaultInstance())
					)
					.setPrimaryKey(true)
					.build()
			)
			.build();
	}

	private static Document document(String id) {
		return new Document(new Document.Value("id", id));
	}

	/**
	 * Write one document on the writer and get the state it lands in, the
	 * way a request does.
	 */
	private static Freshness write(Index index, String id) throws IOException {
		try(var change = index.beginChange()) {
			index.addDocument(document(id));
			return Freshness.ofCommit(IndexName.parse(index.getId()), change.landsIn());
		}
	}

	/**
	 * A document written on one node is read on the other, with no timer
	 * and no commit asked for by hand: the token makes the writer commit and
	 * the reader pull.
	 */
	@Test
	public void testAWriteIsReadOnTheOtherNode() throws IOException {
		var index = writer.indexes.create("books", definition());
		var written = write(index, "1");

		var started = System.nanoTime();
		var answered = reader.waiter.await("books", written);
		var waited = Duration.ofNanos(System.nanoTime() - started);

		assertThat(answered.isReadOnly(), is(true));
		assertThat(answered.visibleCommit(), is(greaterThanOrEqualTo(written.commit())));
		assertThat(answered.getDocument("1").get("id"), is("1"));
		assertThat(waited, is(lessThan(Duration.ofSeconds(4))));
	}

	/**
	 * Settings changed on one node are in force on the other before it
	 * answers, inside the interval the other would otherwise wait.
	 */
	@Test
	public void testASettingsChangeIsInForceOnTheOtherNode() throws IOException {
		var index = writer.indexes.create("books", definition());
		index.addDocument(document("1"));
		index.commit();

		// The reader holds the settings as they were before the change
		reader.waiter.await("books", Freshness.ofCommit(IndexName.of("books", "1"), 1));
		var before = reader.waiter.stateOf(reader.indexes.getOrThrow("books")).settingsVersion();

		var stored = writer.searchSettings.put(
			"books",
			SearchSettingsStore.newBuilder().build(),
			null
		);
		assertThat(before, is(not(stored.version())));

		var answered = reader.waiter.await(
			"books",
			Freshness.ofSettings("books", stored.version())
		);

		assertThat(reader.waiter.stateOf(answered).settingsVersion(), is(stored.version()));
	}

	/**
	 * A generation promoted on one node answers on the other, whose copy of
	 * the registry still names the generation it replaced.
	 */
	@Test
	public void testAPromotionIsAnsweredFromOnTheOtherNode() throws IOException {
		var first = writer.indexes.create("books", definition());
		first.addDocument(document("1"));
		first.commit();

		// The reader answers from the first generation
		reader.waiter.await("books", Freshness.ofCommit(IndexName.of("books", "1"), 1));
		assertThat(reader.indexes.getOrThrow("books").getId(), is("books@1"));

		var second = writer.indexes.createGeneration("books@2", definition());
		second.addDocument(document("2"));
		second.commit();
		writer.indexes.promote("books@2");
		var promoted = Freshness.ofCommit(IndexName.of("books", "2"), second.visibleCommit());

		var answered = reader.waiter.await("books", promoted);

		assertThat(answered.getId(), is("books@2"));
		assertThat(answered.getDocument("2").get("id"), is("2"));
	}
}
