package se.l4.exofind.engine.auth;

import java.io.IOException;

/**
 * KeyStorage for a node that named an object storage it cannot keep keys in.
 *
 * <p>Keys are shared between nodes through the storage, so a node that cannot
 * reach it has nowhere to put them and no way to learn about keys another node
 * made. It answers as though no key has ever been created, which leaves its
 * root key as the only way in - narrower than a working store rather than
 * wider.
 */
public class NoKeyStorage implements KeyStorage {
	@Override
	public boolean isAvailable() {
		return false;
	}

	@Override
	public Read read(String knownVersion) {
		return new Read.Absent();
	}

	@Override
	public String write(KeyStore keys, String expectedVersion) throws IOException {
		throw new IOException("This node has nowhere to keep keys");
	}
}
