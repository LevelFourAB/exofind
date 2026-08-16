package se.l4.exofind.engine.auth;

import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.set.ImmutableSet;
import org.eclipse.collections.api.set.SetIterable;

/**
 * The engine features a stored key can need, and which of them this build has.
 *
 * <p>Protobuf keeps fields it has no code for, so a node reading a key written
 * by a newer version sees the parts it understands and drops the rest. For a
 * grant that is safe on its own - a grant only adds, so a node that drops part
 * of one allows less than was written. What is not safe is anything that
 * narrows a key: a node that dropped it would read the key as the wider one and
 * allow more than was granted. Whichever version stores a key therefore writes
 * down what the key's meaning depends on, and a node that does not know a name
 * on that list refuses the key rather than honouring it.
 *
 * <p>This only works if the names are stable, so a name here is never renamed
 * or reused once released - it is a value written to disk, not an identifier.
 *
 * <p>Everything a key can currently say shipped together, so there is nothing
 * to name yet and no key written by this build carries a required feature. A
 * later version that adds a way of narrowing a key adds its name here and
 * writes it into the keys that use it.
 */
public final class AuthFeatures {
	private static final ImmutableSet<String> SUPPORTED = Sets.immutable.empty();

	private AuthFeatures() {
	}

	/**
	 * Get the features a stored key asks for that this build does not have.
	 *
	 * @param key
	 * @return
	 *   the names, empty when the key can be honoured here
	 */
	public static SetIterable<String> unsupportedIn(KeyDef key) {
		var unsupported = Sets.mutable.<String>empty();

		for(var feature : key.getRequiredFeaturesList()) {
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
