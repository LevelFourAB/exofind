package se.l4.exofind.engine.auth;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;

public class KeysTest {
	private static final String ROOT_KEY = "a-long-random-root-key";

	/**
	 * A feature name no build has. A newer version writes such a name into a
	 * key that narrows what it allows.
	 */
	private static final String FUTURE_FEATURE = "key.from_the_future";

	/**
	 * A permission name no build has. A newer version can grant it.
	 */
	private static final String FUTURE_PERMISSION = "documents.teleport";

	/**
	 * A field number that keys.proto does not use yet.
	 */
	private static final int FUTURE_FIELD = 99;

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
		KeyStore previous = null;

		try {
			if(storage.read(null) instanceof KeyStorage.Read.Loaded loaded) {
				previous = loaded.keys();
				existing.addAll(KeyStoreCodec.fromStored(previous).toList());
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

		storage.set(KeyStoreCodec.toStored(previous, existing.toImmutable()));
		return generated.credential();
	}

	/**
	 * Start a key as a node of any version would store it, granted
	 * {@code search} on {@code books}.
	 */
	private static KeyDef.Builder storedKey(String id) {
		return KeyDef.newBuilder()
			.setId(id)
			.setHashAlgorithm(HashAlgorithm.HASH_ALGORITHM_SHA256)
			.setSecretHash(ByteString.copyFrom(HexFormat.of().parseHex(KeySecret.hash("secret"))))
			.setCreatedAt(1000)
			.addGrants(GrantDef.newBuilder().addPermissions("search").addIndexes("books"));
	}

	private KeyDef stored(String id) throws IOException {
		if(!(storage.read(null) instanceof KeyStorage.Read.Loaded loaded)) {
			throw new AssertionError("The store holds nothing");
		}

		return loaded.keys().getKeysList()
			.stream()
			.filter(key -> key.getId().equals(id))
			.findFirst()
			.orElseThrow(() -> new AssertionError("The store no longer holds key " + id));
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

	/**
	 * A value that says it is a hash and is not one would be a root key that
	 * never matches, found out at the first lockout rather than at startup.
	 */
	@Test
	void aRootKeyHashThatIsNotOneRefusesToStart() {
		assertThrows(IllegalStateException.class, () -> keys("sha256:abc", null));
		assertThrows(
			IllegalStateException.class,
			() -> keys("sha256:" + KeySecret.hash(ROOT_KEY).replace('a', 'x'), null)
		);
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

	/**
	 * Only a request with no header at all presented nothing. One that
	 * presented something this node does not read is refused rather than
	 * served as though it had presented nothing.
	 */
	@Test
	void aHeaderThatIsNotABearerTokenIsRefusedEvenWhereAnonymousIsAnswered() {
		var credential = storeKey(null, grant("books", Permission.SEARCH));
		var id = KeySecret.parse(credential).orElseThrow().id();

		var instance = keys(ROOT_KEY, id);

		assertThrows(UnauthenticatedException.class, () -> instance.resolve("Basic abc"));
		assertThrows(UnauthenticatedException.class, () -> instance.resolve("Bearer "));
		assertThrows(UnauthenticatedException.class, () -> instance.resolve(""));
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

	/**
	 * A node with nowhere to keep keys reads as though none had ever been
	 * created, which would otherwise be listed as a deployment holding no key.
	 */
	@Test
	void aNodeWithNowhereToKeepKeysSaysSoRatherThanListingNone() {
		var instance = new Keys(
			new NoKeyStorage(),
			AuthMode.KEYS,
			Optional.of(ROOT_KEY),
			Optional.empty(),
			Duration.ofSeconds(30)
		);
		started.add(instance);

		assertThrows(KeyStorageException.class, instance::list);
	}

	@Test
	void revokingTheLastKeyThatCouldMakeAnotherIsRefusedWithoutARootKey() {
		var credential = storeKey(
			null,
			new Grant(Sets.immutable.of(Permission.KEYS_WRITE), Lists.immutable.empty())
		);
		var id = KeySecret.parse(credential).orElseThrow().id();

		var instance = keys(null, null);
		instance.onStart(null);

		assertThrows(KeyInUseException.class, () -> instance.delete(id));

		// The key is still there, so the node still has a way in
		assertThat(instance.resolve("Bearer " + credential).id(), is(id));
	}

	@Test
	void revokingAnAdministratorIsAllowedOnceAnotherOneExists() {
		var credential = storeKey(
			null,
			new Grant(Sets.immutable.of(Permission.KEYS_WRITE), Lists.immutable.empty())
		);
		var id = KeySecret.parse(credential).orElseThrow().id();

		storeKey(
			null,
			new Grant(Sets.immutable.of(Permission.KEYS_WRITE), Lists.immutable.empty())
		);

		var instance = keys(null, null);
		instance.onStart(null);
		instance.delete(id);

		assertThrows(
			UnauthenticatedException.class,
			() -> instance.resolve("Bearer " + credential)
		);
	}

	@Test
	void revokingTheLastAdministratorIsAllowedWhereThereIsARootKey() {
		var credential = storeKey(
			null,
			new Grant(Sets.immutable.of(Permission.KEYS_WRITE), Lists.immutable.empty())
		);
		var id = KeySecret.parse(credential).orElseThrow().id();

		var instance = keys(ROOT_KEY, null);
		instance.onStart(null);
		instance.delete(id);

		assertThat(instance.resolve("Bearer " + ROOT_KEY).id(), is(Principal.ROOT));
	}

	@Test
	void revokingTheKeyThatAnswersRequestsWithoutACredentialIsRefused() {
		var credential = storeKey(null, grant("books", Permission.SEARCH));
		var id = KeySecret.parse(credential).orElseThrow().id();

		var instance = keys(ROOT_KEY, id);
		instance.onStart(null);

		assertThrows(KeyInUseException.class, () -> instance.delete(id));

		// The node goes on answering requests that carry no credential
		assertThat(instance.resolve(null).allows(Permission.SEARCH, "books"), is(true));
	}

	@Test
	void rotatingAKeyReplacesTheCredentialAndKeepsWhatItAllows() {
		var instance = keys();
		var created = instance.create(
			"the loader",
			Lists.immutable.of(grant("books", Permission.DOCUMENTS_WRITE)),
			null
		);

		// Read back rather than taken from the create, which is what the store holds
		var stored = instance.list().detect(key -> key.id().equals(created.key().id()));

		var rotated = instance.rotate(created.key().id());

		assertThat(rotated.key().id(), is(created.key().id()));
		assertThat(rotated.key().description(), is("the loader"));
		assertThat(rotated.key().createdAt(), is(stored.createdAt()));
		assertThat(rotated.credential(), is(not(created.credential())));

		var principal = instance.resolve("Bearer " + rotated.credential());
		assertThat(principal.id(), is(created.key().id()));
		assertThat(principal.allows(Permission.DOCUMENTS_WRITE, "books"), is(true));
	}

	@Test
	void theCredentialARotationReplacedStopsWorking() {
		var instance = keys();
		var created = instance.create(
			"",
			Lists.immutable.of(grant("books", Permission.SEARCH)),
			null
		);

		instance.rotate(created.key().id());

		assertThrows(
			UnauthenticatedException.class,
			() -> instance.resolve("Bearer " + created.credential())
		);
	}

	@Test
	void rotatingAKeyThatIsNotThereSaysSo() {
		assertThrows(KeyNotFoundException.class, () -> keys().rotate("0123456789abcdef"));
	}

	/**
	 * A newer node wrote a key that this build refuses. This node must not
	 * delete that key when it writes the store for its own change.
	 */
	@Test
	void aKeyThisBuildRefusesSurvivesThisNodeCreatingAnotherKey() throws IOException {
		// Given the store holds a key that needs a feature this build lacks
		storage.set(
			KeyStore.newBuilder()
				.addKeys(storedKey("aaaaaaaaaaaaaaaa").addRequiredFeatures(FUTURE_FEATURE))
				.build()
		);

		// When this node creates another key
		keys().create("loader", Lists.immutable.of(grant("books", Permission.SEARCH)), null);

		// Then the refused key is still in the store, with its feature
		var kept = stored("aaaaaaaaaaaaaaaa");
		assertThat(kept.getRequiredFeaturesList(), contains(FUTURE_FEATURE));
	}

	/**
	 * A grant names a permission a newer version added. This node must not
	 * remove that permission when it writes the store for another change.
	 */
	@Test
	void aPermissionThisBuildDoesNotKnowSurvivesThisNodeCreatingAnotherKey() throws IOException {
		// Given the store holds a key granted a permission this build lacks
		storage.set(
			KeyStore.newBuilder()
				.addKeys(
					storedKey("bbbbbbbbbbbbbbbb")
						.clearGrants()
						.addGrants(
							GrantDef.newBuilder()
								.addPermissions("search")
								.addPermissions(FUTURE_PERMISSION)
								.addIndexes("books")
						)
				)
				.build()
		);

		// When this node creates another key
		keys().create("loader", Lists.immutable.of(grant("books", Permission.SEARCH)), null);

		// Then the stored grant still names the permission
		var kept = stored("bbbbbbbbbbbbbbbb");
		assertThat(kept.getGrants(0).getPermissionsList(), hasItem(FUTURE_PERMISSION));
	}

	/**
	 * A rotation keeps the grants of the key. A node that does not know one
	 * permission of the grant must keep it all the same.
	 */
	@Test
	void rotatingAKeyKeepsAPermissionThisBuildDoesNotKnow() throws IOException {
		// Given the store holds a key granted a permission this build lacks
		storage.set(
			KeyStore.newBuilder()
				.addKeys(
					storedKey("cccccccccccccccc")
						.clearGrants()
						.addGrants(
							GrantDef.newBuilder()
								.addPermissions("search")
								.addPermissions(FUTURE_PERMISSION)
								.addIndexes("books")
						)
				)
				.build()
		);

		// When this node rotates the credential of that key
		keys().rotate("cccccccccccccccc");

		// Then the stored grant still names the permission
		var kept = stored("cccccccccccccccc");
		assertThat(kept.getGrants(0).getPermissionsList(), hasItem(FUTURE_PERMISSION));
	}

	/**
	 * A newer version can add a field to a key. This node must keep that field
	 * when it writes the store for a change to another key.
	 */
	@Test
	void aFieldANewerVersionAddedSurvivesThisNodeRevokingAnotherKey() throws IOException {
		// Given the store holds a key with a field this build has no code for
		var unknown = UnknownFieldSet.newBuilder()
			.addField(FUTURE_FIELD, UnknownFieldSet.Field.newBuilder().addVarint(7).build())
			.build();

		storage.set(
			KeyStore.newBuilder()
				.addKeys(storedKey("dddddddddddddddd").setUnknownFields(unknown))
				.addKeys(storedKey("eeeeeeeeeeeeeeee"))
				.build()
		);

		// When this node revokes the other key
		keys().delete("eeeeeeeeeeeeeeee");

		// Then the first key still carries the field
		var kept = stored("dddddddddddddddd");
		assertThat(kept.getUnknownFields().hasField(FUTURE_FIELD), is(true));
	}
}
