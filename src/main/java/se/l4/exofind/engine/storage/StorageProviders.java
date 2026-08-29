package se.l4.exofind.engine.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.auth.KeyStorage;
import se.l4.exofind.engine.auth.LocalKeyStorage;
import se.l4.exofind.engine.auth.NoKeyStorage;
import se.l4.exofind.engine.auth.ObjectStorageKeyStorage;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.LocalRegistryStorage;
import se.l4.exofind.engine.index.registry.ObjectStorageRegistryAudit;
import se.l4.exofind.engine.index.registry.ObjectStorageRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryAudit;
import se.l4.exofind.engine.index.registry.RegistryStorage;
import se.l4.exofind.engine.index.settings.LocalSearchSettingsStorage;
import se.l4.exofind.engine.index.settings.NoSearchSettingsStorage;
import se.l4.exofind.engine.index.settings.ObjectStorageSearchSettingsStorage;
import se.l4.exofind.engine.index.settings.SearchSettingsStorage;
import se.l4.exofind.engine.index.state.IndexerOwnership;
import se.l4.exofind.engine.index.state.LocalIndexerOwnership;
import se.l4.exofind.engine.index.state.NoopSyncProvider;
import se.l4.exofind.engine.index.state.ObjectStorageIndexerOwnership;
import se.l4.exofind.engine.index.state.ObjectStorageSyncProvider;
import se.l4.exofind.engine.index.state.StateSyncProvider;
import se.l4.exofind.engine.metrics.StorageMetricsInterceptor;
import io.micrometer.core.instrument.MeterRegistry;
import se.l4.exofind.engine.logging.Log;
import se.l4.exofind.engine.reindex.LocalReindexJobStorage;
import se.l4.exofind.engine.reindex.ObjectStorageReindexJobStorage;
import se.l4.exofind.engine.reindex.ReindexJobStorage;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Where this node keeps the indexes, the registry naming them, the keys that
 * reach them and the indexer role.
 *
 * <p>All four are produced here, from the one {@link StorageMode} the
 * deployment named, because they are one decision rather than four. Answering
 * them apart - each working out for itself whether there is an object storage
 * to use - lets them disagree, and a node whose registry is local while its
 * keys are remote is a node in a state nobody designed.
 */
@ApplicationScoped
public class StorageProviders {
	private static final Log logger = Log.of(StorageProviders.class);

	/**
	 * Name the registry of a node storing locally is kept under, next to the
	 * directory holding the indexes themselves.
	 */
	private static final String LOCAL_REGISTRY_FILE = "registry.ef.bin";

	/**
	 * Name the keys of a node storing locally are kept under, beside the
	 * registry.
	 */
	private static final String LOCAL_KEYS_FILE = "keys.ef.bin";

	/**
	 * Directory the search settings of a node storing locally are kept in,
	 * one file per index, beside the registry.
	 */
	private static final String LOCAL_SETTINGS_DIRECTORY = "settings";

	/**
	 * Read the mode the deployment named, and say once what it means.
	 *
	 * <p>Announced at startup rather than left to be inferred from what does
	 * or does not work, because the default is the mode that is easy to reach
	 * by accident: a deployment that meant to share an object storage and got
	 * a variable wrong should be told it is running alone while it still holds
	 * nothing.
	 */
	@Startup
	@Produces
	@Singleton
	public StorageMode storageMode(
		@ConfigProperty(name = "exofind.storage.mode", defaultValue = "local") String mode,
		@ConfigProperty(name = "exofind.storage.local.directory") Path storageDirectory,
		@ConfigProperty(name = "exofind.indexes.disk.max-size") Optional<String> diskMaxSize,
		@ConfigProperty(name = "exofind.storage.remote.url") Optional<String> remoteUrl
	) {
		var resolved = switch(mode.trim().toLowerCase(Locale.ROOT)) {
			case "local" -> StorageMode.LOCAL;
			case "object" -> StorageMode.OBJECT;
			default -> throw new IllegalStateException(
				"EXOFIND_STORAGE_MODE is '" + mode + "', which is neither 'local' nor"
					+ " 'object'"
			);
		};

		if(resolved == StorageMode.LOCAL) {
			logger.atWarn()
				.addKeyValue("directory", storageDirectory)
				.log(
					"Storing everything on this node's disk. The indexes and the API"
						+ " keys exist only in this directory: losing it loses them, no"
						+ " other node can serve or take over from this one, and there"
						+ " is nothing to add a second node against. Set"
						+ " EXOFIND_STORAGE_MODE=object with the"
						+ " EXOFIND_STORAGE_REMOTE_ settings to keep them in an object"
						+ " storage instead"
				);

			if(remoteUrl.isPresent()) {
				/*
				 * Settings for a storage nothing is going to talk to are how a
				 * deployment that meant to share one ends up alone: naming the
				 * mode is what turns it on, and a deployment that got that
				 * wrong would otherwise find out by wondering where its data
				 * went.
				 */
				logger.atWarn()
					.addKeyValue("url", remoteUrl.get())
					.log(
						"EXOFIND_STORAGE_REMOTE_URL is set but nothing reads it while"
							+ " storing locally. Set EXOFIND_STORAGE_MODE=object to use"
							+ " that storage"
					);
			}

			if(diskMaxSize.isPresent()) {
				/*
				 * The sweep only removes copies the storage already holds, so
				 * with nowhere to have pushed them it removes nothing. Said
				 * here because a bound that is set and does nothing otherwise
				 * looks like a bound that is working.
				 */
				logger.atWarn()
					.log(
						"EXOFIND_INDEXES_DISK_MAX_SIZE is set but frees nothing while"
							+ " storing locally - these copies are the only ones there"
							+ " are, so none of them may be removed"
					);
			}
		}

		return resolved;
	}

