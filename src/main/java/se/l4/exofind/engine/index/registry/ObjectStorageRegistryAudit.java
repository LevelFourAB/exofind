package se.l4.exofind.engine.index.registry;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;

import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.state.IndexRemovals;
import se.l4.exofind.engine.index.state.LocalCopy;
import se.l4.exofind.engine.logging.Log;
import se.l4.exofind.engine.storage.ObjectStorage;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * RegistryAudit over the bucket a deployment in object mode keeps everything
 * in.
 *
 * <p>The indexes all live under one path, an index is one prefix under it and
 * a generation one prefix under that, so what the deployment holds is read
 * with delimited listings. A generation counts as held when its manifest
 * exists: the manifest is written last when a generation is pushed, so a
 * prefix without one has never finished a push and holds nothing a node could
 * serve from. A removal mark beside an index or a generation, see
 * {@link IndexRemovals}, is read along with it.
 *
 * <p>The repair replaces the registry conditionally on the version the audit
 * read - including the version of contents that could not be parsed - so it
 * can never overwrite a change made between reading and writing.
 */
public class ObjectStorageRegistryAudit implements RegistryAudit {
	private static final Log logger = Log.of(ObjectStorageRegistryAudit.class);

	/**
	 * How many times the repair is rebuilt on top of a concurrent change
	 * before giving up, the same allowance {@link IndexRegistry} gives its
	 * writes.
	 */
	private static final int WRITE_ATTEMPTS = 3;

	private final S3Client client;
	private final String bucket;
	private final ObjectStorage storage;
	private final RegistryStorage registry;
	private final IndexRemovals removals;

	public ObjectStorageRegistryAudit(
		ObjectStorage storage,
		RegistryStorage registry,
		IndexRemovals removals
	) {
		this.client = storage.client();
		this.bucket = storage.bucket();
		this.storage = storage;
		this.registry = registry;
		this.removals = removals;
	}

	@Override
	public RegistryAuditReport audit() {
		var read = readRegistry();
		var held = listHeld();

		return buildReport(read, held);
	}

	@Override
	public RegistryRepairResult repair(boolean promoteNewest, ListIterable<IndexName> restore) {
		/*
		 * The marks go before the storage is listed, so that what was
		 * restored is listed the way unmarked storage is and registered
		 * along with it.
		 */
		var restored = unmark(restore);

		/*
		 * Listed once rather than per attempt: an attempt is repeated because
		 * the registry moved, and the registry moving says nothing about the
		 * files.
		 */
		var held = listHeld();

		for(int attempt = 0; attempt < WRITE_ATTEMPTS; attempt++) {
			var read = readRegistry();
			var merged = merge(read, held, promoteNewest, restored);

			if(
				merged.result().createdIndexes().isEmpty()
					&& merged.result().addedGenerations().isEmpty()
					&& merged.result().promoted().isEmpty()
			) {
				// Nothing to write, whether or not a mark was taken away
				return merged.result();
			}

			String version;
			try {
				version = registry.write(merged.store(), read.version());
			} catch(IOException e) {
				throw RegistryException.ioError(e);
			}

			if(version != null) {
				logger.atInfo()
					.addKeyValue("createdIndexes", merged.result().createdIndexes().makeString(", "))
					.addKeyValue("addedGenerations", merged.result().addedGenerations().makeString(", "))
					.addKeyValue("promoted", merged.result().promoted().makeString(", "))
					.addKeyValue("restored", merged.result().restored().makeString(", "))
					.log("Repaired the registry from what the storage holds");

				return merged.result();
			}
		}

		throw RegistryException.conflict();
	}

	/**
	 * Take the removal marks off what the caller asked to restore.
	 *
	 * @return
	 *   the names that had a mark to take off, as asked for
	 */
	private ListIterable<String> unmark(ListIterable<IndexName> restore) {
		var restored = Lists.mutable.<String>empty();

		for(var target : restore) {
			try {
				if(removals.unmark(target)) {
					restored.add(target.toString());

					logger.atInfo()
						.addKeyValue("index", target.toString())
						.log("Took the removal mark off a deleted index, so a repair can register it");
				}
			} catch(IOException e) {
				throw RegistryException.ioError(e);
			}
		}

		return restored.toImmutable();
	}

	/**
	 * The registry as one read found it, holding whichever of the pieces the
	 * state comes with.
	 */
	private record RegistryRead(
		RegistryAuditReport.Registry state,
		IndexRegistryStore store,
		String version
	) {
	}

	private RegistryRead readRegistry() {
		try {
			return switch(registry.read(null)) {
				case RegistryStorage.Read.Loaded loaded -> new RegistryRead(
					RegistryAuditReport.Registry.PRESENT,
					loaded.indexes(),
					loaded.version()
				);
				case RegistryStorage.Read.Absent absent -> new RegistryRead(
					RegistryAuditReport.Registry.ABSENT,
					null,
					null
				);
				case RegistryStorage.Read.Corrupt corrupt -> new RegistryRead(
					RegistryAuditReport.Registry.CORRUPT,
					null,
					corrupt.version()
				);
				case RegistryStorage.Read.Unchanged unchanged ->
					// Reading without a known version is never answered this way
					throw new IllegalStateException(
						"The registry answered as unchanged to a read without a version"
					);
			};
		} catch(IOException e) {
			throw RegistryException.ioError(e);
		}
	}

