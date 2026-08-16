package se.l4.exofind.engine.index.registry;

import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.set.ImmutableSet;
import org.eclipse.collections.api.set.SetIterable;

/**
 * The engine features a registered index can need, and which of them this build
 * has.
 *
 * <p>Protobuf keeps fields it has no code for, so a node reading an entry
 * written by a newer version sees the parts it understands and drops the rest.
 * For an entry that is never safe on its own: resolving a name is something a
 * caller never sees happen, so a node that dropped half of what an entry said
 * would answer from the wrong generation and look no different from one that
 * answered correctly. Whichever version writes an entry therefore writes down
 * what its meaning depends on, and a node that does not know a name on that
 * list refuses the index rather than resolving it as something else.
 *
 * <p>This only works if the names are stable, so a name here is never renamed
 * or reused once released - it is a value written to disk, not an identifier.
 *
 * <p>Everything an entry can currently say shipped together, so there is
 * nothing to name yet and no entry written by this build carries a required
 * feature. A later version that gives an entry a second generation to mean
 * something - one taking writes while another answers searches, say - adds its
 * name here and writes it into the entries that use it.
 */
public final class RegistryFeatures {
	private static final ImmutableSet<String> SUPPORTED = Sets.immutable.empty();

	private RegistryFeatures() {
	}

	/**
	 * Get the features a registered index asks for that this build does not
	 * have.
	 *
	 * @param entry
	 * @return
	 *   the names, empty when the index can be used here
	 */
	public static SetIterable<String> unsupportedIn(IndexEntry entry) {
		return unsupportedIn(entry.getRequiredFeaturesList());
	}

	/**
	 * Get the features named that this build does not have.
	 *
	 * @param required
	 *   names the entry says its meaning depends on
	 * @return
	 *   the names, empty when the index can be used here
	 */
	public static SetIterable<String> unsupportedIn(Iterable<String> required) {
		var unsupported = Sets.mutable.<String>empty();

		for(var feature : required) {
			if(!SUPPORTED.contains(feature)) {
				unsupported.add(feature);
			}
		}

		return unsupported;
	}

	/**
	 * Get the names this build supports.
	 */
	public static SetIterable<String> supported() {
		return SUPPORTED;
	}
}
