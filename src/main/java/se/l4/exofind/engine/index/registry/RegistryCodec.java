package se.l4.exofind.engine.index.registry;

import java.time.Instant;
import java.util.Optional;

import org.eclipse.collections.api.factory.Lists;
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
	 * @param indexes
	 * @return
	 */
	public static IndexRegistryStore toStored(ListIterable<RegisteredIndex> indexes) {
		var store = IndexRegistryStore.newBuilder();

		for(var index : indexes.toSortedListBy(RegisteredIndex::name)) {
			store.addIndexes(toStored(index));
		}

		return store.build();
	}

	/**
	 * Write one index.
	 *
	 * @param index
	 * @return
	 */
	public static IndexEntry toStored(RegisteredIndex index) {
		var entry = IndexEntry.newBuilder()
			.setName(index.name());

		for(var generation : index.generations().toSortedListBy(RegisteredIndex.Generation::name)) {
			var stored = GenerationEntry.newBuilder()
				.setName(generation.name());

			if(generation.createdAt() != null) {
				stored.setCreatedAt(generation.createdAt().toEpochMilli());
			}

			if(generation.manifestVersion() != null) {
				stored.setManifestVersion(generation.manifestVersion());
			}

			entry.addGenerations(stored);
		}

		if(index.live() != null) {
			entry.setLive(index.live());
		}

		if(index.createdAt() != null) {
			entry.setCreatedAt(index.createdAt().toEpochMilli());
		}

		/*
		 * Written back as they were found, so that a node without a feature
		 * carries the name on rather than dropping it from an entry it happens
		 * to rewrite - which would leave the entry looking like one every node
		 * can resolve.
		 */
		for(var feature : index.requiredFeatures().toSortedList()) {
			entry.addRequiredFeatures(feature);
		}

		if(index.settingsVersion() != null) {
			entry.setSettingsVersion(index.settingsVersion());
		}

		return entry.build();
	}
}