	/**
	 * One generation as the storage holds it.
	 *
	 * @param stored
	 * @param removedAt
	 *   when a delete marked the generation on its own, or {@code null}
	 */
	private record HeldGeneration(RegistryAuditReport.Stored stored, Instant removedAt) {
	}

	/**
	 * One index as the storage holds it.
	 *
	 * @param removedAt
	 *   when a delete marked the whole index, or {@code null}
	 * @param generations
	 *   every generation prefix under it, by name
	 */
	private record HeldIndex(Instant removedAt, TreeMap<String, HeldGeneration> generations) {
	}

	/**
	 * What the storage holds: every generation under every index prefix, and
	 * the prefixes whose names nothing may carry.
	 */
	private record HeldIndexes(
		TreeMap<String, HeldIndex> indexes,
		MutableList<String> unusable
	) {
	}

	private HeldIndexes listHeld() {
		var held = new HeldIndexes(new TreeMap<>(), Lists.mutable.empty());
		var indexesPrefix = storage.indexesPath() + "/";

		try {
			for(var index : listPrefixes(indexesPrefix)) {
				if(!IndexName.VALID_INDEX_PATTERN.matcher(index).matches()) {
					held.unusable().add(index);
					continue;
				}

				var generations = new TreeMap<String, HeldGeneration>();
				var unusableBefore = held.unusable().size();

				for(var generation : listPrefixes(indexesPrefix + index + "/")) {
					if(!IndexName.VALID_GENERATION_PATTERN.matcher(generation).matches()) {
						held.unusable().add(index + "/" + generation);
						continue;
					}

					var name = IndexName.of(index, generation);
					generations.put(
						generation,
						new HeldGeneration(
							hasManifest(name)
								? RegistryAuditReport.Stored.SYNCED
								: RegistryAuditReport.Stored.INCOMPLETE,
							markedAt(name)
						)
					);
				}

				/*
				 * A prefix with no generation under it holds nothing a node
				 * could serve or a repair could restore. The settings object
				 * and the mark of a deleted index can outlast its generations
				 * for a moment, and those alone keeping the prefix listable is
				 * not the index still being held - so it is left out rather
				 * than reported forever.
				 */
				if(!generations.isEmpty() || held.unusable().size() > unusableBefore) {
					held.indexes().put(
						index,
						new HeldIndex(markedAt(IndexName.of(index)), generations)
					);
				}
			}
		} catch(SdkException e) {
			throw RegistryException.ioError(e);
		}

		held.unusable().sortThis();
		return held;
	}

	/**
	 * The names directly under a prefix, read off a delimited listing.
	 */
	private MutableList<String> listPrefixes(String prefix) {
		var names = Lists.mutable.<String>empty();

		var pages = client.listObjectsV2Paginator(
			ListObjectsV2Request.builder()
				.bucket(bucket)
				.prefix(prefix)
				.delimiter("/")
				.build()
		);

		for(var page : pages) {
			for(var common : page.commonPrefixes()) {
				var name = common.prefix();
				names.add(name.substring(prefix.length(), name.length() - 1));
			}
		}

		return names;
	}

	private boolean hasManifest(IndexName generation) {
		try {
			client.headObject(
				HeadObjectRequest.builder()
					.bucket(bucket)
					.key(storage.indexPath(generation) + "/" + LocalCopy.MANIFEST_FILE)
					.build()
			);

			return true;
		} catch(S3Exception e) {
			if(e.statusCode() == 404) {
				return false;
			}

			throw RegistryException.ioError(e);
		}
	}

	private Instant markedAt(IndexName target) {
		try {
			return removals.markedAt(target).orElse(null);
		} catch(IOException e) {
			throw RegistryException.ioError(e);
		}
	}

