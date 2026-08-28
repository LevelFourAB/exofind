package se.l4.exofind.engine.index.settings;

import java.io.IOException;

/**
 * Where the search settings of the indexes are kept, one object per index
 * name.
 *
 * <p>Each object is read and replaced as a whole. Replacing it is conditional
 * on the version the writer last read, so two nodes changing the settings of
 * the same index at the same time can not lose one another's change - which is
 * what lets any node manage settings rather than only the one holding the
 * indexer role.
 *
 * <p>The settings belong to the index name rather than to a generation, so
 * promoting a generation keeps them and every generation answers with the same
 * ones.
 *
 * <p>Implementations are safe for concurrent use.
 */
public interface SearchSettingsStorage {
	/**
	 * Whether this node has somewhere to keep settings. A node that named an
	 * object storage it cannot use has not, and searches with the definitions
	 * alone.
	 */
	boolean isAvailable();

	/**
	 * Read the settings of one index.
	 *
	 * <p>Versions are opaque to compare, but always carry the surrounding
	 * quotes of an entity tag - which is what lets the API strip them for an
	 * {@code ETag} header and put them back from an {@code If-Match}.
	 *
	 * @param index
	 *   name of the index, without a generation
	 * @param knownVersion
	 *   version the caller already holds, or {@code null} to read whatever is
	 *   there
	 * @return
	 * @throws IOException
	 *   if the storage could not be reached
	 */
	Read read(String index, String knownVersion) throws IOException;

	/**
	 * Replace the settings of one index.
	 *
	 * @param index
	 *   name of the index, without a generation
	 * @param settings
	 * @param expectedVersion
	 *   version the settings are expected to be at, or {@code null} when they
	 *   are expected not to exist yet
	 * @return
	 *   the version the written settings are now at, or {@code null} when the
	 *   write was refused because someone else changed them first
	 * @throws IOException
	 *   if the storage could not be reached, or this node has nowhere to keep
	 *   settings
	 */
	String write(String index, SearchSettingsStore settings, String expectedVersion)
		throws IOException;

	/**
	 * Remove the settings of one index, returning it to searching with its
	 * definition alone. Removing what is not there changes nothing, so the
	 * call can be repeated.
	 *
	 * @param index
	 *   name of the index, without a generation
	 * @throws IOException
	 *   if the storage could not be reached, or this node has nowhere to keep
	 *   settings
	 */
	void delete(String index) throws IOException;

	/**
	 * What a read found.
	 */
	sealed interface Read {
		/**
		 * The settings as the storage holds them.
		 */
		record Loaded(SearchSettingsStore settings, String version) implements Read {
		}

		/**
		 * The settings are still at the version the caller already holds.
		 */
		record Unchanged() implements Read {
		}

		/**
		 * The index has no settings, which is what an index that searches with
		 * its definition alone looks like.
		 */
		record Absent() implements Read {
		}
	}
}
