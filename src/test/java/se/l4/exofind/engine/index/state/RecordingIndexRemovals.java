package se.l4.exofind.engine.index.state;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ListIterable;

import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.IndexStorageHeldException;

/**
 * IndexRemovals in memory, recording what was asked of it, for tests of the
 * callers rather than of the storage.
 */
public class RecordingIndexRemovals implements IndexRemovals {
	/**
	 * The marks that stand, by what they stand over, in the order they were
	 * written. Tests set entries directly to stand in for a delete that
	 * happened elsewhere or earlier.
	 */
	public final Map<IndexName, Instant> marks = new LinkedHashMap<>();

	/**
	 * What {@link #remove(IndexName)} was asked to remove, in order.
	 */
	public final List<IndexName> removed = new ArrayList<>();

	/**
	 * Indexes {@link #prepareForIndex(String)} was asked to make room for.
	 */
	public final List<String> preparedIndexes = new ArrayList<>();

	/**
	 * Generations {@link #prepareForGeneration(IndexName)} was asked to make
	 * room for.
	 */
	public final List<IndexName> preparedGenerations = new ArrayList<>();

	/**
	 * Generations whose prefix holds a manifest nothing marked, which
	 * {@link #prepareForGeneration(IndexName)} refuses.
	 */
	public final Set<IndexName> held = new HashSet<>();

	/**
	 * The time a mark written now gets.
	 */
	public Instant now = Instant.now();

	/**
	 * When set, writing a mark fails as though the storage were unreachable.
	 */
	public boolean failMark;

	/**
	 * When set, a removal reports that its mark went away midway.
	 */
	public boolean stopRemoval;

	@Override
	public void mark(IndexName target) throws IOException {
		if(failMark) {
			throw new IOException("Marks can not be written");
		}

		marks.put(target, now);
	}

	@Override
	public boolean unmark(IndexName target) {
		return marks.remove(target) != null;
	}

	@Override
	public Optional<Instant> markedAt(IndexName target) {
		return Optional.ofNullable(marks.get(target));
	}

	@Override
	public ListIterable<Mark> listMarks(Predicate<IndexName> wanted) {
		var result = Lists.mutable.<Mark>empty();
		for(var entry : marks.entrySet()) {
			if(wanted.test(entry.getKey())) {
				result.add(new Mark(entry.getKey(), entry.getValue()));
			}
		}

		return result;
	}

	@Override
	public boolean remove(IndexName target) {
		removed.add(target);

		if(stopRemoval) {
			return false;
		}

		marks.remove(target);
		return true;
	}

	@Override
	public void prepareForIndex(String index) {
		preparedIndexes.add(index);
		marks.remove(IndexName.of(index));
	}

	@Override
	public void prepareForGeneration(IndexName generation) {
		preparedGenerations.add(generation);

		if(marks.remove(generation) != null) {
			return;
		}

		if(held.contains(generation)) {
			throw new IndexStorageHeldException(generation);
		}
	}
}
