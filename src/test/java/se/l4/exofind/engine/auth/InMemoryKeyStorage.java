package se.l4.exofind.engine.auth;

import java.io.IOException;

/**
 * KeyStorage in memory, with the same conditional write the object storage
 * has - which is what the tests about racing writers need.
 */
public class InMemoryKeyStorage implements KeyStorage {
	private KeyStore keys;
	private String version;
	private int versions;

	/**
	 * Number of reads that have been served, for checking that a node reads the
	 * store as rarely as it says it does.
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
	public Read read(String knownVersion) throws IOException {
		reads++;

		if(unreachable) {
			throw new IOException("Storage is unreachable");
		}

		if(keys == null) {
			return new Read.Absent();
		}

		if(knownVersion != null && knownVersion.equals(version)) {
			return new Read.Unchanged();
		}

		return new Read.Loaded(keys, version);
	}

	@Override
	public String write(KeyStore keys, String expectedVersion) throws IOException {
		if(unreachable) {
			throw new IOException("Storage is unreachable");
		}

		if(refuseNextWrite) {
			refuseNextWrite = false;
			return null;
		}

		if(expectedVersion == null ? version != null : !expectedVersion.equals(version)) {
			return null;
		}

		this.keys = keys;
		this.version = "v" + (++versions);

		return version;
	}

	/**
	 * Replace what is stored without going through a conditional write, for
	 * setting a test up or standing in for another node having written.
	 */
	public void set(KeyStore keys) {
		this.keys = keys;
		this.version = "v" + (++versions);
	}
}
