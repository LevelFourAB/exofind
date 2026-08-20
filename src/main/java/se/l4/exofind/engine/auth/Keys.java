package se.l4.exofind.engine.auth;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.map.MapIterable;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import se.l4.exofind.engine.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

/**
 * The keys of the deployment as this node knows them, and who a request is
 * answered as.
 *
 * <p>Keys live in the storage rather than in configuration, so a key created on
 * one node works on every node and a leaked key can be revoked without
 * redeploying anything. Each node keeps its own copy and re-reads it every
 * {@code exofind.auth.refresh-interval}, which is also how long revoking a key
 * can take to reach a node that is already holding it. A credential naming a
 * key this node has not seen is looked up once per interval, so a key created
 * a moment ago works without waiting for the next read.
 *
 * <p>Managing keys does not need the indexer role. The store is a single object
 * replaced conditionally on the version it was read at, so a node that raced
 * another is refused and tries again on top of what the other wrote.
 *
 * <p>Besides the stored keys a node accepts its configured root key, which is
 * allowed everything and is not stored anywhere. It exists to create the first
 * key and to get back in after the last key that could manage keys was deleted.
 *
 * <p>Safe for concurrent use.
 */
@Singleton
public class Keys {
	private static final Log logger = Log.of(Keys.class);

	/**
	 * What a root key configured as a hash rather than as the key itself starts
	 * with.
	 */
	private static final String HASH_PREFIX = "sha256:";

	private static final String BEARER = "Bearer ";

	/**
	 * How many times a change to the keys is rebuilt on top of a concurrent one
	 * before giving up. Losing three races in a row means something is writing
	 * keys continuously, which is not a state waiting longer improves.
	 */
	private static final int WRITE_ATTEMPTS = 3;

	private final KeyStorage storage;
	private final AuthMode mode;
	private final Duration refreshInterval;

	/**
	 * Hash of the root key, or {@code null} when this node has none.
	 */
	private final String rootKeyHash;

	/**
	 * Id of the key requests carrying no credential are answered as, or
	 * {@code null} when this node refuses them.
	 */
	private final String anonymousKeyId;

	private final ScheduledExecutorService executor;

	/**
	 * Lock held while deciding whether a lookup that missed may go and read the
	 * store, so a run of unknown credentials causes one read rather than one
	 * each.
	 */
	private final Object forcedReadLock = new Object();

	private volatile Snapshot snapshot = Snapshot.empty();
	private long lastForcedReadNanos;
	private boolean forcedReadEver;

	/**
	 * The keys as of one read of the store, together with the version they were
	 * read at.
	 */
	private record Snapshot(
		ListIterable<Key> keys,
		MapIterable<String, Key> byId,
		String version
	) {
		static Snapshot empty() {
			return new Snapshot(Lists.immutable.empty(), Maps.immutable.empty(), null);
		}

		static Snapshot of(ListIterable<Key> keys, String version) {
			var byId = Maps.mutable.<String, Key>empty();
			for(var key : keys) {
				byId.put(key.id(), key);
			}

			return new Snapshot(keys, byId.toImmutable(), version);
		}
	}

	/**
	 * A key together with the only copy of its credential there will ever be.
	 */
	public record Created(Key key, String credential) {
	}

