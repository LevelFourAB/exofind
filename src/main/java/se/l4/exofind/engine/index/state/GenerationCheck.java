package se.l4.exofind.engine.index.state;

import java.io.IOException;
import java.time.Instant;

/**
 * Tells whether the registry still names the generation that a copy of an
 * index belongs to.
 *
 * <p>A push that finds the remote manifest gone asks this before it writes
 * the manifest again from its local copy. The manifest is gone when the
 * generation was deleted, and also when the prefix was emptied by hand or by
 * a lifecycle rule. Only in the second case is the local copy the data of the
 * generation the registry names. The first generation of every index has the
 * same name, so a generation created again under the same name shares the
 * prefix of the deleted one. Only the time of creation tells them apart.
 */
@FunctionalInterface
public interface GenerationCheck {
	/**
	 * A check that takes every generation as registered, for a sync that has
	 * no registry to ask.
	 */
	GenerationCheck ALWAYS = createdAt -> true;

	/**
	 * Whether the registry names the generation, created at the given time.
	 *
	 * @param createdAt
	 *   when the generation the copy belongs to was created, or {@code null}
	 *   when that is not known. Then the name alone decides. A registry entry
	 *   that does not state the time also matches on the name alone
	 * @return
	 *   {@code false} when the registry does not name the generation, names
	 *   one created at another time, or cannot be read as a registry
	 * @throws IOException
	 *   if the registry could not be read
	 */
	boolean isRegistered(Instant createdAt) throws IOException;
}
