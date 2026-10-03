package se.l4.exofind.engine.auth;

import java.time.Instant;
import java.util.Optional;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
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
 *
 * <p>Writing starts from the contents the change was built on. What a read
 * left out is written back unchanged: the keys this build refuses, the
 * permission names it does not know, and fields a newer version added to the
 * store, to a key, or to a grant.
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

		var refusal = refusalOf(stored);
		if(refusal != null) {
			logger.atError()
				.addKeyValue("key", id)
				.log("Refusing key, " + refusal);

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

	/**
	 * Get why this build refuses a stored key that has an id.
	 *
	 * @return
	 *   the reason, worded to follow "Refusing key, " in a log line, or
	 *   {@code null} when this build can read the key
	 */
	private static String refusalOf(KeyDef stored) {
		var unsupported = AuthFeatures.unsupportedIn(stored);
		if(unsupported.notEmpty()) {
			return "it needs features this node does not have: "
				+ unsupported.toSortedList().makeString(", ")
				+ ". Upgrade this node or the key will not work here";
		}

		if(stored.getHashAlgorithm() != HashAlgorithm.HASH_ALGORITHM_SHA256) {
			return "its secret is hashed with an algorithm this node cannot check against";
		}

		if(stored.getSecretHash().isEmpty()) {
			return "it carries no hash to check a secret against";
		}

		return null;
	}

	private static boolean isReadable(KeyDef stored) {
		return !stored.getId().isEmpty() && refusalOf(stored) == null;
	}

	private static ListIterable<Grant> grantsFrom(KeyDef stored) {
		return Lists.immutable.ofAll(stored.getGrantsList()).collect(KeyStoreCodec::grantFrom);
	}

	private static Grant grantFrom(GrantDef stored) {
		var permissions = Sets.mutable.<Permission>empty();
		for(var name : stored.getPermissionsList()) {
			/*
			 * A name from a newer version names something this node cannot do,
			 * so leaving it out is what the key would have meant here anyway.
			 */
			Permission.byId(name).ifPresent(permissions::add);
		}

		return new Grant(permissions, Lists.immutable.ofAll(stored.getIndexesList()));
	}

	/**
	 * Write the keys as the store holds them.
	 *
	 * <p>The keys are written in order of id, the refused ones included. The
	 * same contents and the same keys always produce the same bytes.
	 *
	 * @param previous
	 *   the contents the change was built on, or {@code null} when the store is
	 *   being written for the first time
	 * @param keys
	 *   the keys this build can read that the store is to hold. A key in
	 *   {@code previous} that this build refuses is kept.
	 * @return
	 */
	public static KeyStore toStored(KeyStore previous, ListIterable<Key> keys) {
		var store = previous == null
			? KeyStore.newBuilder()
			: previous.toBuilder();

		/*
		 * A key that was read is rewritten from the record the caller changed,
		 * and one that was refused is put back byte for byte. A caller only
		 * holds the keys this build can read, so a refused key that is missing
		 * from the records is not one the caller asked to remove.
		 */
		var readable = Maps.mutable.<String, KeyDef>empty();
		var written = Lists.mutable.<KeyDef>empty();
		for(var stored : store.getKeysList()) {
			if(isReadable(stored)) {
				readable.put(stored.getId(), stored);
			} else {
				written.add(stored);
			}
		}

		for(var key : keys) {
			written.add(toStored(readable.get(key.id()), key));
		}

		store.clearKeys();
		store.addAllKeys(written.sortThisBy(KeyDef::getId));

		return store.build();
	}

	/**
	 * Write one key over the stored key it was read from.
	 *
	 * @param previous
	 *   the stored key the record was read from, or {@code null} for a key the
	 *   store does not hold yet
	 * @param key
	 * @return
	 */
	private static KeyDef toStored(KeyDef previous, Key key) {
		var builder = previous == null
			? KeyDef.newBuilder()
			: previous.toBuilder();

		builder
			.setId(key.id())
			.setHashAlgorithm(HashAlgorithm.HASH_ALGORITHM_SHA256)
			.setSecretHash(
				ByteString.copyFrom(HexFormat.of().parseHex(key.secretHash()))
			)
			.setCreatedAt(key.createdAt().toEpochMilli());

		if(!key.description().isEmpty()) {
			builder.setDescription(key.description());
		} else {
			builder.clearDescription();
		}

		if(key.expiresAt() != null) {
			builder.setExpiresAt(key.expiresAt().toEpochMilli());
		} else {
			builder.clearExpiresAt();
		}

		/*
		 * A grant the record still holds as it was read is put back as it was
		 * stored, with the permission names this build dropped when it read
		 * it. Grants are matched by what they read as, not by position, so a
		 * change to one grant leaves the others as they were stored.
		 */
		var unmatched = Lists.mutable.ofAll(builder.getGrantsList());
		builder.clearGrants();

		for(var grant : key.grants()) {
			var stored = unmatched.detect(candidate -> grantFrom(candidate).equals(grant));

			if(stored != null) {
				unmatched.remove(stored);
				builder.addGrants(stored);
			} else {
				builder.addGrants(toStored(grant));
			}
		}

		return builder.build();
	}

	private static GrantDef toStored(Grant grant) {
		var builder = GrantDef.newBuilder();

		for(var permission : grant.permissions().toSortedListBy(Permission::id)) {
			builder.addPermissions(permission.id());
		}

		builder.addAllIndexes(grant.indexes().toList());
		return builder.build();
	}
}
