package se.l4.exofind.engine.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The files that only a node in {@link StorageMode#LOCAL} writes into its
 * local storage directory.
 *
 * <p>In local mode the index directories are the only copy of the indexes. A
 * node in {@link StorageMode#OBJECT} takes each index directory for a copy of
 * the object storage, and removes a copy that its registry does not name. Over
 * a directory that local mode wrote, that removes the local indexes. These
 * files identify such a directory, so that a node in object mode can refuse
 * it.
 */
public final class LocalModeFiles {
	/**
	 * Name of the index registry of a node in local mode, in the root of the
	 * local storage directory.
	 */
	public static final String REGISTRY_FILE = "registry.ef.bin";

	/**
	 * Name of the API keys of a node in local mode, in the root of the local
	 * storage directory.
	 */
	public static final String KEYS_FILE = "keys.ef.bin";

	private static final List<String> FILES = List.of(REGISTRY_FILE, KEYS_FILE);

	private LocalModeFiles() {
	}

	/**
	 * Make sure that local mode did not write into the directory, before a
	 * node uses it for copies of an object storage.
	 *
	 * @param directory
	 *   the local storage directory, as {@code EXOFIND_STORAGE_LOCAL_DIRECTORY}
	 *   names it
	 * @throws IllegalStateException
	 *   if the directory holds the registry or the keys of a node in local
	 *   mode
	 */
	public static void requireNoneIn(Path directory) {
		for(var name : FILES) {
			var file = directory.resolve(name);
			if(Files.exists(file)) {
				throw new IllegalStateException(
					"The local storage directory " + directory + " holds " + name
						+ ", which a node in local storage mode writes. In local mode the"
						+ " index directories are the only copy of the indexes, and a node"
						+ " in object mode removes the index directories that its registry"
						+ " does not name. Give this node an empty directory through"
						+ " EXOFIND_STORAGE_LOCAL_DIRECTORY, or set EXOFIND_STORAGE_MODE=local"
						+ " to use the local indexes"
				);
			}
		}
	}
}
