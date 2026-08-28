package se.l4.exofind.engine.index.state;

import java.io.IOException;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

/**
 * NoopSync is a {@link StateSync} implementation that does nothing. Used for
 * running the engine in a local-only mode.
 */
public class NoopSync implements StateSync {
	@Override
	public boolean pull() throws IOException {
		return false;
	}

	@Override
	public void push(Set<String> files) throws IOException {
	}

	@Override
	public OptionalLong syncedVersion() {
		return OptionalLong.empty();
	}

	/**
	 * Nothing is written to describe the index, so the segments on disk are the
	 * only record of what created them.
	 */
	@Override
	public OptionalInt luceneCreatedMajor() {
		return OptionalInt.empty();
	}
}
