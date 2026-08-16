package se.l4.exofind.engine.auth;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

import java.time.Instant;
import java.util.HexFormat;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.junit.jupiter.api.Test;

import com.google.protobuf.ByteString;

public class KeyStoreCodecTest {
	private static KeyDef.Builder storedKey(String id) {
		return KeyDef.newBuilder()
			.setId(id)
			.setHashAlgorithm(HashAlgorithm.HASH_ALGORITHM_SHA256)
			.setSecretHash(ByteString.copyFrom(HexFormat.of().parseHex(KeySecret.hash("secret"))))
			.setCreatedAt(1000);
	}

	@Test
	void aKeySurvivesBeingWrittenAndReadBack() {
		var key = new Key(
			"0123456789abcdef",
			KeySecret.hash("secret"),
			"the loader",
			Lists.immutable.of(
				new Grant(
					Sets.immutable.of(Permission.DOCUMENTS_WRITE, Permission.INDEXES_COMMIT),
					Lists.immutable.of("books", "movies-*")
				)
			),
			Instant.ofEpochMilli(1000),
			Instant.ofEpochMilli(5000)
		);

		var read = KeyStoreCodec.fromStored(KeyStoreCodec.toStored(Lists.immutable.of(key)));

		assertThat(read.size(), is(1));
		assertThat(read.getFirst(), is(key));
	}

	@Test
	void aKeyWithoutAnExpiryStaysWithoutOne() {
		var key = new Key(
			"0123456789abcdef",
			KeySecret.hash("secret"),
			"",
			Lists.immutable.of(
				new Grant(Sets.immutable.of(Permission.SEARCH), Lists.immutable.of("*"))
			),
			Instant.ofEpochMilli(1000),
			null
		);

		var read = KeyStoreCodec.fromStored(KeyStoreCodec.toStored(Lists.immutable.of(key)));

		assertThat(read.getFirst().expiresAt(), is((Instant) null));
	}

	@Test
	void keysAreWrittenOrderedByIdSoTheSameKeysProduceTheSameBytes() {
		var first = new Key(
			"aaaa", KeySecret.hash("a"), "", Lists.immutable.empty(), Instant.EPOCH, null
		);
		var second = new Key(
			"bbbb", KeySecret.hash("b"), "", Lists.immutable.empty(), Instant.EPOCH, null
		);

		var one = KeyStoreCodec.toStored(Lists.immutable.of(first, second));
		var other = KeyStoreCodec.toStored(Lists.immutable.of(second, first));

		assertThat(one, is(other));
	}

	@Test
	void aPermissionThisBuildDoesNotKnowGrantsNothing() {
		/*
		 * A grant only ever adds, so leaving out a name from a newer version is
		 * what the key would have meant here anyway - and the rest of the key
		 * keeps working.
		 */
		var stored = KeyStore.newBuilder()
			.addKeys(
				storedKey("0123456789abcdef")
					.addGrants(
						GrantDef.newBuilder()
							.addPermissions("search")
							.addPermissions("documents.teleport")
							.addIndexes("books")
					)
			)
			.build();

		var read = KeyStoreCodec.fromStored(stored);

		assertThat(read.size(), is(1));
		assertThat(
			read.getFirst().grants().getFirst().permissions().toList(),
			contains(Permission.SEARCH)
		);
	}

	@Test
	void aKeyNeedingAFeatureThisBuildLacksIsRefusedWhole() {
		var stored = KeyStore.newBuilder()
			.addKeys(
				storedKey("0123456789abcdef")
					.addRequiredFeatures("key.something_from_the_future")
					.addGrants(
						GrantDef.newBuilder().addPermissions("search").addIndexes("books")
					)
			)
			.build();

		assertThat(KeyStoreCodec.fromStored(stored).isEmpty(), is(true));
	}

	@Test
	void aKeyHashedWithSomethingThisBuildCannotCheckIsRefused() {
		var stored = KeyStore.newBuilder()
			.addKeys(
				storedKey("0123456789abcdef")
					.setHashAlgorithm(HashAlgorithm.HASH_ALGORITHM_UNSPECIFIED)
			)
			.build();

		assertThat(KeyStoreCodec.fromStored(stored).isEmpty(), is(true));
	}

	@Test
	void aKeyWithNothingToCheckASecretAgainstIsRefused() {
		var stored = KeyStore.newBuilder()
			.addKeys(storedKey("0123456789abcdef").clearSecretHash())
			.build();

		assertThat(KeyStoreCodec.fromStored(stored).isEmpty(), is(true));
	}

	@Test
	void oneRefusedKeyDoesNotTakeTheOthersWithIt() {
		var stored = KeyStore.newBuilder()
			.addKeys(storedKey("aaaaaaaaaaaaaaaa").addRequiredFeatures("key.unknown"))
			.addKeys(storedKey("bbbbbbbbbbbbbbbb"))
			.build();

		var read = KeyStoreCodec.fromStored(stored);

		assertThat(read.size(), is(1));
		assertThat(read.getFirst().id(), is("bbbbbbbbbbbbbbbb"));
	}
}
