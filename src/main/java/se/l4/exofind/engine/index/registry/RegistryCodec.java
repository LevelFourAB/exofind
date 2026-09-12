package se.l4.exofind.engine.index.registry;

import java.time.Instant;
import java.util.Optional;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.ListIterable;

import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.logging.Log;

/**
 * Reading and writing the stored form of the index registry.
 *
 * <p>An entry that names features this build does not have is read but marked
 * as such rather than dropped, so that listing the indexes still shows it and
 * says what it needs. Using it is refused where it would be resolved. An entry
 * that could not be read at all - one naming no index, or naming one that
 * could not have been created here - is passed over, as there is nothing about
 * it that could be reported usefully.
 *
 * <p>Writing takes the contents the change was built on and rewrites them,
 * rather than building the object from the records alone. Everything a read
 * left behind is therefore carried on: fields a newer version added to the
 * store, to an entry or to a generation, and the entries and generations that
 * were passed over as unreadable. A node without those fields rewrites the
 * registry for its own reasons - creating an index somewhere else in it, or
 * folding in a version hint - and must leave the rest of it as it found it.
 */
public final class RegistryCodec {
	private static final Log logger = Log.of(RegistryCodec.class);

	private RegistryCodec() {
	}

	/**
	 * Read every index the registry holds.
	 *
	 * @param stored
	 * @return
	 *   the indexes, ordered by name
	 */
	public static ListIterable<RegisteredIndex> fromStored(IndexRegistryStore stored) {
		var indexes = Lists.mutable.<RegisteredIndex>empty();

		for(var entry : stored.getIndexesList()) {
			fromStored(entry).ifPresent(indexes::add);
		}

		return indexes.sortThisBy(RegisteredIndex::name).toImmutable();
	}

	/**
	 * Read one index.
	 *
	 * @param stored
	 * @return
	 *   empty when the entry names nothing that can be used here, which is
	 *   logged with the reason
	 */
	public static Optional<RegisteredIndex> fromStored(IndexEntry stored) {
		var name = stored.getName();
		if(name.isEmpty()) {
			logger.atWarn().log("Ignoring a registered index that has no name");
			return Optional.empty();
		}

		/*
		 * The name becomes a directory here and part of a key in the remote, so
		 * it has to hold up to the same rules as one this node was asked to
		 * create.
		 */
		if(!IndexName.VALID_INDEX_PATTERN.matcher(name).matches()) {
			logger.atWarn()
				.addKeyValue("index", name)
				.log("Ignoring a registered index, its name is not one that can be used");

			return Optional.empty();
		}

		var generations = Lists.mutable.<RegisteredIndex.Generation>empty();
		for(var generation : stored.getGenerationsList()) {
			var generationName = generation.getName();

			if(!IndexName.VALID_GENERATION_PATTERN.matcher(generationName).matches()) {
				logger.atWarn()
					.addKeyValue("index", name)
					.addKeyValue("generation", generationName)
					.log("Ignoring a generation, its name is not one that can be used");

				continue;
			}

			generations.add(new RegisteredIndex.Generation(
				generationName,
				generation.hasCreatedAt() ? Instant.ofEpochMilli(generation.getCreatedAt()) : null,
				generation.hasManifestVersion() ? generation.getManifestVersion() : null
			));
		}

		var unsupported = RegistryFeatures.unsupportedIn(stored);
		if(unsupported.notEmpty()) {
			logger.atError()
				.addKeyValue("index", name)
				.log(
					"Refusing to resolve index, it needs features this node does not have: "
						+ unsupported.toSortedList().makeString(", ")
						+ ". Upgrade this node to serve it"
				);
		}

		return Optional.of(new RegisteredIndex(
			name,
			generations.sortThisBy(RegisteredIndex.Generation::name).toImmutable(),
			stored.hasLive() ? stored.getLive() : null,
			stored.hasCreatedAt() ? Instant.ofEpochMilli(stored.getCreatedAt()) : null,
			Sets.immutable.withAll(stored.getRequiredFeaturesList()),
			stored.hasSettingsVersion() ? stored.getSettingsVersion() : null
		));
	}

