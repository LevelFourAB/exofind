package se.l4.exofind.engine.index.settings;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * SearchSettingsStorage in memory, with the same conditional write the object
 * storage has - which is what the tests about racing writers need.
 */
public class InMemorySearchSettingsStorage implements SearchSettingsStorage {
	private record Stored(SearchSettingsStore settings, String version) {
	}

	private final Map<String, Stored> stored = new HashMap<>();
	private int versions;

	/**
	 * Number of reads that have been served, for checking that a node reads
	 * the storage as rarely as it says it does.
	 */
	public int reads;

	/**
	 * When set, the next write is refused as though someone else had written
	 * first, and the flag clears itself.
	 */
	public boolean refuseNextWrite;

	/**
	 * When set, every read and write fails as though the storage were
	 * unreachable.
	 */
	public boolean unreachable;

	@Override
	public boolean isAvailable() {
		return true;
	}

	@Override
	public synchronized Read read(String index, String knownVersion) throws IOException {
		reads++;

		if(unreachable) {
			throw new IOException("Storage is unreachable");
		}

		var current = stored.get(index);
		if(current == null) {
			return new Read.Absent();
		}

		if(knownVersion != null && knownVersion.equals(current.version())) {
			return new Read.Unchanged();
		}

		return new Read.Loaded(current.settings(), current.version());
	}

	@Override
	public synchronized String write(
		String index,
		SearchSettingsStore settings,
		String expectedVersion
	)
		throws IOException
	{
		if(unreachable) {
			throw new IOException("Storage is unreachable");
		}

		if(refuseNextWrite) {
			refuseNextWrite = false;
			return null;
		}

		var current = stored.get(index);
		var version = current == null ? null : current.version();
		if(expectedVersion == null ? version != null : !expectedVersion.equals(version)) {
			return null;
		}

		var next = "\"v" + (++versions) + "\"";
		stored.put(index, new Stored(settings, next));

		return next;
	}

	@Override
	public synchronized void delete(String index) throws IOException {
		if(unreachable) {
			throw new IOException("Storage is unreachable");
		}

		stored.remove(index);
	}

	/**
	 * Replace what is stored without going through a conditional write, for
	 * setting a test up or standing in for another node having written.
	 */
	public synchronized void set(String index, SearchSettingsStore settings) {
		stored.put(index, new Stored(settings, "\"v" + (++versions) + "\""));
	}

	/**
	 * What one index's settings are stored as, or {@code null} when there are
	 * none.
	 */
	public synchronized SearchSettingsStore get(String index) {
		var current = stored.get(index);
		return current == null ? null : current.settings();
	}
}
