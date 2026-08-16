package se.l4.exofind.engine.index.registry;

import java.time.Instant;
import java.util.Optional;

import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.set.SetIterable;

/**
 * One index as the registry holds it: the generations that exist under its
 * name, and which of them the name answers for.
 *
 * @param name
 *   name of the index, which outlives every generation under it
 * @param generations
 *   the generations that exist, ordered by name
 * @param live
 *   name of the generation the index answers for, or {@code null} when it
 *   answers for none
 * @param createdAt
 *   when the index was created, or {@code null} for one registered before that
 *   was recorded
 * @param requiredFeatures
 *   names the entry says its meaning depends on, kept as they were stored so
 *   that a node rewriting the registry carries on names it does not know itself
 */
public record RegisteredIndex(
	String name,
	ListIterable<Generation> generations,
	String live,
	Instant createdAt,
	SetIterable<String> requiredFeatures
) {
	/**
	 * One generation of an index - one set of Lucene files with one definition
	 * over them.
	 *
	 * @param name
	 *   name of the generation, unique within its index
	 * @param createdAt
	 *   when the generation was created, or {@code null} for one registered
	 *   before that was recorded
	 */
	public record Generation(String name, Instant createdAt) {
	}

	/**
	 * The generation this index answers for, empty when it answers for none.
	 */
	public Optional<String> liveGeneration() {
		return Optional.ofNullable(live);
	}

	/**
	 * Whether a generation of this name exists.
	 *
	 * @param generation
	 * @return
	 */
	public boolean hasGeneration(String generation) {
		return generations.anySatisfy(g -> g.name().equals(generation));
	}

	/**
	 * The features this index says its meaning depends on that this build does
	 * not have. An index with any of these is refused rather than resolved, as
	 * resolving it would mean answering from a generation the entry did not
	 * name.
	 */
	public SetIterable<String> unsupportedFeatures() {
		return RegistryFeatures.unsupportedIn(requiredFeatures);
	}

	/**
	 * Whether this build can use this index at all.
	 */
	public boolean isSupported() {
		return unsupportedFeatures().isEmpty();
	}
}