	/**
	 * Write the indexes as the registry stores them.
	 *
	 * <p>The unreadable entries come first and the rest follow ordered by name,
	 * so the same contents and the same indexes always produce the same bytes.
	 *
	 * @param previous
	 *   the contents the change was built on, or {@code null} when the registry
	 *   is being written for the first time
	 * @param indexes
	 *   every index the registry is to name, which is what a caller can address
	 * @return
	 */
	public static IndexRegistryStore toStored(
		IndexRegistryStore previous,
		ListIterable<RegisteredIndex> indexes
	) {
		var store = previous == null
			? IndexRegistryStore.newBuilder()
			: previous.toBuilder();

		/*
		 * An entry that was read is rewritten from the record the caller
		 * changed, and one that was not is put back byte for byte. A caller
		 * addresses an index by a name that parses, so an entry left out of the
		 * records is one this build could not read rather than one a caller
		 * asked to remove.
		 */
		var readable = Maps.mutable.<String, IndexEntry>empty();
		var unreadable = Lists.mutable.<IndexEntry>empty();
		for(var entry : store.getIndexesList()) {
			if(IndexName.VALID_INDEX_PATTERN.matcher(entry.getName()).matches()) {
				readable.put(entry.getName(), entry);
			} else {
				unreadable.add(entry);
			}
		}

		store.clearIndexes();

		for(var entry : unreadable) {
			store.addIndexes(entry);
		}

		for(var index : indexes.toSortedListBy(RegisteredIndex::name)) {
			store.addIndexes(toStored(readable.get(index.name()), index));
		}

		return store.build();
	}

	/**
	 * Write one index over the entry it was read from.
	 *
	 * @param previous
	 *   the entry the index was read from, or {@code null} for one the registry
	 *   does not hold yet
	 * @param index
	 * @return
	 */
	private static IndexEntry toStored(IndexEntry previous, RegisteredIndex index) {
		var entry = previous == null
			? IndexEntry.newBuilder()
			: previous.toBuilder();

		entry.setName(index.name());

		// Kept and put back the way the entries of the store are
		var readable = Maps.mutable.<String, GenerationEntry>empty();
		var unreadable = Lists.mutable.<GenerationEntry>empty();
		for(var generation : entry.getGenerationsList()) {
			if(IndexName.VALID_GENERATION_PATTERN.matcher(generation.getName()).matches()) {
				readable.put(generation.getName(), generation);
			} else {
				unreadable.add(generation);
			}
		}

		entry.clearGenerations();

		for(var generation : unreadable) {
			entry.addGenerations(generation);
		}

		for(var generation : index.generations().toSortedListBy(RegisteredIndex.Generation::name)) {
			var storedBefore = readable.get(generation.name());
			var stored = storedBefore == null
				? GenerationEntry.newBuilder()
				: storedBefore.toBuilder();

			stored.setName(generation.name());

			if(generation.createdAt() != null) {
				stored.setCreatedAt(generation.createdAt().toEpochMilli());
			} else {
				stored.clearCreatedAt();
			}

			if(generation.manifestVersion() != null) {
				stored.setManifestVersion(generation.manifestVersion());
			} else {
				stored.clearManifestVersion();
			}

			entry.addGenerations(stored);
		}

		if(index.live() != null) {
			entry.setLive(index.live());
		} else {
			entry.clearLive();
		}

		if(index.createdAt() != null) {
			entry.setCreatedAt(index.createdAt().toEpochMilli());
		} else {
			entry.clearCreatedAt();
		}

		/*
		 * Written back as they were found, so that a node without a feature
		 * carries the name on rather than dropping it from an entry it happens
		 * to rewrite - which would leave the entry looking like one every node
		 * can resolve.
		 */
		entry.clearRequiredFeatures();
		for(var feature : index.requiredFeatures().toSortedList()) {
			entry.addRequiredFeatures(feature);
		}

		if(index.settingsVersion() != null) {
			entry.setSettingsVersion(index.settingsVersion());
		} else {
			entry.clearSettingsVersion();
		}

		return entry.build();
	}
}