	private static RegistryAuditReport buildReport(RegistryRead read, HeldIndexes held) {
		/*
		 * Joined at the stored level rather than through RegistryCodec, so an
		 * entry is compared under exactly the name it is stored as.
		 */
		var registered = new TreeMap<String, IndexEntry>();
		if(read.store() != null) {
			for(var entry : read.store().getIndexesList()) {
				if(IndexName.VALID_INDEX_PATTERN.matcher(entry.getName()).matches()) {
					registered.put(entry.getName(), entry);
				}
			}
		}

		var names = new TreeSet<String>();
		names.addAll(registered.keySet());
		names.addAll(held.indexes().keySet());

		var indexes = Lists.mutable.<RegistryAuditReport.AuditedIndex>empty();
		for(var name : names) {
			var entry = registered.get(name);
			var heldIndex = held.indexes().get(name);
			var heldGenerations = heldIndex != null
				? heldIndex.generations()
				: new TreeMap<String, HeldGeneration>();

			var generationNames = new TreeSet<>(heldGenerations.keySet());

			var registeredGenerations = new TreeSet<String>();
			if(entry != null) {
				for(var generation : entry.getGenerationsList()) {
					registeredGenerations.add(generation.getName());
					generationNames.add(generation.getName());
				}
			}

			var generations = Lists.mutable.<RegistryAuditReport.AuditedGeneration>empty();
			for(var generation : generationNames) {
				var heldGeneration = heldGenerations.get(generation);
				generations.add(new RegistryAuditReport.AuditedGeneration(
					generation,
					registeredGenerations.contains(generation),
					heldGeneration != null
						? heldGeneration.stored()
						: RegistryAuditReport.Stored.MISSING,
					heldGeneration != null ? heldGeneration.removedAt() : null
				));
			}

			var removedAt = heldIndex != null ? heldIndex.removedAt() : null;

			indexes.add(new RegistryAuditReport.AuditedIndex(
				name,
				entry != null,
				entry != null && entry.hasLive() ? entry.getLive() : null,
				entry == null && removedAt == null ? newestSynced(heldGenerations) : null,
				removedAt,
				generations.toImmutable()
			));
		}

		return new RegistryAuditReport(
			read.state(),
			indexes.toImmutable(),
			held.unusable().toImmutable()
		);
	}

	/**
	 * The merged registry and what merging added to it.
	 */
	private record Merge(IndexRegistryStore store, RegistryRepairResult result) {
	}

	private static Merge merge(
		RegistryRead read,
		HeldIndexes held,
		boolean promoteNewest,
		ListIterable<String> restored
	) {
		/*
		 * Built from the stored bytes rather than through RegistryCodec, so
		 * entries this build cannot read - unknown fields, features it does
		 * not have - are carried through a repair untouched.
		 */
		var store = read.store() != null
			? read.store().toBuilder()
			: IndexRegistryStore.newBuilder();

		var entries = new TreeMap<String, Integer>();
		for(int i = 0; i < store.getIndexesCount(); i++) {
			entries.put(store.getIndexes(i).getName(), i);
		}

		var createdIndexes = Lists.mutable.<String>empty();
		var addedGenerations = Lists.mutable.<String>empty();
		var promoted = Lists.mutable.<String>empty();

		for(var index : held.indexes().entrySet()) {
			if(index.getValue().removedAt() != null) {
				// Deleted and on its way out, which a repair does not undo on its own
				continue;
			}

			var synced = new TreeSet<String>();
			for(var generation : index.getValue().generations().entrySet()) {
				if(
					generation.getValue().stored() == RegistryAuditReport.Stored.SYNCED
						&& generation.getValue().removedAt() == null
				) {
					synced.add(generation.getKey());
				}
			}

			if(synced.isEmpty()) {
				// Nothing under the index has finished a push, nothing to register
				continue;
			}

			var position = entries.get(index.getKey());
			if(position != null) {
				var entry = store.getIndexesBuilder(position);

				var existing = new TreeSet<String>();
				for(var generation : entry.getGenerationsList()) {
					existing.add(generation.getName());
				}

				for(var generation : synced) {
					if(!existing.contains(generation)) {
						entry.addGenerations(
							GenerationEntry.newBuilder().setName(generation)
						);

						addedGenerations.add(index.getKey() + "@" + generation);
					}
				}
			} else {
				var entry = IndexEntry.newBuilder()
					.setName(index.getKey());

				for(var generation : synced) {
					entry.addGenerations(
						GenerationEntry.newBuilder().setName(generation)
					);

					addedGenerations.add(index.getKey() + "@" + generation);
				}

				if(promoteNewest) {
					var live = newestSynced(index.getValue().generations());
					if(live != null) {
						entry.setLive(live);
						promoted.add(index.getKey() + "@" + live);
					}
				}

				store.addIndexes(entry);
				createdIndexes.add(index.getKey());
			}
		}

		return new Merge(
			store.build(),
			new RegistryRepairResult(
				createdIndexes.toImmutable(),
				addedGenerations.toImmutable(),
				promoted.toImmutable(),
				restored
			)
		);
	}

	/**
	 * The synced generation with the highest number, or {@code null} when no
	 * synced generation carries one. Generations count up from one when the
	 * engine names them, so the highest number is the one created last; a
	 * name given by hand says nothing about age and is never picked. A
	 * generation a delete marked on its own is not up for promotion either.
	 */
	private static String newestSynced(Map<String, HeldGeneration> generations) {
		String newest = null;
		int newestNumber = 0;

		for(var generation : generations.entrySet()) {
			if(
				generation.getValue().stored() != RegistryAuditReport.Stored.SYNCED
					|| generation.getValue().removedAt() != null
			) {
				continue;
			}

			try {
				var number = Integer.parseInt(generation.getKey());
				if(newest == null || number > newestNumber) {
					newest = generation.getKey();
					newestNumber = number;
				}
			} catch(NumberFormatException e) {
				// Not a numbered generation
			}
		}

		return newest;
	}
}
