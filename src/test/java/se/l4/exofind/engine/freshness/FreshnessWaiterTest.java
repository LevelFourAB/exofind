package se.l4.exofind.engine.freshness;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;

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
import se.l4.exofind.engine.index.registry.LocalRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.settings.InMemorySearchSettingsStorage;
import se.l4.exofind.engine.index.settings.SearchSettings;
import se.l4.exofind.engine.index.settings.SearchSettingsStore;
import se.l4.exofind.engine.index.state.NoopSyncProvider;
import se.l4.exofind.engine.storage.StorageMode;

/**
 * The waiter on one node that writes, which is what local mode is: the
 * commit sequence is reached by asking the writer to commit, a settings
 * version by reading the storage, and a generation by reading the registry.
 */
public class FreshnessWaiterTest {
	@TempDir
	Path storageDirectory;

	Indexes indexes;
	SearchSettings searchSettings;
	FreshnessWaiter waiter;

	@BeforeEach
	void setup() throws IOException {
		var nodeState = new NodeState(true);
		nodeState.updateOwnership(true);

		var registry = new IndexRegistry(
			new LocalRegistryStorage(storageDirectory.resolve("registry.ef.bin")),
			Duration.ofMinutes(5)
		);

		/*
		 * A commit policy that would wait five seconds on its own, so that a
		 * wait that returns sooner shows the writer was asked to commit.
		 */
		indexes = new Indexes(
			nodeState,
			new NoopSyncProvider(),
			registry,
			new RegistryHints(registry, StorageMode.LOCAL),
			storageDirectory,
			OptionalInt.empty(),
			Duration.ofMinutes(5),
			Duration.ofMinutes(10),
			4,
			Duration.ofSeconds(10),
			10000,
			Duration.ofSeconds(5),
			Optional.empty(),
			Optional.empty(),
			Duration.ofHours(24),
			Duration.ofHours(168),
			Duration.ofHours(1)
		);

		searchSettings = new SearchSettings(
			new InMemorySearchSettingsStorage(),
			registry,
			new RegistryHints(registry, StorageMode.LOCAL),
			Duration.ofSeconds(10),
			Duration.ofMinutes(10)
		);

		waiter = new FreshnessWaiter(indexes, searchSettings, Duration.ofSeconds(10));
	}

