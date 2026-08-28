package se.l4.exofind.engine.reindex;

import java.io.IOException;
import java.util.Optional;

import org.eclipse.collections.api.list.ListIterable;

/**
 * Where the reindex job records are kept, one per index under a common
 * prefix. The records share the storage the indexes live in, so a job
 * survives the node running it and any node can answer where one stands.
 *
 * <p>Records are replaced conditionally on the version they were read at, so
 * a node resuming a job another node still believes it runs is refused rather
 * than overwritten - the loser of the race reads the record back and finds
 * the job is no longer its.
 */
public interface ReindexJobStorage {
	/**
	 * Read the record of one index.
	 *
	 * @param index
	 *   name of the index, without a generation
	 * @return
	 *   empty when the index has no record
	 * @throws IOException
	 *   if the storage could not be reached
	 */
	Optional<Stored> read(String index) throws IOException;

	/**
	 * Replace the record of one index, conditionally.
	 *
	 * @param index
	 *   name of the index, without a generation
	 * @param record
	 * @param expectedVersion
	 *   version the current record is expected to have, or {@code null} when
	 *   there is expected to be none
	 * @return
	 *   the version the written record is held under, or {@code null} when
	 *   the write was refused because the record no longer had the expected
	 *   version
	 * @throws IOException
	 *   if the storage could not be reached
	 */
	String write(String index, ReindexJobStore record, String expectedVersion) throws IOException;

	/**
	 * Remove the record of one index. Removing one that is not there changes
	 * nothing.
	 *
	 * @param index
	 *   name of the index, without a generation
	 * @throws IOException
	 *   if the storage could not be reached
	 */
	void delete(String index) throws IOException;

	/**
	 * Every record there is, in no particular order.
	 *
	 * @throws IOException
	 *   if the storage could not be reached
	 */
	ListIterable<Stored> list() throws IOException;

	/**
	 * A record together with the version it was read at, which is what a
	 * conditional {@link #write} is made against.
	 */
	record Stored(ReindexJobStore record, String version) {
	}
}
