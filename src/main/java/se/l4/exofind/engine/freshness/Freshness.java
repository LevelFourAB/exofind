package se.l4.exofind.engine.freshness;

import java.util.Objects;

import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.IndexName;

/**
 * A state of an index that a read demands to see, or that an answer came from.
 *
 * <p>A change to an index returns the state it lands in, and a read can hand
 * that state back to demand an answer that holds the change. Three things make
 * up the state, and each is left out when the change said nothing about it:
 *
 * <ul>
 *   <li>the generation the index answers from, so a promotion is visible;
 *   <li>the {@link Index#visibleCommit() commit sequence} of that generation,
 *     so a document write, a removal or a commit is visible;
 *   <li>the version of the search settings, so a settings change is visible.
 * </ul>
 *
 * <p>The commit sequence is ordered only inside one generation. Generations
 * are ordered by when they were created, and settings versions are not ordered
 * at all: a read satisfies a settings version by having read the storage since
 * the version was written, which {@link FreshnessWaiter} arranges.
 *
 * @param index
 *   name of the index, without a generation
 * @param generation
 *   the generation, or {@code null} when the state says nothing about one
 * @param commit
 *   the commit sequence to reach in that generation, or zero when the state
 *   says nothing about one
 * @param settingsVersion
 *   the version of the search settings to have seen, the empty string for
 *   settings that were removed, or {@code null} when the state says nothing
 *   about them
 */
public record Freshness(
	String index,
	String generation,
	long commit,
	String settingsVersion
) {
	public Freshness {
		Objects.requireNonNull(index, "index");

		if(commit < 0) {
			throw new IllegalArgumentException("commit must be zero or above");
		}

		if(commit > 0 && generation == null) {
			throw new IllegalArgumentException("a commit sequence belongs to a generation");
		}
	}

	/**
	 * The state a change to the contents of a generation lands in.
	 *
	 * @param generation
	 *   the generation that was changed
	 * @param commit
	 *   the commit sequence the change lands in, see
	 *   {@link Index.Change#landsIn()}
	 * @return
	 */
	public static Freshness ofCommit(IndexName generation, long commit) {
		return new Freshness(generation.index(), generation.generation(), commit, null);
	}

	/**
	 * The state a promotion lands in: the index answering from a generation.
	 *
	 * @param generation
	 *   the generation that was promoted
	 * @return
	 */
	public static Freshness ofGeneration(IndexName generation) {
		return new Freshness(generation.index(), generation.generation(), 0, null);
	}

	/**
	 * The state a change to the search settings lands in.
	 *
	 * @param index
	 *   name of the index, without a generation
	 * @param version
	 *   the version the settings are now at, or {@code null} when they were
	 *   removed
	 * @return
	 */
	public static Freshness ofSettings(String index, String version) {
		return new Freshness(index, null, 0, version == null ? "" : version);
	}

	/**
	 * Whether this state includes a generation.
	 */
	public boolean hasGeneration() {
		return generation != null;
	}

	/**
	 * Whether this state includes a commit sequence.
	 */
	public boolean hasCommit() {
		return commit > 0;
	}

	/**
	 * Whether this state includes a version of the search settings, including
	 * their removal.
	 */
	public boolean hasSettingsVersion() {
		return settingsVersion != null;
	}
}