	public Keys(
		KeyStorage storage,
		@ConfigProperty(name = "exofind.auth.mode", defaultValue = "keys") AuthMode mode,
		@ConfigProperty(name = "exofind.auth.root-key") Optional<String> rootKey,
		@ConfigProperty(name = "exofind.auth.anonymous-key") Optional<String> anonymousKey,
		@ConfigProperty(name = "exofind.auth.refresh-interval", defaultValue = "10s")
		Duration refreshInterval
	) {
		this.storage = storage;
		this.mode = mode;
		this.refreshInterval = refreshInterval;
		this.rootKeyHash = rootKey.filter(value -> !value.isBlank())
			.map(Keys::toRootKeyHash)
			.orElse(null);
		this.anonymousKeyId = anonymousKey.filter(value -> !value.isBlank()).orElse(null);

		this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			var thread = new Thread(runnable, "auth-keys");
			thread.setDaemon(true);
			return thread;
		});
	}

	/**
	 * Read the root key as it was configured. A value that already is a hash is
	 * taken as one, so a deployment need not put the key itself in an
	 * environment variable.
	 */
	private static String toRootKeyHash(String configured) {
		var value = configured.trim();

		return value.startsWith(HASH_PREFIX)
			? value.substring(HASH_PREFIX.length()).toLowerCase()
			: KeySecret.hash(value);
	}

	void onStart(@Observes StartupEvent event) {
		if(mode == AuthMode.NONE) {
			logger.atWarn().log(
				"Authentication is turned off, every request is answered as though it"
					+ " were allowed everything. Set EXOFIND_AUTH_MODE=keys to check"
					+ " credentials"
			);

			return;
		}

		var read = read();

		/*
		 * A node nobody can administer is worse than one that does not come up,
		 * so this is checked before anything is served rather than found out
		 * the first time someone tries to manage a key.
		 */
		if(!read && rootKeyHash == null) {
			throw new IllegalStateException(
				"The keys could not be read from storage and this node has no root key,"
					+ " so there is no way to tell whether anyone can administer it."
					+ " Set EXOFIND_AUTH_ROOT_KEY, or fix the storage and start again"
			);
		}

		if(read) {
			verifyAdministrable();
			verifyAnonymousKey();
		}

		executor.scheduleWithFixedDelay(
			this::read,
			refreshInterval.toMillis(),
			refreshInterval.toMillis(),
			TimeUnit.MILLISECONDS
		);
	}

	@PreDestroy
	void stop() {
		executor.shutdownNow();
	}

	/**
	 * Refuse to run if no credential could administer this node.
	 */
	private void verifyAdministrable() {
		if(rootKeyHash != null) {
			return;
		}

		var administrable = snapshot.keys().anySatisfy(
			key -> key.grants().anySatisfy(grant -> grant.allows(Permission.KEYS_WRITE))
		);

		if(!administrable) {
			throw new IllegalStateException(
				"No stored key is granted `keys.write` and this node has no root key, so"
					+ " no credential could ever create one. Set EXOFIND_AUTH_ROOT_KEY"
			);
		}
	}

	/**
	 * Refuse to run if the key this node would answer unauthenticated requests
	 * as is not one it should be answering them as.
	 */
	private void verifyAnonymousKey() {
		if(anonymousKeyId == null) {
			return;
		}

		var key = snapshot.byId().get(anonymousKeyId);
		if(key == null) {
			throw new IllegalStateException(
				"EXOFIND_AUTH_ANONYMOUS_KEY names `" + anonymousKeyId + "`, which is not"
					+ " a key this deployment holds"
			);
		}

		var refused = key.grants()
			.flatCollect(Grant::permissions)
			.reject(Permission::isAnonymousAllowed)
			.collect(Permission::id)
			.toSortedList()
			.distinct();

		if(refused.notEmpty()) {
			throw new IllegalStateException(
				"EXOFIND_AUTH_ANONYMOUS_KEY names `" + anonymousKeyId + "`, which is"
					+ " granted " + refused.makeString(", ") + ". A key that answers"
					+ " requests carrying no credential may only be granted `search`"
			);
		}
	}

	/**
	 * Work out who a request is being answered as.
	 *
	 * @param authorization
	 *   the {@code Authorization} header, or {@code null} when the request
	 *   carried none
	 * @return
	 * @throws UnauthenticatedException
	 *   if the credential is not one this node accepts, or there is none and
	 *   this node does not answer requests without one
	 */
	public Principal resolve(String authorization) {
		if(mode == AuthMode.NONE) {
			return Principal.unchecked();
		}

		var credential = toCredential(authorization);
		if(credential == null) {
			return anonymous();
		}

		var presented = KeySecret.parse(credential).orElse(null);
		if(presented != null) {
			var key = find(presented.id());

			if(key != null) {
				if(
					!KeySecret.matches(presented.secret(), key.secretHash())
						|| key.isExpired(Instant.now())
				) {
					throw new UnauthenticatedException();
				}

				return Principal.of(key);
			}
		}

		/*
		 * The root key is opaque rather than shaped like a stored credential,
		 * so it is compared against whatever was presented - after the stored
		 * keys, which is where a credential normally matches.
		 */
		if(
			rootKeyHash != null
				&& KeySecret.constantTimeEquals(KeySecret.hash(credential), rootKeyHash)
		) {
			return Principal.root();
		}

		throw new UnauthenticatedException();
	}

	private Principal anonymous() {
		if(anonymousKeyId == null) {
			throw new UnauthenticatedException();
		}

		var key = find(anonymousKeyId);
		if(key == null || key.isExpired(Instant.now())) {
			throw new UnauthenticatedException();
		}

		return Principal.anonymous(key);
	}

	private static String toCredential(String authorization) {
		if(authorization == null) {
			return null;
		}

		var value = authorization.trim();
		if(value.length() <= BEARER.length()
			|| !value.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
			return null;
		}

		return value.substring(BEARER.length()).trim();
	}

	/**
	 * Find a key by id, going back to the store once per refresh interval when
	 * it is not one this node has seen.
	 */
	private Key find(String id) {
		var key = snapshot.byId().get(id);
		if(key != null) {
			return key;
		}

		if(!forcedReadAllowed()) {
			return null;
		}

		read();
		return snapshot.byId().get(id);
	}

	private boolean forcedReadAllowed() {
		var now = System.nanoTime();

		synchronized(forcedReadLock) {
			if(forcedReadEver && now - lastForcedReadNanos < refreshInterval.toNanos()) {
				return false;
			}

			forcedReadEver = true;
			lastForcedReadNanos = now;
			return true;
		}
	}

	/**
	 * Bring this node's copy of the keys up to date.
	 *
	 * @return
	 *   whether the store could be read, {@code false} leaving the copy as it
	 *   was
	 */
	private boolean read() {
		var current = snapshot;

		try {
			switch(storage.read(current.version())) {
				case KeyStorage.Read.Unchanged unchanged -> {
					// The copy this node holds is the stored one
				}
				case KeyStorage.Read.Absent absent -> {
					if(current.version() != null) {
						snapshot = Snapshot.empty();
					}
				}
				case KeyStorage.Read.Loaded loaded -> snapshot = Snapshot.of(
					KeyStoreCodec.fromStored(loaded.keys()),
					loaded.version()
				);
			}

			return true;
		} catch(IOException e) {
			logger.atWarn()
				.setCause(e)
				.log("Could not read the keys, using the copy this node holds; " + e.getMessage());

			return false;
		}
	}

	/**
	 * Every key of the deployment, ordered by id.
	 *
	 * <p>Read from the store rather than from this node's copy, so a key
	 * created elsewhere is listed as soon as it exists.
	 *
	 * @return
	 * @throws KeyStorageException
	 *   if the store could not be read
	 */
	public ListIterable<Key> list() {
		if(!read()) {
			throw KeyStorageException.ioError(null);
		}

		return snapshot.keys().toSortedListBy(Key::id);
	}

	/**
	 * Create a key.
	 *
	 * <p>The credential comes back once and is never stored, so it is the
	 * caller's only chance to keep it.
	 *
	 * @param description
	 *   what the key is for, for whoever lists the keys later
	 * @param grants
	 * @param expiresAt
	 *   when the key stops working, or {@code null} for a key that does not
	 * @return
	 * @throws KeyStorageException
	 *   if this node cannot keep keys, the storage could not be reached, or the
	 *   keys kept being changed by someone else
	 */
	public Created create(String description, ListIterable<Grant> grants, Instant expiresAt) {
		var generated = KeySecret.generate();
		var key = new Key(
			generated.id(),
			generated.secretHash(),
			description == null ? "" : description,
			grants,
			Instant.now(),
			expiresAt
		);

		change(keys -> Lists.immutable.ofAll(keys).newWith(key));

		logger.atInfo()
			.addKeyValue("key", key.id())
			.log("Created API key");

		return new Created(key, generated.credential());
	}

	/**
	 * Revoke a key.
	 *
	 * <p>The key stops working on this node at once and on every other node
	 * within its refresh interval.
	 *
	 * @param id
	 * @throws KeyNotFoundException
	 *   if no key is stored under that id
	 * @throws KeyStorageException
	 *   if this node cannot keep keys, the storage could not be reached, or the
	 *   keys kept being changed by someone else
	 */
	public void delete(String id) {
		change(keys -> {
			if(keys.noneSatisfy(key -> key.id().equals(id))) {
				throw new KeyNotFoundException(id);
			}

			return keys.reject(key -> key.id().equals(id));
		});

		logger.atInfo()
			.addKeyValue("key", id)
			.log("Revoked API key");
	}

	/**
	 * Rewrite the stored keys, rebuilding the change on top of whatever else
	 * was written in the meantime rather than overwriting it.
	 */
	private void change(UnaryOperator<ListIterable<Key>> change) {
		if(!storage.isAvailable()) {
			throw KeyStorageException.unavailable();
		}

		for(int attempt = 0; attempt < WRITE_ATTEMPTS; attempt++) {
			if(!read()) {
				throw KeyStorageException.ioError(null);
			}

			var current = snapshot;
			var updated = change.apply(current.keys());

			String version;
			try {
				version = storage.write(KeyStoreCodec.toStored(updated), current.version());
			} catch(IOException e) {
				throw KeyStorageException.ioError(e);
			}

			if(version != null) {
				snapshot = Snapshot.of(updated, version);
				return;
			}
		}

		throw KeyStorageException.conflict();
	}

	/**
	 * Whether this node checks credentials at all.
	 */
	public boolean isEnabled() {
		return mode != AuthMode.NONE;
	}

	/**
	 * Whether this node has a root key configured, for reporting how a
	 * deployment is set up without saying what the key is.
	 */
	public boolean hasRootKey() {
		return rootKeyHash != null;
	}

	/**
	 * The id of the key requests carrying no credential are answered as, empty
	 * when this node refuses them.
	 */
	public Optional<String> anonymousKeyId() {
		return Optional.ofNullable(anonymousKeyId);
	}
}
