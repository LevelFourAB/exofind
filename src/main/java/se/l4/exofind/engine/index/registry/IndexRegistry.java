package se.l4.exofind.engine.index.registry;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.UnaryOperator;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.map.MapIterable;
import org.eclipse.collections.api.set.ImmutableSet;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.IndexNoLiveGenerationException;
import se.l4.exofind.engine.index.IndexNotFoundException;
import se.l4.exofind.engine.index.IndexUnsupportedException;
import se.l4.exofind.engine.logging.Log;
import jakarta.inject.Singleton;

/**
 * Which indexes the deployment holds, which generations each of them has, and
 * which generation a name answers for.
 *
 * <p>The registry is a single object in the storage, so whether an index exists
 * is read at one version rather than pieced together from a listing that
 * arrives in pages. That is what makes creating an index a race exactly one
 * node can win, and what lets a node learn about every index in a deployment
 * for one conditional request however many there are - a listing would grow
 * with the indexes, and grow again with every generation left standing during a
 * rollout.
 *
 * <p>Changing the registry does not need the indexer role. The object is
 * replaced conditionally on the version it was read at, so a node that raced
 * another is refused and rebuilds its change on top of what the other wrote.
 *
 * <p>Each node keeps its own copy and re-reads it as part of the index refresh,
 * which is also how long promoting a generation takes to reach a node still
 * answering from the old one. A name this node has not seen is looked up at
 * once, at most once per interval, so an index created a moment ago on another
 * node can be used without waiting for the next read.
 *
 * <p>Safe for concurrent use.
 */
@Singleton
public class IndexRegistry {
	private static final Log logger = Log.of(IndexRegistry.class);

	/**
	 * How many times a change is rebuilt on top of a concurrent one before
	 * giving up. Losing three races in a row means something is changing the
	 * registry continuously, which is not a state waiting longer improves.
	 */
	private static final int WRITE_ATTEMPTS = 3;

	private static final ErrorType ALREADY_EXISTS = ErrorType.withCode("index:already_exists")
		.withArguments("name")
		.withMessage("The index `{{name}}` already exists");

	private static final ErrorType GENERATION_ALREADY_EXISTS =
		ErrorType.withCode("index:generation:already_exists")
			.withArguments("name")
			.withMessage("The generation `{{name}}` already exists");

	private static final ErrorType GENERATION_IS_LIVE =
		ErrorType.withCode("index:generation:is_live")
			.withArguments("name")
			.withMessage(
				"The generation `{{name}}` is the one its index answers for and cannot be"
					+ " removed. Promote another generation first"
			);

	private final RegistryStorage storage;
	private final Duration refreshInterval;

	/**
	 * Lock held while deciding whether a lookup that missed may go and read the
	 * registry, so a run of unknown names causes one read rather than one each.
	 */
	private final Object forcedReadLock = new Object();

	private volatile Snapshot snapshot = Snapshot.empty();
	private long lastForcedReadNanos;
	private boolean forcedReadEver;

	/**
	 * Whether a read has ever succeeded, which is what makes an empty copy an
	 * answer rather than a gap.
	 */
	private volatile boolean readEver;

	/**
	 * The indexes as of one read of the registry, together with the version they
	 * were read at.
	 */
	private record Snapshot(
		ListIterable<RegisteredIndex> indexes,
		MapIterable<String, RegisteredIndex> byName,
		String version
	) {
		static Snapshot empty() {
			return new Snapshot(Lists.immutable.empty(), Maps.immutable.empty(), null);
		}

		static Snapshot of(ListIterable<RegisteredIndex> indexes, String version) {
			var byName = Maps.mutable.<String, RegisteredIndex>empty();
			for(var index : indexes) {
				byName.put(index.name(), index);
			}

			return new Snapshot(indexes, byName.toImmutable(), version);
		}
	}

	public IndexRegistry(
		RegistryStorage storage,
		@ConfigProperty(name = "indexes.refreshInterval", defaultValue = "30s")
		Duration refreshInterval
	) {
		this.storage = storage;
		this.refreshInterval = refreshInterval;
	}

	/**
	 * Bring this node's copy of the registry up to date.
	 *
	 * @return
	 *   whether the registry could be read, {@code false} leaving the copy as it
	 *   was
	 */
	public boolean refresh() {
		var current = snapshot;

		try {
			switch(storage.read(current.version())) {
				case RegistryStorage.Read.Unchanged unchanged -> {
					// The copy this node holds is the stored one
				}
				case RegistryStorage.Read.Absent absent -> {
					if(current.version() != null) {
						snapshot = Snapshot.empty();
					}
				}
				case RegistryStorage.Read.Loaded loaded -> snapshot = Snapshot.of(
					RegistryCodec.fromStored(loaded.indexes()),
					loaded.version()
				);
			}

			readEver = true;
			return true;
		} catch(IOException e) {
			logger.atWarn()
				.setCause(e)
				.log(
					"Could not read the indexes, using the copy this node holds; "
						+ e.getMessage()
				);

			return false;
		}
	}

