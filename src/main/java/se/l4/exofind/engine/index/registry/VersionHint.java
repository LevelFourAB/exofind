package se.l4.exofind.engine.index.registry;

/**
 * One version a writer reports into the registry, so that other nodes can tell
 * from the registry alone - which they already read - whether an object of an
 * index is worth asking the storage for.
 *
 * <p>A hint is advisory: the object itself stays the truth, and every node
 * still reads it at an interval regardless of what the hints say. A hint that
 * is stale, lost or dropped by an older node rewriting the registry therefore
 * costs extra reads or a bounded delay, never a wrong answer - which is also
 * why folding hints in never fails a caller.
 */
public sealed interface VersionHint {
	/**
	 * The index the hint is about.
	 */
	String index();

	/**
	 * Version of an index's search settings object as it was stored.
	 *
	 * @param index
	 * @param version
	 *   the stored version, or the empty string when the index has no settings
	 */
	record Settings(String index, String version) implements VersionHint {
	}

	/**
	 * Version of a generation's manifest as its writer pushed it.
	 *
	 * @param index
	 * @param generation
	 * @param version
	 */
	record Manifest(String index, String generation, long version) implements VersionHint {
	}
}
