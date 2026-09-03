package se.l4.exofind.engine.index.state;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Predicate;

import org.eclipse.collections.api.list.ListIterable;

import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.IndexStorageHeldException;

/**
 * What a deleted index or generation leaves in the storage the deployment
 * shares, and how it goes away.
 *
 * <p>Deleting takes the name out of the registry, which is what makes it gone
 * for the deployment. The objects stay for a while: the delete leaves a
 * <em>removal mark</em> beside them, and a sweep removes the marked prefix once
 * the mark is older than a grace period. Until then an operator can take the
 * delete back by clearing the mark and repairing the registry, and the whole
 * removal - which can be many thousands of objects - happens off the request
 * that asked for it.
 *
 * <p>Only a mark says that data was deleted. A prefix the registry does not
 * name is not one: the registry may have been lost, and a lost registry is
 * repaired from these very objects. Nothing here removes an object that has
 * no mark over it.
 *
 * <p>A name deleted and created again lands on the same prefix, so creating
 * clears a marked prefix at once rather than waiting for the sweep. The order
 * in which objects go is the same either way: the manifests and the settings
 * first, so that an interrupted removal leaves nothing a node could serve or
 * a repair could register, then the mark when a creation is clearing, and
 * the rest last. A sweep keeps its mark until the end instead, and looks for
 * it before every batch: a creation that cleared the mark meanwhile has taken
 * the prefix back, and the sweep stops rather than remove what the new
 * generation is writing.
 *
 * <p>Implementations are safe for concurrent use.
 */
public interface IndexRemovals {
	/**
	 * Name of the object a delete leaves beside what it deleted. Under the
	 * index prefix it marks the whole index, under a generation prefix that
	 * generation alone. Part of the storage layout, so it is never renamed.
	 */
	String MARK_FILE = "removed.ef.bin";

	/**
	 * A mark as the storage holds it.
	 *
	 * @param target
	 *   the index, or one generation of it
	 * @param removedAt
	 *   when the delete happened
	 */
	record Mark(IndexName target, Instant removedAt) {
	}

	/**
	 * Mark an index or one generation of it as deleted, now. Called after the
	 * registry no longer names it; a mark over something the registry names
	 * is void, and a sweep passes it over.
	 *
	 * @param target
	 *   the index, or one generation of it
	 * @throws IOException
	 *   if the mark could not be written
	 */
	void mark(IndexName target) throws IOException;

	/**
	 * Take a mark back, for an operator restoring what was deleted before the
	 * sweep got to it.
	 *
	 * @param target
	 * @return
	 *   whether there was a mark to take back
	 * @throws IOException
	 */
	boolean unmark(IndexName target) throws IOException;

	/**
	 * When an index or generation was marked as deleted.
	 *
	 * @param target
	 * @return
	 *   the time of the delete, or empty when there is no mark
	 * @throws IOException
	 *   if the storage could not be asked, or the mark can not be read
	 */
	Optional<Instant> markedAt(IndexName target) throws IOException;

	/**
	 * Every mark the storage holds over a prefix the caller cares about. A
	 * marked index is reported once, as the index, without its generations
	 * being looked at.
	 *
	 * @param wanted
	 *   which indexes and generations to read a mark for, typically those
	 *   the registry does not name. The others are listed past without a
	 *   request being made for them
	 * @return
	 *   the marks, in the order the storage lists them
	 * @throws IOException
	 *   if the storage could not be listed
	 */
	ListIterable<Mark> listMarks(Predicate<IndexName> wanted) throws IOException;

	/**
	 * Remove everything under a marked prefix, the mark last. The mark is
	 * looked for before every batch, and the removal stops - leaving what is
	 * left for a later sweep, or for the creation that took the mark away -
	 * when it is gone.
	 *
	 * @param target
	 *   the index, or one generation of it
	 * @return
	 *   whether everything went, {@code false} when the mark disappeared
	 *   before the removal was done
	 * @throws IOException
	 *   if the storage could not be listed or objects could not be removed;
	 *   what was removed so far stays removed, and the mark stays
	 */
	boolean remove(IndexName target) throws IOException;

	/**
	 * Make room for an index about to be created under a name: when its
	 * prefix carries a mark, everything under it goes now, settings and all.
	 * Called once the name is registered, so that of two nodes creating the
	 * same name only the one that won the registration clears the prefix.
	 *
	 * <p>Says nothing about the generation the index starts with, which
	 * {@link #prepareForGeneration(IndexName)} checks in turn.
	 *
	 * @param index
	 *   name of the index, without a generation
	 * @throws IOException
	 */
	void prepareForIndex(String index) throws IOException;

	/**
	 * Make room for a generation about to be created: when its prefix carries
	 * a mark, everything under it goes now. Only the generation's own prefix
	 * is touched, never the index's - a mark over a registered index is void,
	 * and a generation being added to it must not act on one.
	 *
	 * @param generation
	 * @throws IndexStorageHeldException
	 *   if the prefix holds a manifest and no mark, which is data nothing
	 *   said was deleted
	 * @throws IOException
	 */
	void prepareForGeneration(IndexName generation) throws IOException;
}
