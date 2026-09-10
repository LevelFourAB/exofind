package se.l4.exofind.engine.index.state;

import java.io.IOException;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

public interface StateSync {
	/**
	 * Push changes to the remote. This will initiate a sync operation, and
	 * should be called when the local state has changed.
	 *
	 * @param files
	 *        names of the files the index consists of, relative to the local
	 *        directory. Anything else the directory holds is work in progress
	 *        or a leftover and is not part of what other nodes read. The caller
	 *        has to keep these files from being removed until the push returns
	 * @throws SyncConflictException
	 *         if the remote was changed by another node since this node last
	 *         synchronized with it. Nothing has been overwritten, and pulling
	 *         is what brings the local copy back in step with the remote
	 * @throws IOException if an error occurs while pushing the changes
	 */
	void push(Set<String> files) throws IOException;

	/**
	 * Claim the right to write the index, for the writer that is about to
	 * open. What the claim leaves behind is what every push of this session is
	 * conditional on, so a node whose claim on the index lapsed while it was
	 * paused is refused by the remote rather than replacing what a successor
	 * has already written.
	 *
	 * <p>Claimed here rather than before the first push, because the successor
	 * acknowledges writes from the moment its writer opens. Claiming later
	 * would leave the whole span between the takeover and the first push of
	 * the successor with nothing refusing the node it took over from.
	 *
	 * <p>Called every time a writer opens, not once per instance: an index
	 * that was lost and taken again continues from a manifest another node
	 * wrote, and the claim is what says who writes it now.
	 *
	 * @throws SyncConflictException
	 *   if the remote was changed by another node since this node last
	 *   synchronized with it, which is what losing the index looks like from
	 *   here. Nothing has been overwritten, and pulling is what brings the
	 *   local copy back in step
	 * @throws IOException
	 *   if the claim could not be written
	 */
	void claimWriter() throws IOException;

	/**
	 * Remove objects under the index that no manifest names anymore, for a
	 * caller that holds the write claim on the index.
	 *
	 * <p>Call this from a timer for an index that receives no writes. A push
	 * runs the same sweep, so an index that is written needs no call. The
	 * sweep runs at most once per grace period whatever the caller does, and
	 * it removes only objects older than that period. A call that finds the
	 * remote changed since this node last synchronized removes nothing.
	 *
	 * <p>Blocks on the remote: one metadata request on every call that is due
	 * and a listing of the whole index when the sweep runs. Failures during
	 * the listing and the removals are logged and left for a later call.
	 *
	 * <p>The default does nothing, for an implementation that shares nothing
	 * with other nodes.
	 *
	 * @throws IOException
	 *   if the remote could not be asked whether the sweep is safe to run
	 */
	default void sweep() throws IOException {
	}

	/**
	 * Pull the latest changes from the remote.
	 *
	 * @throws SyncIncompatibleException
	 *         if the remote says the index was created by a Lucene version this
	 *         build can no longer read. Thrown before anything is downloaded,
	 *         as the files would be of no use here
	 * @throws IOException if an error occurs while pulling the changes
	 * @return true if changes were pulled, false if no changes were available
	 */
	boolean pull() throws IOException;

	/**
	 * The version of the manifest this node last synchronized, whether it was
	 * pulled or pushed. What it is compared against is the version another
	 * writer reported, so a copy already at it need not ask the remote.
	 *
	 * <p>Empty when nothing recorded one: nothing has been synchronized yet,
	 * the manifest predates versions, or the implementation keeps no state of
	 * its own.
	 *
	 * @return
	 */
	OptionalLong syncedVersion();

	/**
	 * Whether the copy this node last synchronized holds a Lucene commit. Read
	 * from the record of that synchronization and not from the directory, so
	 * that it says what the directory has to hold. A directory that holds no
	 * commit under such a record is a local copy that lost files, and opening
	 * it as an empty index would publish that loss with the next push.
	 *
	 * <p>False for an index nothing has been committed to yet, and for an
	 * implementation that keeps no record of its own.
	 *
	 * @return
	 */
	boolean hasSyncedCommit();

	/**
	 * The major Lucene version the index was created with, as the last
	 * synchronization recorded it.
	 *
	 * Empty when nothing recorded one, which is what a synchronization written
	 * before the version was tracked looks like, and what an implementation
	 * that keeps no state of its own has to say. Reading the segments is the
	 * only way to find out from there.
	 *
	 * @return
	 */
	OptionalInt luceneCreatedMajor();
}