	/**
	 * Get whether this node has read the registry since it started. Until it
	 * has, an empty copy means nothing is known rather than that the
	 * deployment holds nothing. Stays {@code true} once a read has succeeded -
	 * a later one that fails leaves the node on the copy it has.
	 */
	public boolean hasBeenRead() {
		return readEver;
	}

	/**
	 * The names of every index the deployment holds, as of this node's copy.
	 */
	public ImmutableSet<String> names() {
		return snapshot.indexes().collect(RegisteredIndex::name, Sets.mutable.empty())
			.toImmutable();
	}

	/**
	 * Every index the deployment holds, ordered by name, as of this node's copy.
	 */
	public ListIterable<RegisteredIndex> list() {
		return snapshot.indexes();
	}

	/**
	 * Get one index, going back to the registry once per refresh interval when
	 * it is not one this node has seen.
	 *
	 * @param index
	 * @return
	 *   empty when the deployment holds no index by that name
	 */
	public Optional<RegisteredIndex> get(String index) {
		var found = snapshot.byName().get(index);
		if(found != null) {
			return Optional.of(found);
		}

		if(!forcedReadAllowed()) {
			return Optional.empty();
		}

		refresh();
		return Optional.ofNullable(snapshot.byName().get(index));
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
	 * Work out which generation a name answers from.
	 *
	 * @param name
	 *   the index, or one generation of it
	 * @return
	 *   the name with the generation that answers for it
	 * @throws IndexNotFoundException
	 *   if there is no such index, or it has no such generation
	 * @throws IndexUnsupportedException
	 *   if the index needs something this build does not have
	 * @throws IndexNoLiveGenerationException
	 *   if the index answers for none of its generations
	 */
	public IndexName resolve(IndexName name) {
		var index = get(name.index()).orElseThrow(
			() -> new IndexNotFoundException(name.toString())
		);

		var unsupported = index.unsupportedFeatures();
		if(unsupported.notEmpty()) {
			throw new IndexUnsupportedException(
				name.toString(),
				unsupported.toSortedList().makeString(", ")
			);
		}

		if(name.isPinned()) {
			if(!index.hasGeneration(name.generation())) {
				throw new IndexNotFoundException(name.toString());
			}

			return name;
		}

		var live = index.live();
		if(live == null) {
			throw new IndexNoLiveGenerationException(name.index());
		}

		return name.withGeneration(live);
	}

	/**
	 * Register an index with its first generation.
	 *
	 * @param index
	 * @param generation
	 *   name for the first generation
	 * @return
	 *   the index as it is now registered
	 * @throws ValidationException
	 *   if an index of that name already exists
	 */
	public RegisteredIndex create(String index, String generation) {
		var now = Instant.now();
		var created = new RegisteredIndex(
			index,
			Lists.immutable.of(new RegisteredIndex.Generation(generation, now)),
			generation,
			now,
			Sets.immutable.empty()
		);

		change(indexes -> {
			if(indexes.anySatisfy(existing -> existing.name().equals(index))) {
				throw new ValidationException(
					ALREADY_EXISTS.toMessage(ObjectLocation.root(), "name", index)
				);
			}

			return indexes.toList().with(created);
		});

		logger.atInfo()
			.addKeyValue("index", index)
			.addKeyValue("generation", generation)
			.log("Created index");

		return created;
	}

	/**
	 * Add a generation to an index, without making the index answer for it.
	 *
	 * @param index
	 * @param generation
	 * @return
	 *   the index as it is now registered
	 * @throws IndexNotFoundException
	 *   if there is no such index
	 * @throws ValidationException
	 *   if the index already has a generation of that name
	 */
	public RegisteredIndex addGeneration(String index, String generation) {
		var updated = update(index, entry -> {
			if(entry.hasGeneration(generation)) {
				throw new ValidationException(
					GENERATION_ALREADY_EXISTS.toMessage(
						ObjectLocation.root(),
						"name",
						IndexName.of(index, generation).toString()
					)
				);
			}

			return new RegisteredIndex(
				entry.name(),
				entry.generations().toList()
					.with(new RegisteredIndex.Generation(generation, Instant.now())),
				entry.live(),
				entry.createdAt(),
				entry.requiredFeatures()
			);
		});

		logger.atInfo()
			.addKeyValue("index", index)
			.addKeyValue("generation", generation)
			.log("Created generation");

		return updated;
	}

	/**
	 * Make an index answer for one of its generations, which is how a rebuilt
	 * index takes over without a caller changing anything.
	 *
	 * @param index
	 * @param generation
	 * @return
	 *   the index as it is now registered
	 * @throws IndexNotFoundException
	 *   if there is no such index, or it has no such generation
	 */
	public RegisteredIndex promote(String index, String generation) {
		var updated = update(index, entry -> {
			if(!entry.hasGeneration(generation)) {
				throw new IndexNotFoundException(
					IndexName.of(index, generation).toString()
				);
			}

			return new RegisteredIndex(
				entry.name(),
				entry.generations(),
				generation,
				entry.createdAt(),
				entry.requiredFeatures()
			);
		});

		logger.atInfo()
			.addKeyValue("index", index)
			.addKeyValue("generation", generation)
			.log("Promoted generation");

		return updated;
	}

	/**
	 * Take a generation out of the registry. The generation an index answers
	 * for is refused, so an index is never left answering for nothing by
	 * removing one.
	 *
	 * @param index
	 * @param generation
	 * @return
	 *   the index as it is now registered
	 * @throws IndexNotFoundException
	 *   if there is no such index, or it has no such generation
	 * @throws ValidationException
	 *   if the generation is the one the index answers for
	 */
	public RegisteredIndex removeGeneration(String index, String generation) {
		var updated = update(index, entry -> {
			if(!entry.hasGeneration(generation)) {
				throw new IndexNotFoundException(
					IndexName.of(index, generation).toString()
				);
			}

			if(generation.equals(entry.live())) {
				throw new ValidationException(
					GENERATION_IS_LIVE.toMessage(
						ObjectLocation.root(),
						"name",
						IndexName.of(index, generation).toString()
					)
				);
			}

			return new RegisteredIndex(
				entry.name(),
				entry.generations().reject(g -> g.name().equals(generation)),
				entry.live(),
				entry.createdAt(),
				entry.requiredFeatures()
			);
		});

		logger.atInfo()
			.addKeyValue("index", index)
			.addKeyValue("generation", generation)
			.log("Removed generation");

		return updated;
	}

	/**
	 * Take an index and every generation of it out of the registry.
	 *
	 * @param index
	 * @throws IndexNotFoundException
	 *   if there is no such index
	 */
	public void remove(String index) {
		change(indexes -> {
			if(indexes.noneSatisfy(entry -> entry.name().equals(index))) {
				throw new IndexNotFoundException(index);
			}

			return indexes.reject(entry -> entry.name().equals(index));
		});

		logger.atInfo()
			.addKeyValue("index", index)
			.log("Removed index");
	}

	/**
	 * A name for a generation of an index that has none of that name yet,
	 * counting up from one so that the newest generation of an index is the
	 * highest number it holds.
	 *
	 * @param index
	 *   the index, or {@code null} for one that does not exist yet
	 * @return
	 */
	public static String nextGeneration(RegisteredIndex index) {
		if(index == null) {
			return "1";
		}

		var next = 1;
		for(var generation : index.generations()) {
			try {
				next = Math.max(next, Integer.parseInt(generation.name()) + 1);
			} catch(NumberFormatException e) {
				// A generation named by hand says nothing about what comes next
			}
		}

		while(index.hasGeneration(Integer.toString(next))) {
			next++;
		}

		return Integer.toString(next);
	}

	/**
	 * Rewrite one index, leaving the rest of the registry as it is.
	 */
	private RegisteredIndex update(String index, UnaryOperator<RegisteredIndex> change) {
		var updated = new RegisteredIndex[1];

		change(indexes -> {
			var entry = indexes.detect(existing -> existing.name().equals(index));
			if(entry == null) {
				throw new IndexNotFoundException(index);
			}

			updated[0] = change.apply(entry);
			return indexes.reject(existing -> existing.name().equals(index))
				.toList()
				.with(updated[0]);
		});

		return updated[0];
	}

	/**
	 * Rewrite the registry, rebuilding the change on top of whatever else was
	 * written in the meantime rather than overwriting it.
	 */
	private void change(UnaryOperator<ListIterable<RegisteredIndex>> change) {
		for(int attempt = 0; attempt < WRITE_ATTEMPTS; attempt++) {
			if(!refresh()) {
				throw RegistryException.ioError(null);
			}

			var current = snapshot;
			var updated = change.apply(current.indexes());

			String version;
			try {
				version = storage.write(RegistryCodec.toStored(updated), current.version());
			} catch(IOException e) {
				throw RegistryException.ioError(e);
			}

			if(version != null) {
				snapshot = Snapshot.of(
					updated.toSortedListBy(RegisteredIndex::name).toImmutable(),
					version
				);

				return;
			}
		}

		throw RegistryException.conflict();
	}
}
