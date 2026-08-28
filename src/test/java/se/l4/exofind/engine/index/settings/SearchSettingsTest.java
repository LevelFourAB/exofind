package se.l4.exofind.engine.index.settings;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.InMemoryRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.schema.RankingConfig;
import se.l4.exofind.engine.storage.StorageMode;

public class SearchSettingsTest {
	InMemorySearchSettingsStorage storage;
	SearchSettings settings;

	@BeforeEach
	void setup() {
		storage = new InMemorySearchSettingsStorage();
		settings = newSettings(storage);
	}

	/**
	 * Settings over the given storage, with a registry of their own the way a
	 * node without indexes has one, and a verify interval long enough that the
	 * fallback never fires inside a test.
	 */
	private static SearchSettings newSettings(SearchSettingsStorage storage) {
		var registry = new IndexRegistry(
			new InMemoryRegistryStorage(),
			Duration.ofMinutes(5)
		);

		return new SearchSettings(
			storage,
			registry,
			new RegistryHints(registry, StorageMode.LOCAL),
			Duration.ofSeconds(10),
			Duration.ofMinutes(10)
		);
	}

	private static SearchSettingsStore storeWith(String tieBreakerField) {
		return SearchSettingsFeatures.describe(
			SearchSettingsStore.newBuilder()
				.setRanking(RankingConfig.newBuilder()
					.addTieBreakers(RankingConfig.TieBreaker.newBuilder()
						.setField(tieBreakerField)))
				.build()
		);
	}

	@Test
	void testGetOfAnIndexWithoutSettingsIsEmpty() {
		assertThat(settings.get("books").isEmpty(), is(true));
	}

	/**
	 * An index this node has not seen settings for is looked up once, and the
	 * answer - even "there are none" - is then held rather than asked for
	 * again per search.
	 */
	@Test
	void testRepeatedGetsCostOneRead() {
		settings.get("books");
		settings.get("books");
		settings.get("books");

		assertThat(storage.reads, is(1));
	}

	@Test
	void testGetFindsStoredSettings() {
		storage.set("books", storeWith("sales"));

		var snapshot = settings.get("books").orElseThrow();
		assertThat(snapshot.ranking(), not(nullValue()));
		assertThat(snapshot.ranking().getTieBreakers(0).getField(), is("sales"));
		assertThat(snapshot.unsupportedFeatures().isEmpty(), is(true));
	}

	/**
	 * The refresh is what carries a change made elsewhere onto this node - a
	 * get in between answers from the copy.
	 */
	@Test
	void testRefreshPicksUpAChange() {
		settings.get("books");

		storage.set("books", storeWith("sales"));
		assertThat(settings.get("books").isEmpty(), is(true));

		settings.refresh();
		assertThat(
			settings.get("books").orElseThrow().ranking().getTieBreakers(0).getField(),
			is("sales")
		);
	}

	@Test
	void testRefreshPicksUpARemoval() throws Exception {
		storage.set("books", storeWith("sales"));
		settings.get("books");

		storage.delete("books");
		settings.refresh();

		assertThat(settings.get("books").isEmpty(), is(true));
	}

	/**
	 * A storage that cannot be reached leaves the copy as it was, so searches
	 * keep ranking the way they did rather than snapping back to the
	 * definition.
	 */
	@Test
	void testUnreachableStorageKeepsTheCopy() {
		storage.set("books", storeWith("sales"));
		settings.get("books");

		storage.unreachable = true;
		settings.refresh();

		assertThat(settings.get("books").isPresent(), is(true));
	}

	@Test
	void testPutStoresAndTakesEffectAtOnce() {
		var snapshot = settings.put("books", storeWith("sales"), null);

		assertThat(snapshot.version(), not(nullValue()));
		assertThat(storage.get("books"), is(storeWith("sales")));
		assertThat(settings.get("books").orElseThrow().version(), is(snapshot.version()));
	}

	/**
	 * A put without an expected version is rebuilt on top of a concurrent
	 * write rather than refused by it.
	 */
	@Test
	void testPutRetriesPastAConcurrentWrite() {
		storage.set("books", storeWith("sales"));
		storage.refuseNextWrite = true;

		var snapshot = settings.put("books", storeWith("published"), null);

		assertThat(snapshot.ranking().getTieBreakers(0).getField(), is("published"));
	}

	@Test
	void testPutGivesUpWhenTheWritesKeepLosing() {
		var alwaysLosing = new InMemorySearchSettingsStorage() {
			@Override
			public synchronized String write(
				String index,
				SearchSettingsStore settings,
				String expectedVersion
			) {
				return null;
			}
		};

		var contended = newSettings(alwaysLosing);
		var e = assertThrows(
			SearchSettingsException.class,
			() -> contended.put("books", storeWith("sales"), null)
		);
		assertThat(e.getCode(), is("index:settings:conflict"));
	}

	/**
	 * A caller that named the version to build on is told about the conflict
	 * rather than having their expectation retried away.
	 */
	@Test
	void testPutWithAStaleExpectedVersionIsRefused() {
		var first = settings.put("books", storeWith("sales"), null);
		settings.put("books", storeWith("published"), null);

		assertThrows(
			SearchSettingsVersionMismatchException.class,
			() -> settings.put("books", storeWith("rating"), first.version())
		);
	}

	@Test
	void testPutWithTheCurrentExpectedVersionIsTaken() {
		var first = settings.put("books", storeWith("sales"), null);

		var second = settings.put("books", storeWith("published"), first.version());
		assertThat(second.ranking().getTieBreakers(0).getField(), is("published"));
	}

	@Test
	void testPutWithoutAStoreIsRefused() {
		var without = newSettings(new NoSearchSettingsStorage());

		var e = assertThrows(
			SearchSettingsException.class,
			() -> without.put("books", storeWith("sales"), null)
		);
		assertThat(e.getCode(), is("index:settings:unavailable"));
	}

	@Test
	void testDeleteTakesEffectAtOnce() {
		settings.put("books", storeWith("sales"), null);
		settings.delete("books");

		assertThat(settings.get("books").isEmpty(), is(true));
		assertThat(storage.get("books"), is(nullValue()));
	}

	/**
	 * Reading answers what is stored rather than what this node holds, so
	 * settings stored elsewhere are seen as soon as they exist.
	 */
	@Test
	void testReadSeesAChangeBeforeTheRefresh() {
		settings.get("books");
		storage.set("books", storeWith("sales"));

		assertThat(settings.read("books").isPresent(), is(true));
	}

	@Test
	void testReadOfUnreachableStorageFails() {
		storage.unreachable = true;

		var e = assertThrows(SearchSettingsException.class, () -> settings.read("books"));
		assertThat(e.getCode(), is("index:settings:io_error"));
	}

	/**
	 * An object needing features this build does not have is set aside whole -
	 * no ranking - with the names carried so a caller can see why.
	 */
	@Test
	void testUnknownRequiredFeatureSetsTheObjectAside() {
		storage.set(
			"books",
			storeWith("sales").toBuilder()
				.addRequiredFeatures("pin_rules")
				.build()
		);

		var snapshot = settings.get("books").orElseThrow();
		assertThat(snapshot.ranking(), is(nullValue()));
		assertThat(snapshot.unsupportedFeatures(), contains("pin_rules"));
	}
}
