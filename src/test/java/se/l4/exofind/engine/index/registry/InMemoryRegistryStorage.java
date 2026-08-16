package se.l4.exofind.engine.index.registry;

import java.io.IOException;

/**
 * RegistryStorage in memory, with the same conditional write the object storage
 * has - which is what the tests about racing writers need.
 */
public class InMemoryRegistryStorage implements RegistryStorage {
	private IndexRegistryStore indexes;
	private String version;
	private int versions;

	/**
	 * Number of reads that have been served, for checking that a node reads the
	 * registry as rarely as it says it does.
	 */
	public int reads;

	/**
	 * When set, the next write is refused as though someone else had written
	 * first, and the flag clears itself.
	 */
	public boolean refuseNextWrite;

	/**
	 * When set, every write is refused, standing in for something writing the
	 * registry continuously.
	 */
	public boolean refuseEveryWrite;

	/**
	 * When set, every read and write fails as though the storage were
	 * unreachable.
	 */
	public boolean unreachable;

	@Override
	public Read read(String knownVersion) throws IOException {
		reads++;

		if(unreachable) {
			throw new IOException("Storage is unreachable");
		}

		if(indexes == null) {
			return new Read.Absent();
		}

		if(knownVersion != null && knownVersion.equals(version)) {
			return new Read.Unchanged();
		}

		return new Read.Loaded(indexes, version);
	}

	@Override
	public String write(IndexRegistryStore indexes, String expectedVersion) throws IOException {
		if(unreachable) {
			throw new IOException("Storage is unreachable");
		}

		if(refuseEveryWrite) {
			return null;
		}

		if(refuseNextWrite) {
			refuseNextWrite = false;
			return null;
		}

		if(expectedVersion == null ? version != null : !expectedVersion.equals(version)) {
			return null;
		}

		this.indexes = indexes;
		this.version = "v" + (++versions);

		return version;
	}

	/**
	 * Replace what is stored without going through a conditional write, for
	 * setting a test up or standing in for another node having written.
	 */
	public void set(IndexRegistryStore indexes) {
		this.indexes = indexes;
		this.version = "v" + (++versions);
	}
}
