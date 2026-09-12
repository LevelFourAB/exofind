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
 *
 * <p>A delete takes its entry out of the registry before it marks the storage,
 * so a mark can arrive while a repair runs. The repair reads the marks of
 * everything it is about to register once more, right before it writes, and a
 * mark the caller asked to take off comes off only once the registry names
 * what it stood over.
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
		 * Listed once rather than per attempt: an attempt is repeated because
		 * the registry moved, and the registry moving says nothing about the
		 * files.
		 */
		var held = listHeld();

		/*
		 * What the caller asked to restore is merged the way unmarked storage
		 * is. The marks themselves stay on the storage until the registry names
		 * what they stand over.
		 */
		var restoring = dropMarks(held, restore);

		for(int attempt = 0; attempt < WRITE_ATTEMPTS; attempt++) {
			var read = readRegistry();

			/*
			 * A delete takes its entry out of the registry before it writes its
			 * mark, so a mark can land on a prefix this listing read as
			 * unmarked. Read the marks of what the write would register again
			 * until none has landed, so an index on its way out is not
			 * registered again moments before its mark arrives.
			 */
			var merged = merge(read, held, promoteNewest);
			while(remark(held, merged, restoring)) {
				merged = merge(read, held, promoteNewest);
			}

			if(
				merged.result().createdIndexes().isEmpty()
					&& merged.result().addedGenerations().isEmpty()
					&& merged.result().promoted().isEmpty()
			) {
				// Nothing to write; a mark still comes off what is registered already
				return withRestored(merged.result(), unmark(restore, read.store()));
			}

			String version;
			try {
				version = registry.write(merged.store(), read.version());
			} catch(IOException e) {
				throw RegistryException.ioError(e);
			}

			if(version != null) {
				var restored = unmark(restore, merged.store());

				logger.atInfo()
					.addKeyValue("createdIndexes", merged.result().createdIndexes().makeString(", "))
					.addKeyValue("addedGenerations", merged.result().addedGenerations().makeString(", "))
					.addKeyValue("promoted", merged.result().promoted().makeString(", "))
					.addKeyValue("restored", restored.makeString(", "))
					.log("Repaired the registry from what the storage holds");

				return withRestored(merged.result(), restored);
			}
		}

		throw RegistryException.conflict();
	}

	/**
	 * Leave the removal mark of everything the caller asked to restore out of a
	 * listing, so the merge treats it as storage nothing deleted.
	 *
	 * @return
	 *   the names as the merge and the mark re-reads spell them
	 */
	private static TreeSet<String> dropMarks(
		HeldIndexes held,
		ListIterable<IndexName> restore
	) {
		var restoring = new TreeSet<String>();

		for(var target : restore) {
			restoring.add(target.toString());

			var index = held.indexes().get(target.index());
			if(index == null) {
				continue;
			}

			if(!target.isPinned()) {
				held.indexes().put(target.index(), new HeldIndex(null, index.generations()));
				continue;
			}

			var generation = index.generations().get(target.generation());
			if(generation != null) {
				index.generations().put(
					target.generation(),
					new HeldGeneration(generation.stored(), null)
				);
			}
		}

		return restoring;
	}

	/**
	 * Read the removal marks of everything a merge would register, and write
	 * the ones that have arrived since the listing into it. A name the caller
	 * asked to restore is left as it is.
	 *
	 * @return
	 *   whether the listing changed, which asks for the merge to be built
	 *   again from it
	 */
	private boolean remark(HeldIndexes held, Merge merged, TreeSet<String> restoring) {
		var changed = false;

		for(var name : merged.result().createdIndexes()) {
			var index = held.indexes().get(name);
			if(restoring.contains(name) || index == null || index.removedAt() != null) {
				continue;
			}

			var markedAt = markedAt(IndexName.of(name));
			if(markedAt != null) {
				held.indexes().put(name, new HeldIndex(markedAt, index.generations()));
				changed = true;
			}
		}

		for(var name : merged.result().addedGenerations()) {
			var target = IndexName.parse(name);
			var index = held.indexes().get(target.index());
			if(restoring.contains(name) || index == null) {
				continue;
			}

			var generation = index.generations().get(target.generation());
			if(generation == null || generation.removedAt() != null) {
				continue;
			}

			var markedAt = markedAt(target);
			if(markedAt != null) {
				index.generations().put(
					target.generation(),
					new HeldGeneration(generation.stored(), markedAt)
				);
				changed = true;
			}
		}

		return changed;
	}

	/**
	 * Take the removal marks off what the caller asked to restore and a
	 * registry names. A name the registry does not name keeps its mark: the
	 * mark is the only thing saying its objects were deleted, and nothing
	 * removes them once it is gone.
	 *
	 * <p>A mark that could not be taken off is logged and left out of the
	 * answer. The registry names what it stands over, which makes it void, and
	 * a sweep passes it over.
	 *
	 * @return
	 *   the names a mark came off, as asked for
	 */
	private ListIterable<String> unmark(
		ListIterable<IndexName> restore,
		IndexRegistryStore store
	) {
		var restored = Lists.mutable.<String>empty();

		for(var target : restore) {
			if(!isRegistered(store, target)) {
				continue;
			}

			try {
				if(removals.unmark(target)) {
					restored.add(target.toString());

					logger.atInfo()
						.addKeyValue("index", target.toString())
						.log("Took the removal mark off a deleted index the registry names again");
				}
			} catch(IOException e) {
				logger.atWarn()
					.addKeyValue("index", target.toString())
					.setCause(e)
					.log(
						"Could not take the removal mark off a restored index; the registry"
							+ " names it, so the mark stands for nothing; " + e.getMessage()
					);
			}
		}

		return restored.toImmutable();
	}

	/**
	 * Whether a registry names an index, or the generation of it a name pins.
	 * A registry that was never read names nothing.
	 */
	private static boolean isRegistered(IndexRegistryStore store, IndexName target) {
		if(store == null) {
			return false;
		}

		for(var entry : store.getIndexesList()) {
			if(!entry.getName().equals(target.index())) {
				continue;
			}

			if(!target.isPinned()) {
				return true;
			}

			for(var generation : entry.getGenerationsList()) {
				if(generation.getName().equals(target.generation())) {
					return true;
				}
			}

			return false;
		}

		return false;
	}

	/**
	 * A merge result with the names a mark came off in it.
	 */
	private static RegistryRepairResult withRestored(
		RegistryRepairResult result,
		ListIterable<String> restored
	) {
		return new RegistryRepairResult(
			result.createdIndexes(),
			result.addedGenerations(),
			result.promoted(),
			restored
		);
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
	 * The merged registry and what merging added to it. The result names
	 * nothing as restored: a mark comes off after the write, so what was
	 * restored is only known then.
	 */
	private record Merge(IndexRegistryStore store, RegistryRepairResult result) {
	}

	private static Merge merge(
		RegistryRead read,
		HeldIndexes held,
		boolean promoteNewest
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
				Lists.immutable.empty()
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