	/**
	 * The bucket everything is kept in, which exists only in
	 * {@link StorageMode#OBJECT}.
	 *
	 * <p>The settings are read as optional and demanded here rather than
	 * declared as required, so that a node storing locally does not have to
	 * carry settings it never uses - and so a node that named the object mode
	 * and left one out is told which one.
	 */
	@Produces
	@Singleton
	public ObjectStorage objectStorage(
		StorageMode mode,
		NodeState nodeState,
		MeterRegistry meterRegistry,
		@ConfigProperty(name = "exofind.storage.remote.url") Optional<String> url,
		@ConfigProperty(name = "exofind.storage.remote.access-key") Optional<String> accessKey,
		@ConfigProperty(name = "exofind.storage.remote.secret-key") Optional<String> secretKey,
		@ConfigProperty(name = "exofind.storage.remote.region") Optional<String> region,
		@ConfigProperty(name = "exofind.storage.remote.bucket") Optional<String> bucket,
		@ConfigProperty(name = "exofind.storage.remote.prefix") Optional<String> prefix
	) throws IOException {
		if(mode != StorageMode.OBJECT) {
			throw new IllegalStateException(
				"There is no object storage while EXOFIND_STORAGE_MODE is '"
					+ mode.name().toLowerCase(Locale.ROOT) + "'"
			);
		}

		return new ObjectStorage(
			required(url, "EXOFIND_STORAGE_REMOTE_URL"),
			required(accessKey, "EXOFIND_STORAGE_REMOTE_ACCESS_KEY"),
			required(secretKey, "EXOFIND_STORAGE_REMOTE_SECRET_KEY"),
			region,
			required(bucket, "EXOFIND_STORAGE_REMOTE_BUCKET"),
			prefix,
			nodeState.isIndexerCandidate(),
			new StorageMetricsInterceptor(meterRegistry)
		);
	}

	/**
	 * How an index reaches the copy the deployment shares. A node storing
	 * locally has none to reach, and its files stay where they are written.
	 *
	 * <p>Takes the directory lock so that the claim on the directory is made
	 * before anything opens an index in it.
	 */
	@Startup
	@Produces
	@Singleton
	public StateSyncProvider stateSyncProvider(
		StorageMode mode,
		StorageDirectoryLock lock,
		Instance<ObjectStorage> storage
	) {
		return switch(mode) {
			case LOCAL -> new NoopSyncProvider();
			case OBJECT -> new ObjectStorageSyncProvider(storage.get());
		};
	}

	/**
	 * Which node writes which index. Candidates divide the indexes up through
	 * a leadership table in the object storage; a node storing locally is the
	 * only node there is, so a candidate simply holds them all.
	 */
	@Produces
	@Singleton
	public IndexerOwnership indexerOwnership(
		StorageMode mode,
		Instance<ObjectStorage> storage,
		IndexRegistry registry,
		NodeState nodeState,
		Instance<Indexes> indexes,
		@ConfigProperty(name = "exofind.node.id") Optional<String> nodeId,
		@ConfigProperty(name = "exofind.node.address") Optional<String> address,
		@ConfigProperty(name = "exofind.indexer.lease.duration", defaultValue = "30s")
		Duration leaseDuration
	) {
		return switch(mode) {
			case LOCAL -> new LocalIndexerOwnership();
			case OBJECT -> new ObjectStorageIndexerOwnership(
				storage.get(),
				nodeId,
				address,
				leaseDuration,
				/*
				 * Null until the registry has been read: an unread registry and
				 * a deployment holding nothing both look empty, and only the
				 * second is a reason to drop claims.
				 */
				() -> registry.hasBeenRead() ? registry.names() : null,
				nodeState::writeLoad,
				nodeState::recordWrite,
				name -> indexes.get().flushForHandover(name)
			);
		};
	}

	/**
	 * Where the registry of this deployment's indexes is kept. It shares the
	 * storage the indexes live in, so that an index created on one node is
	 * known to every other.
	 */
	@Produces
	@Singleton
	public RegistryStorage registryStorage(
		StorageMode mode,
		Instance<ObjectStorage> storage,
		@ConfigProperty(name = "exofind.storage.local.directory") Path storageDirectory
	) {
		return switch(mode) {
			case LOCAL -> new LocalRegistryStorage(storageDirectory.resolve(LOCAL_REGISTRY_FILE));
			case OBJECT -> new ObjectStorageRegistryStorage(storage.get());
		};
	}

