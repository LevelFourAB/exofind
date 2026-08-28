package se.l4.exofind.engine.index.settings;

import java.io.IOException;

/**
 * SearchSettingsStorage for a node that named an object storage it cannot keep
 * settings in.
 *
 * <p>Settings are shared between nodes through the storage, so a node that
 * cannot reach it has nowhere to put them and no way to learn about settings
 * another node stored. It answers as though no settings exist, which makes
 * every search fall back to the definitions - the ranking the indexes shipped
 * with rather than half of somebody's tuning.
 */
public class NoSearchSettingsStorage implements SearchSettingsStorage {
	@Override
	public boolean isAvailable() {
		return false;
	}

	@Override
	public Read read(String index, String knownVersion) {
		return new Read.Absent();
	}

	@Override
	public String write(String index, SearchSettingsStore settings, String expectedVersion)
		throws IOException
	{
		throw new IOException("This node has nowhere to keep search settings");
	}

	@Override
	public void delete(String index) throws IOException {
		throw new IOException("This node has nowhere to keep search settings");
	}
}
