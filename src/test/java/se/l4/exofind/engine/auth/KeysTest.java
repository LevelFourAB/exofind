package se.l4.exofind.engine.auth;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class KeysTest {
	private static final String ROOT_KEY = "a-long-random-root-key";

	InMemoryKeyStorage storage;

	private final List<Keys> started = new ArrayList<>();

	@BeforeEach
	void setup() {
		storage = new InMemoryKeyStorage();
	}

	@AfterEach
	void cleanup() {
		started.forEach(Keys::stop);
		started.clear();
	}

	private Keys keys(String rootKey, String anonymousKey) {
		var instance = new Keys(
			storage,
			AuthMode.KEYS,
			Optional.ofNullable(rootKey),
			Optional.ofNullable(anonymousKey),
			Duration.ofSeconds(30)
		);

		started.add(instance);
		return instance;
	}

	private Keys keys() {
		return keys(ROOT_KEY, null);
	}

	private static Grant grant(String index, Permission... permissions) {
		return new Grant(Sets.immutable.of(permissions), Lists.immutable.of(index));
	}

	/**
	 * Put a key into the store the way another node would have, so that this
	 * node has never seen it.
	 */
	private String storeKey(Instant expiresAt, Grant... grants) {
		var generated = KeySecret.generate();
		var existing = Lists.mutable.<Key>empty();

		try {
			if(storage.read(null) instanceof KeyStorage.Read.Loaded loaded) {
				existing.addAll(KeyStoreCodec.fromStored(loaded.keys()).toList());
			}
		} catch(Exception e) {
			throw new IllegalStateException(e);
		}

		existing.add(
			new Key(
				generated.id(),
				generated.secretHash(),
				"",
				Lists.immutable.of(grants),
				Instant.now(),
				expiresAt
			)
		);

		storage.set(KeyStoreCodec.toStored(existing.toImmutable()));
		return generated.credential();
	}

	@Test
	void theRootKeyResolvesToAPrincipalAllowedEverything() {
		var principal = keys().resolve("Bearer " + ROOT_KEY);

		assertThat(principal.id(), is(Principal.ROOT));
		assertThat(principal.allows(Permission.KEYS_WRITE), is(true));
	}

	@Test
	void theRootKeyCanBeConfiguredAsItsHash() {
		var principal = keys("sha256:" + KeySecret.hash(ROOT_KEY), null)
			.resolve("Bearer " + ROOT_KEY);

		assertThat(principal.id(), is(Principal.ROOT));
	}

	@Test
	void aStoredKeyResolvesToWhatItWasGranted() {
		var credential = storeKey(null, grant("books", Permission.SEARCH));

		var principal = keys().resolve("Bearer " + credential);

		assertThat(principal.allows(Permission.SEARCH, "books"), is(true));
		assertThat(principal.allows(Permission.SEARCH, "movies"), is(false));
	}

	@Test
	void theSchemeIsReadWhateverCaseItIsWrittenIn() {
		var credential = storeKey(null, grant("books", Permission.SEARCH));

		assertThat(
			keys().resolve("bearer " + credential).allows(Permission.SEARCH, "books"),
			is(true)
		);
	}

	@Test
	void aWrongSecretUnderAKnownIdIsRefused() {
		var credential = storeKey(null, grant("books", Permission.SEARCH));
		var id = KeySecret.parse(credential).orElseThrow().id();

		var instance = keys();

		assertThrows(
			UnauthenticatedException.class,
			() -> instance.resolve("Bearer " + KeySecret.PREFIX + id + "_wrong")
		);
	}

	@Test
	void aLapsedKeyIsRefused() {
		var credential = storeKey(
			Instant.now().minusSeconds(1),
			grant("books", Permission.SEARCH)
		);

		var instance = keys();

		assertThrows(
			UnauthenticatedException.class,
			() -> instance.resolve("Bearer " + credential)
		);
	}

	@Test
	void nothingAtAllIsRefusedWhenAnonymousRequestsAreNotAnswered() {
		var instance = keys();

		assertThrows(UnauthenticatedException.class, () -> instance.resolve(null));
		assertThrows(UnauthenticatedException.class, () -> instance.resolve("Bearer "));
		assertThrows(UnauthenticatedException.class, () -> instance.resolve("Basic abc"));
	}

	@Test
	void aRequestWithoutACredentialIsAnsweredAsTheAnonymousKey() {
		var credential = storeKey(null, grant("books", Permission.SEARCH));
		var id = KeySecret.parse(credential).orElseThrow().id();

		var principal = keys(ROOT_KEY, id).resolve(null);

		assertThat(principal.allows(Permission.SEARCH, "books"), is(true));
	}

	@Test
	void theAnonymousKeyIsHonouredOnlyAsFarAsAnonymousMayGo() {
		/*
		 * Another node can widen the key after this one started and checked it,
		 * so what it is honoured as is narrowed at every request too.
		 */
		var credential = storeKey(
			null,
			grant("books", Permission.SEARCH, Permission.INDEXES_DELETE)
		);
		var id = KeySecret.parse(credential).orElseThrow().id();

		var principal = keys(ROOT_KEY, id).resolve(null);

		assertThat(principal.allows(Permission.SEARCH, "books"), is(true));
		assertThat(principal.allows(Permission.INDEXES_DELETE, "books"), is(false));
	}

	@Test
	void aCreatedKeyWorksAtOnce() {
		var instance = keys();
		var created = instance.create(
			"the loader",
			Lists.immutable.of(grant("books", Permission.DOCUMENTS_WRITE)),
			null
		);

		var principal = instance.resolve("Bearer " + created.credential());

		assertThat(principal.id(), is(created.key().id()));
		assertThat(principal.allows(Permission.DOCUMENTS_WRITE, "books"), is(true));
	}

	@Test
	void aRevokedKeyStopsWorking() {
		var instance = keys();
		var created = instance.create(
			"",
			Lists.immutable.of(grant("books", Permission.SEARCH)),
			null
		);

		instance.delete(created.key().id());

		assertThrows(
			UnauthenticatedException.class,
			() -> instance.resolve("Bearer " + created.credential())
		);
	}

	@Test
	void revokingAKeyThatIsNotThereSaysSo() {
		var instance = keys();

		assertThrows(KeyNotFoundException.class, () -> instance.delete("0123456789abcdef"));
	}

	@Test
	void aKeyMadeOnAnotherNodeIsFoundWithoutWaitingForTheNextRead() {
		var instance = keys();

		// Nothing has been read yet, and the key was never on this node
		var credential = storeKey(null, grant("books", Permission.SEARCH));

		assertThat(
			instance.resolve("Bearer " + credential).allows(Permission.SEARCH, "books"),
			is(true)
		);
	}

	@Test
	void aRunOfUnknownCredentialsCausesOneReadRatherThanOneEach() {
		var instance = keys();
		instance.resolve("Bearer " + storeKey(null, grant("books", Permission.SEARCH)));

		var reads = storage.reads;

		for(var i = 0; i < 5; i++) {
			assertThrows(
				UnauthenticatedException.class,
				() -> instance.resolve("Bearer " + KeySecret.PREFIX + "0123456789abcdef_nope")
			);
		}

		assertThat(storage.reads, is(reads));
	}

	@Test
	void aChangeThatLostARaceIsRebuiltOnTopOfTheOneThatWon() {
		var instance = keys();
		storage.refuseNextWrite = true;

		var created = instance.create(
			"",
			Lists.immutable.of(grant("books", Permission.SEARCH)),
			null
		);

		assertThat(
			instance.resolve("Bearer " + created.credential()).id(),
			is(created.key().id())
		);
	}

	@Test
	void aChangeThatKeepsLosingIsGivenUpOnRatherThanOverwriting() {
		var storageRefusingEverything = new InMemoryKeyStorage() {
			@Override
			public String write(KeyStore keys, String expectedVersion) {
				return null;
			}
		};

		var refusing = new Keys(
			storageRefusingEverything,
			AuthMode.KEYS,
			Optional.of(ROOT_KEY),
			Optional.empty(),
			Duration.ofSeconds(30)
		);
		started.add(refusing);

		assertThrows(
			KeyStorageException.class,
			() -> refusing.create(
				"",
				Lists.immutable.of(grant("books", Permission.SEARCH)),
				null
			)
		);
	}

	@Test
	void aNodeWithNowhereToKeepKeysCannotManageThem() {
		var instance = new Keys(
			new NoKeyStorage(),
			AuthMode.KEYS,
			Optional.of(ROOT_KEY),
			Optional.empty(),
			Duration.ofSeconds(30)
		);
		started.add(instance);

		assertThrows(
			KeyStorageException.class,
			() -> instance.create(
				"",
				Lists.immutable.of(grant("books", Permission.SEARCH)),
				null
			)
		);

		// Its root key still works, so the node is reachable
		assertThat(instance.resolve("Bearer " + ROOT_KEY).id(), is(Principal.ROOT));
	}

	@Test
	void checkingNothingAnswersEveryRequestAsAllowedEverything() {
		var instance = new Keys(
			storage,
			AuthMode.NONE,
			Optional.empty(),
			Optional.empty(),
			Duration.ofSeconds(30)
		);
		started.add(instance);

		assertThat(instance.resolve(null).id(), is(Principal.UNCHECKED));
		assertThat(instance.resolve(null).allows(Permission.KEYS_WRITE), is(true));
	}

	@Test
	void aNodeNobodyCouldAdministerRefusesToStart() {
		var instance = keys(null, null);

		var failure = assertThrows(IllegalStateException.class, () -> instance.onStart(null));
		assertThat(failure.getMessage().contains("keys.write"), is(true));
	}

	@Test
	void aStoredKeyThatCanManageKeysIsEnoughToStartWithoutARootKey() {
		storeKey(
			null,
			new Grant(Sets.immutable.of(Permission.KEYS_WRITE), Lists.immutable.empty())
		);

		keys(null, null).onStart(null);
	}

	@Test
	void anAnonymousKeyThatDoesNotExistRefusesToStart() {
		var instance = keys(ROOT_KEY, "0123456789abcdef");

		var failure = assertThrows(IllegalStateException.class, () -> instance.onStart(null));
		assertThat(failure.getMessage().contains("0123456789abcdef"), is(true));
	}

	@Test
	void anAnonymousKeyGrantedMoreThanSearchRefusesToStart() {
		var credential = storeKey(
			null,
			grant("books", Permission.SEARCH, Permission.DOCUMENTS_WRITE)
		);
		var id = KeySecret.parse(credential).orElseThrow().id();

		var instance = keys(ROOT_KEY, id);

		var failure = assertThrows(IllegalStateException.class, () -> instance.onStart(null));
		assertThat(failure.getMessage().contains("documents.write"), is(true));
	}

	@Test
	void aSearchOnlyAnonymousKeyStarts() {
		var credential = storeKey(null, grant("books", Permission.SEARCH));
		var id = KeySecret.parse(credential).orElseThrow().id();

		keys(ROOT_KEY, id).onStart(null);
	}

	@Test
	void aNodeThatCannotTellWhoMayAdministerItRefusesToStart() {
		storage.unreachable = true;

		var instance = keys(null, null);

		assertThrows(IllegalStateException.class, () -> instance.onStart(null));
	}

	@Test
	void aNodeWithARootKeyStartsEvenWhenTheStorageIsDown() {
		storage.unreachable = true;

		keys(ROOT_KEY, null).onStart(null);
	}
}