	@AfterEach
	void cleanup() {
		indexes.close();
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
	 * Write one document and get the state it lands in, the way a request
	 * does.
	 */
	private static Freshness write(Index index, String id) throws IOException {
		try(var change = index.beginChange()) {
			index.addDocument(document(id));
			return Freshness.ofCommit(IndexName.parse(index.getId()), change.landsIn());
		}
	}

	@Test
	public void testNoDemandAnswersFromWhatTheNodeHolds() throws IOException {
		var index = indexes.create("books", definition());
		index.addDocument(document("1"));

		assertThat(waiter.await("books", null), is(index));
		assertThat(index.visibleCommit(), is(0L));
	}

	@Test
	public void testAWriteIsWaitedForByAskingTheWriterToCommit() throws IOException {
		var index = indexes.create("books", definition());
		var written = write(index, "1");

		var started = System.nanoTime();
		var answered = waiter.await("books", written);
		var waited = Duration.ofNanos(System.nanoTime() - started);

		assertThat(answered.visibleCommit(), is(greaterThanOrEqualTo(written.commit())));
		assertThat(waited, is(lessThan(Duration.ofSeconds(4))));
		assertThat(index.getDocument("1").get("id"), is("1"));
	}

	@Test
	public void testAStateAlreadyReachedIsAnsweredAtOnce() throws IOException {
		var index = indexes.create("books", definition());
		var written = write(index, "1");
		index.commit();

		var started = System.nanoTime();
		waiter.await("books", written);

		assertThat(
			Duration.ofNanos(System.nanoTime() - started),
			is(lessThan(Duration.ofSeconds(1)))
		);
	}

	@Test
	public void testASequenceTheNodeCannotReachFailsOnceTheWaitIsOver() throws IOException {
		var index = indexes.create("books", definition());
		index.addDocument(document("1"));
		index.commit();

		var impatient = new FreshnessWaiter(indexes, searchSettings, Duration.ZERO);
		var far = Freshness.ofCommit(IndexName.of("books", "1"), 99);

		var e = assertThrows(
			FreshnessUnavailableException.class,
			() -> impatient.await("books", far)
		);

		assertThat(e.getCode(), is("search:freshness:unavailable"));
		assertThat(e.getStatus(), is(503));
		assertThat(e.retryAfter(), is(Duration.ofSeconds(1)));
		assertThat(e.getArguments().get("index"), is("books"));
	}

	@Test
	public void testAStateOfAnotherIndexIsRefused() throws IOException {
		indexes.create("books", definition());

		assertThrows(
			IllegalArgumentException.class,
			() -> waiter.await("books", Freshness.ofCommit(IndexName.of("films", "1"), 1))
		);
	}

	/**
	 * A name that carries a generation answers from it whatever the state
	 * names, so a sequence of another generation is not waited for.
	 */
	@Test
	public void testAPinnedNameDoesNotWaitForAnotherGeneration() throws IOException {
		var first = indexes.create("books", definition());
		first.addDocument(document("1"));
		first.commit();

		var started = System.nanoTime();
		var answered = waiter.await("books@1", Freshness.ofCommit(IndexName.of("books", "2"), 99));

		assertThat(answered, is(first));
		assertThat(
			Duration.ofNanos(System.nanoTime() - started),
			is(lessThan(Duration.ofSeconds(1)))
		);
	}

	@Test
	public void testTheSettingsVersionTheNodeHoldsIsAnsweredAtOnce() throws IOException {
		var index = indexes.create("books", definition());
		var stored = searchSettings.put("books", SearchSettingsStore.newBuilder().build(), null);

		var answered = waiter.await("books", Freshness.ofSettings("books", stored.version()));

		assertThat(answered, is(index));
		assertThat(waiter.stateOf(index).settingsVersion(), is(stored.version()));
	}

	/**
	 * A version this node has never held is answered after one read of the
	 * storage, which holds that version or a later one. Nothing is left
	 * waiting on a version nothing will store again.
	 */
	@Test
	public void testAnUnknownSettingsVersionIsReadPastOnce() throws IOException {
		var index = indexes.create("books", definition());
		searchSettings.put("books", SearchSettingsStore.newBuilder().build(), null);

		var started = System.nanoTime();
		var answered = waiter.await("books", Freshness.ofSettings("books", "\"never-stored\""));
		waiter.await("books", Freshness.ofSettings("books", "\"never-stored\""));

		assertThat(answered, is(index));
		assertThat(
			Duration.ofNanos(System.nanoTime() - started),
			is(lessThan(Duration.ofSeconds(1)))
		);
	}

	@Test
	public void testRemovedSettingsAreAState() throws IOException {
		var index = indexes.create("books", definition());
		searchSettings.put("books", SearchSettingsStore.newBuilder().build(), null);
		searchSettings.delete("books");

		waiter.await("books", Freshness.ofSettings("books", null));

		assertThat(waiter.stateOf(index).settingsVersion(), is(""));
	}

	@Test
	public void testAPromotedGenerationIsAnsweredFrom() throws IOException {
		indexes.create("books", definition());
		var second = indexes.createGeneration("books@2", definition());
		second.addDocument(document("1"));
		second.commit();
		indexes.promote("books@2");

		var answered = waiter.await(
			"books",
			Freshness.ofCommit(IndexName.of("books", "2"), second.visibleCommit())
		);

		assertThat(answered.getId(), is("books@2"));
	}

	/**
	 * A state of a generation the index no longer answers from was promoted
	 * over, and what the node answers from is later than it.
	 */
	@Test
	public void testAGenerationPromotedOverIsSatisfied() throws IOException {
		var first = indexes.create("books", definition());
		first.addDocument(document("1"));
		first.commit();
		var second = indexes.createGeneration("books@2", definition());
		indexes.promote("books@2");

		var answered = waiter.await("books", Freshness.ofCommit(IndexName.of("books", "1"), 1));

		assertThat(answered, is(second));
	}

	@Test
	public void testAGenerationTheRegistryNeverHeldIsSatisfied() throws IOException {
		var first = indexes.create("books", definition());

		var answered = waiter.await("books", Freshness.ofGeneration(IndexName.of("books", "9")));

		assertThat(answered, is(first));
	}

	@Test
	public void testTheStateOfAnAnswerNamesEverything() throws IOException {
		var index = indexes.create("books", definition());
		index.addDocument(document("1"));
		index.commit();
		var stored = searchSettings.put("books", SearchSettingsStore.newBuilder().build(), null);

		var state = waiter.stateOf(index);

		assertThat(state, is(new Freshness("books", "1", 1, stored.version())));
	}
}
