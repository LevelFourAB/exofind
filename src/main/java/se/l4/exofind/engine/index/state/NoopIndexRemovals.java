package se.l4.exofind.engine.index.state;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Predicate;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ListIterable;

import se.l4.exofind.engine.index.IndexName;

/**
 * IndexRemovals for a node storing locally, where a deleted index has no
 * remote copy to remove - its directory goes with the delete, and there is
 * nothing to mark, sweep or restore.
 */
public class NoopIndexRemovals implements IndexRemovals {
	@Override
	public void mark(IndexName target) {
	}

	@Override
	public boolean unmark(IndexName target) {
		return false;
	}

	@Override
	public Optional<Instant> markedAt(IndexName target) {
		return Optional.empty();
	}

	@Override
	public ListIterable<Mark> listMarks(Predicate<IndexName> wanted) {
		return Lists.immutable.empty();
	}

	@Override
	public boolean remove(IndexName target) {
		return true;
	}

	@Override
	public void prepareForIndex(String index) {
	}

	@Override
	public void prepareForGeneration(IndexName generation) {
	}
}
