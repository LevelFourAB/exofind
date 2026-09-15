package se.l4.exofind.engine.patch;

import org.eclipse.collections.api.list.ListIterable;

/**
 * The field that says which entry of a list a change names, for each list the
 * target holds.
 *
 * <p>A selector holding a single word names an entry by its key, as
 * {@code ranking.signals[sales]}. The key is not written in the path, so the
 * target supplies it: an index definition declares one per object field, and
 * the search settings model declares one per list.
 *
 * <p>What a key names depends on the list. A key of an object field of a
 * document names at most one value, because a document holding two values under
 * one key is refused when it is indexed. A key of a list of the search settings
 * carries no such rule, so {@code ranking.signals[sales]} names every signal
 * reading {@code sales} the way {@code ranking.signals[field=sales]} does.
 *
 * <p>Implementations are stateless and safe to call from several threads.
 */
@FunctionalInterface
public interface PatchKeys {
	/** A target whose lists declare no key, so every single word is refused. */
	PatchKeys NONE = names -> null;

	/**
	 * Get the key of the list one path reaches.
	 *
	 * @param names
	 *   the names of the path down to and including the one carrying the
	 *   selector, with every escape resolved
	 * @return
	 *   the name of the field inside an entry that says which entry it is, or
	 *   {@code null} when the list this reaches declares no key
	 */
	String keyOf(ListIterable<String> names);
}