	/**
	 * The audit that compares the registry with what the storage holds, and
	 * repairs it from there. Exists only in {@link StorageMode#OBJECT} - a node
	 * storing locally has no storage to compare its registry with, which
	 * callers check before resolving this.
	 */
	@Produces
	@Singleton
	public RegistryAudit registryAudit(
		StorageMode mode,
		Instance<ObjectStorage> storage,
		RegistryStorage registryStorage
	) {
		if(mode != StorageMode.OBJECT) {
			throw new IllegalStateException(
				"There is no registry audit while EXOFIND_STORAGE_MODE is '"
					+ mode.name().toLowerCase(Locale.ROOT) + "'"
			);
		}

		return new ObjectStorageRegistryAudit(storage.get(), registryStorage);
	}

	/**
	 * Where the reindex job records are kept. They share the storage the
	 * indexes live in, so a half-finished job survives its node and any node
	 * answers where one stands.
	 */
	@Produces
	@Singleton
	public ReindexJobStorage reindexJobStorage(
		StorageMode mode,
		Instance<ObjectStorage> storage,
		@ConfigProperty(name = "exofind.storage.local.directory") Path storageDirectory
	) {
		return switch(mode) {
			case LOCAL -> new LocalReindexJobStorage(
				storageDirectory.resolve("jobs").resolve("reindex")
			);
			case OBJECT -> new ObjectStorageReindexJobStorage(storage.get());
		};
	}

	/**
	 * Where this node keeps its keys.
	 *
	 * <p>A node that named the object storage but cannot use it for keys falls
	 * back to its root key alone. That is narrower than a working store rather
	 * than wider, so a node that cannot reach one refuses more than it would
	 * otherwise, never less - and it is not the same as storing locally, where
	 * there is a store and it works.
	 */
	@Produces
	@Singleton
	public KeyStorage keyStorage(
		StorageMode mode,
		Instance<ObjectStorage> storage,
		@ConfigProperty(name = "exofind.storage.local.directory") Path storageDirectory
	) {
		if(mode == StorageMode.LOCAL) {
			return new LocalKeyStorage(storageDirectory.resolve(LOCAL_KEYS_FILE));
		}

		/*
		 * Resolved outside the fallback: a node that cannot open the storage at
		 * all is misconfigured rather than cut off from its keys, and saying so
		 * as a lost key store would report the wrong problem for a node that is
		 * about to refuse to start anyway.
		 */
		var objectStorage = storage.get();

		try {
			return new ObjectStorageKeyStorage(objectStorage);
		} catch(RuntimeException e) {
			logger.atWarn()
				.setCause(e)
				.log(
					"Object storage is configured but could not be used for keys, so this"
						+ " node can only be reached with its root key; " + e.getMessage()
				);

			return new NoKeyStorage();
		}
	}

	/**
	 * Where this node keeps the search settings of the indexes.
	 *
	 * <p>A node that named the object storage but cannot use it for settings
	 * falls back to searching with the definitions alone. That is the ranking
	 * every index shipped with rather than half of somebody's tuning, so a node
	 * that cannot reach the settings answers plainer than the others, never
	 * differently wrong - and it is not the same as storing locally, where
	 * there is a store and it works.
	 */
	@Produces
	@Singleton
	public SearchSettingsStorage searchSettingsStorage(
		StorageMode mode,
		Instance<ObjectStorage> storage,
		@ConfigProperty(name = "exofind.storage.local.directory") Path storageDirectory
	) {
		if(mode == StorageMode.LOCAL) {
			return new LocalSearchSettingsStorage(
				storageDirectory.resolve(LOCAL_SETTINGS_DIRECTORY)
			);
		}

		/*
		 * Resolved outside the fallback: a node that cannot open the storage at
		 * all is misconfigured rather than cut off from its settings, and saying
		 * so as lost settings would report the wrong problem for a node that is
		 * about to refuse to start anyway.
		 */
		var objectStorage = storage.get();

		try {
			return new ObjectStorageSearchSettingsStorage(objectStorage);
		} catch(RuntimeException e) {
			logger.atWarn()
				.setCause(e)
				.log(
					"Object storage is configured but could not be used for search"
						+ " settings, so this node searches with the definitions alone; "
						+ e.getMessage()
				);

			return new NoSearchSettingsStorage();
		}
	}

	/**
	 * The value of a setting the named mode cannot do without.
	 */
	private static String required(Optional<String> value, String variable) {
		return value
			.filter(v -> !v.isBlank())
			.orElseThrow(() -> new IllegalStateException(
				variable + " has to be set when EXOFIND_STORAGE_MODE is 'object'"
			));
	}
}
