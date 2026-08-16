package se.l4.exofind.engine.index.state;

import java.io.IOException;
import java.util.OptionalInt;
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
