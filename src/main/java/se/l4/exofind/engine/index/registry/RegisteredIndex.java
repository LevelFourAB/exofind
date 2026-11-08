package se.l4.exofind.engine.index.registry;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

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
 * @param registration
 *   random identifier that the create of the index gave it, or {@code null}
 *   for one registered before that was recorded, or added by a registry
 *   repair. See {@link #isSameRegistration}
 * @param requiredFeatures
 *   names the entry says its meaning depends on, kept as they were stored so
 *   that a node rewriting the registry carries on names it does not know itself
 * @param settingsVersion
 *   version of the index's search settings object as last reported, the empty
 *   string when the index is known to have none, or {@code null} when nothing
 *   is said - a hint that spares reading the object, never the truth about it
 */
public record RegisteredIndex(
	String name,
	ListIterable<Generation> generations,
	String live,
	Instant createdAt,
	String registration,
	SetIterable<String> requiredFeatures,
	String settingsVersion
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
	 * @param registration
	 *   random identifier that the create of the generation gave it, or
	 *   {@code null} for one registered before that was recorded, or added by
	 *   a registry repair
	 * @param manifestVersion
	 *   version of the generation's manifest as its writer last reported it, or
	 *   {@code null} when nothing is said - a hint like the settings version on
	 *   the index
	 */
	public record Generation(
		String name,
		Instant createdAt,
		String registration,
		Long manifestVersion
	) {
		/**
		 * Whether this generation and {@code other} come from the same
		 * create, and not from two creates of the same name. See
		 * {@link RegisteredIndex#isSameRegistration}.
		 *
		 * @param other
		 * @return
		 */
		public boolean isSameRegistration(Generation other) {
			return sameRegistration(registration, createdAt, other.registration, other.createdAt);
		}
	}

	/**
	 * Whether this index and {@code other} come from the same create, and not
	 * from two creates of the same name.
	 *
	 * <p>When one of them carries a registration identifier, the identifiers
	 * decide, and one with an identifier is never the same as one without.
	 * When neither carries one, the creation times decide, compared to the
	 * millisecond that the registry stores. Two unknown times are the same.
	 *
	 * @param other
	 * @return
	 */
	public boolean isSameRegistration(RegisteredIndex other) {
		return sameRegistration(registration, createdAt, other.registration, other.createdAt);
	}

	private static boolean sameRegistration(
		String registration,
		Instant createdAt,
		String otherRegistration,
		Instant otherCreatedAt
	) {
		if(registration != null || otherRegistration != null) {
			return Objects.equals(registration, otherRegistration);
		}

		if(createdAt == null || otherCreatedAt == null) {
			return createdAt == otherCreatedAt;
		}

		return createdAt.toEpochMilli() == otherCreatedAt.toEpochMilli();
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
	 * One generation by name, empty when the index has none of that name.
	 *
	 * @param generation
	 * @return
	 */
	public Optional<Generation> generation(String generation) {
		return Optional.ofNullable(
			generations.detect(g -> g.name().equals(generation))
		);
	}

	/**
	 * The manifest version last reported for a generation, empty when nothing
	 * is said - the generation does not exist, or no writer has reported one.
	 *
	 * @param generation
	 * @return
	 */
	public OptionalLong manifestVersion(String generation) {
		var found = generations.detect(g -> g.name().equals(generation));
		return found == null || found.manifestVersion() == null
			? OptionalLong.empty()
			: OptionalLong.of(found.manifestVersion());
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
