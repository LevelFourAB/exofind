package se.l4.exofind.engine.index.state;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.logging.Log;
import se.l4.exofind.engine.storage.DurableFiles;

/**
 * Keeps the {@link PendingRemovals} record of a node: the removals of
 * indexes and generations whose registry write may have landed, but whose
 * outcome the node could not find out.
 *
 * <p>The record belongs to the node alone and is never synchronized. It is
 * kept on disk so that a node that stops before it can read the registry
 * again still leaves the removal mark of a removal that landed.
 */
public final class PendingRemovalsFile {
	private static final Log logger = Log.of(PendingRemovalsFile.class);

	/**
	 * Name the record is stored under inside the storage directory.
	 */
	public static final String NAME = "pending-removals.ef.bin";

	/**
	 * A removal whose outcome is not known.
	 *
	 * @param target
	 *   the index, or the generation as {@code index@generation}, that was
	 *   removed
	 * @param createdAt
	 *   when it was created, as the registry stated it, or {@code null} when
	 *   the registry stated no time
	 */
	public record Removal(IndexName target, Instant createdAt) {
	}

	private final Path file;
	private final List<Removal> removals;

	private PendingRemovalsFile(Path file, List<Removal> removals) {
		this.file = file;
		this.removals = removals;
	}

	/**
	 * Read the record in a storage directory. A directory without a record
	 * holds no pending removals. A record that cannot be read is logged and
	 * taken as empty: the removals it held then leave no mark, and the
	 * registry audit reports their objects as held but not registered.
	 *
	 * @param directory
	 *   the storage directory of the node
	 */
	public static PendingRemovalsFile open(Path directory) {
		var file = directory.resolve(NAME);
		var removals = new ArrayList<Removal>();
		if(!Files.exists(file)) {
			return new PendingRemovalsFile(file, removals);
		}

		try(var in = Files.newInputStream(file)) {
			for(var stored : PendingRemovals.parseFrom(in).getRemovalsList()) {
				var target = stored.hasGeneration()
					? IndexName.of(stored.getIndex(), stored.getGeneration())
					: IndexName.of(stored.getIndex());
				var createdAt = stored.hasCreatedAt()
					? Instant.ofEpochMilli(stored.getCreatedAt())
					: null;

				removals.add(new Removal(target, createdAt));
			}
		} catch(IOException | RuntimeException e) {
			logger.atError()
				.addKeyValue("file", file.toString())
				.setCause(e)
				.log(
					"Could not read the removals whose outcome is not known, their objects"
						+ " may stay without a removal mark; " + e.getMessage()
				);
		}

		return new PendingRemovalsFile(file, removals);
	}

	/**
	 * Get the removals whose outcome is not known.
	 */
	public synchronized List<Removal> list() {
		return List.copyOf(removals);
	}

	/**
	 * Get whether there are no removals whose outcome is not known.
	 */
	public synchronized boolean isEmpty() {
		return removals.isEmpty();
	}

	/**
	 * Record a removal whose outcome is not known. A removal of the same
	 * target that is already recorded is replaced.
	 *
	 * @throws IOException
	 *   if the record could not be written
	 */
	public synchronized void add(Removal removal) throws IOException {
		removals.removeIf(existing -> existing.target().equals(removal.target()));
		removals.add(removal);
		write();
	}

	/**
	 * Forget a removal once its outcome is known.
	 *
	 * @throws IOException
	 *   if the record could not be written
	 */
	public synchronized void remove(Removal removal) throws IOException {
		if(removals.remove(removal)) {
			write();
		}
	}

	private void write() throws IOException {
		if(removals.isEmpty()) {
			Files.deleteIfExists(file);
			return;
		}

		var builder = PendingRemovals.newBuilder();
		for(var removal : removals) {
			var stored = PendingRemoval.newBuilder()
				.setIndex(removal.target().index());

			if(removal.target().isPinned()) {
				stored.setGeneration(removal.target().generation());
			}

			if(removal.createdAt() != null) {
				stored.setCreatedAt(removal.createdAt().toEpochMilli());
			}

			builder.addRemovals(stored);
		}

		DurableFiles.replace(file, builder.build().toByteArray());
	}
}
