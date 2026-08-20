package se.l4.exofind.engine.auth;

import java.time.Instant;
import java.util.Optional;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.ListIterable;

import com.google.protobuf.ByteString;

import se.l4.exofind.engine.logging.Log;

import java.util.HexFormat;

/**
 * Reading and writing the stored form of the key store.
 *
 * <p>Reading refuses a key it can not honour exactly as written rather than
 * honouring the part it understands. A grant is additive, so an unknown
 * permission name is dropped and grants nothing; everything else that can not
 * be read - a hash this build can not check against, a required feature it does
 * not have - takes the whole key out of the store on this node.
 */
public final class KeyStoreCodec {
	private static final Log logger = Log.of(KeyStoreCodec.class);

	private KeyStoreCodec() {
	}

	/**
	 * Read every key this node can honour.
	 *
	 * @param stored
	 * @return
	 *   the keys, in the order they are stored, without the ones this build
	 *   refuses
	 */
	public static ListIterable<Key> fromStored(KeyStore stored) {
		var keys = Lists.mutable.<Key>empty();

		for(var key : stored.getKeysList()) {
			fromStored(key).ifPresent(keys::add);
		}

		return keys.toImmutable();
	}

	/**
	 * Read one key.
	 *
	 * @param stored
	 * @return
	 *   empty when this build refuses the key, which is logged with the reason
	 */
	public static Optional<Key> fromStored(KeyDef stored) {
		var id = stored.getId();
		if(id.isEmpty()) {
			logger.atWarn().log("Ignoring a stored key that has no id");
			return Optional.empty();
		}

		var unsupported = AuthFeatures.unsupportedIn(stored);
		if(unsupported.notEmpty()) {
			logger.atError()
				.addKeyValue("key", id)
				.log(
					"Refusing key, it needs features this node does not have: "
						+ unsupported.toSortedList().makeString(", ")
						+ ". Upgrade this node or the key will not work here"
				);

			return Optional.empty();
		}

		if(stored.getHashAlgorithm() != HashAlgorithm.HASH_ALGORITHM_SHA256) {
			logger.atError()
				.addKeyValue("key", id)
				.log(
					"Refusing key, its secret is hashed with an algorithm this node"
						+ " cannot check against"
				);

			return Optional.empty();
		}

		if(stored.getSecretHash().isEmpty()) {
			logger.atError()
				.addKeyValue("key", id)
				.log("Refusing key, it carries no hash to check a secret against");

			return Optional.empty();
		}

		return Optional.of(
			new Key(
				id,
				HexFormat.of().formatHex(stored.getSecretHash().toByteArray()),
				stored.getDescription(),
				grantsFrom(stored),
				Instant.ofEpochMilli(stored.getCreatedAt()),
				stored.hasExpiresAt() ? Instant.ofEpochMilli(stored.getExpiresAt()) : null
			)
		);
	}

	private static ListIterable<Grant> grantsFrom(KeyDef stored) {
		var grants = Lists.mutable.<Grant>empty();

		for(var grant : stored.getGrantsList()) {
			var permissions = Sets.mutable.<Permission>empty();
			for(var name : grant.getPermissionsList()) {
				/*
				 * A name from a newer version names something this node cannot
				 * do, so leaving it out is what the key would have meant here
				 * anyway.
				 */
				Permission.byId(name).ifPresent(permissions::add);
			}

			grants.add(new Grant(permissions, Lists.immutable.ofAll(grant.getIndexesList())));
		}

		return grants.toImmutable();
	}

	/**
	 * Write the keys, ordered by id so that the same set of keys always
	 * produces the same bytes.
	 *
	 * @param keys
	 * @return
	 */
	public static KeyStore toStored(ListIterable<Key> keys) {
		var builder = KeyStore.newBuilder();

		for(var key : keys.toSortedListBy(Key::id)) {
			builder.addKeys(toStored(key));
		}

		return builder.build();
	}

	private static KeyDef toStored(Key key) {
		var builder = KeyDef.newBuilder()
			.setId(key.id())
			.setHashAlgorithm(HashAlgorithm.HASH_ALGORITHM_SHA256)
			.setSecretHash(
				ByteString.copyFrom(HexFormat.of().parseHex(key.secretHash()))
			)
			.setCreatedAt(key.createdAt().toEpochMilli());

		if(!key.description().isEmpty()) {
			builder.setDescription(key.description());
		}

		if(key.expiresAt() != null) {
			builder.setExpiresAt(key.expiresAt().toEpochMilli());
		}

		for(var grant : key.grants()) {
			var grantBuilder = GrantDef.newBuilder();

			for(var permission : grant.permissions().toSortedListBy(Permission::id)) {
				grantBuilder.addPermissions(permission.id());
			}

			grantBuilder.addAllIndexes(grant.indexes().toList());
			builder.addGrants(grantBuilder);
		}

		return builder.build();
	}
}
