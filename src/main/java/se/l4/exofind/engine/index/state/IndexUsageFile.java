package se.l4.exofind.engine.index.state;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;

/**
 * Reads and writes the {@link IndexUsage} record kept next to the local copy
 * of an index, and holds the arithmetic on the decayed open count it stores.
 *
 * <p>The record belongs to the node alone and is never synchronized; a pull
 * leaves it in place. Methods that read tolerate a missing or unreadable
 * record and answer with {@link IndexUsage#getDefaultInstance()}.
 */
public final class IndexUsageFile {
	/**
	 * Name the record is stored under inside the index directory.
	 */
	public static final String NAME = "usage.ef.bin";

	/**
	 * Name the record is written under before being moved into place, so an
	 * interrupted write can never leave a truncated record behind.
	 */
	private static final String TEMP_NAME = NAME + ".tmp";

	private IndexUsageFile() {
	}

	/**
	 * Read the usage record of an index directory.
	 *
	 * @return
	 *   the stored record, or the default instance when the directory holds
	 *   none or it can not be read
	 */
	public static IndexUsage read(Path directory) {
		var path = directory.resolve(NAME);
		if(!Files.exists(path)) {
			return IndexUsage.getDefaultInstance();
		}

		try(var in = Files.newInputStream(path)) {
			return IndexUsage.parseFrom(in);
		} catch(IOException e) {
			return IndexUsage.getDefaultInstance();
		}
	}

	/**
	 * Write the usage record of an index directory, replacing the one it
	 * holds.
	 *
	 * @throws IOException
	 *   if the record could not be written, which includes the directory no
	 *   longer existing
	 */
	public static void write(Path directory, IndexUsage usage) throws IOException {
		var tempFile = directory.resolve(TEMP_NAME);
		try(var out = Files.newOutputStream(tempFile)) {
			usage.writeTo(out);
		}

		try {
			Files.move(
				tempFile,
				directory.resolve(NAME),
				StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE
			);
		} catch(AtomicMoveNotSupportedException e) {
			Files.move(tempFile, directory.resolve(NAME), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/**
	 * The record after the index is opened at {@code now}: the open count is
	 * decayed up to {@code now} and incremented, and {@code now} becomes the
	 * time of last use.
	 */
	public static IndexUsage recordOpen(IndexUsage usage, Instant now, Duration halfLife) {
		return IndexUsage.newBuilder()
			.setLastUsedMs(now.toEpochMilli())
			.setDecayedOpens(decayedOpens(usage, now, halfLife) + 1)
			.setDecayedAtMs(now.toEpochMilli())
			.build();
	}

	/**
	 * The record after the index was in use at {@code now}, without counting
	 * an open. Recorded when an index closes, so that the record covers the
	 * whole span it was open.
	 */
	public static IndexUsage recordUse(IndexUsage usage, Instant now) {
		return usage.toBuilder()
			.setLastUsedMs(now.toEpochMilli())
			.build();
	}

	/**
	 * The open count as of {@code now}: halved for every half-life that has
	 * passed since the record was last brought up to date. A record holding
	 * no count reads as zero, and one stamped in the future reads as current
	 * rather than grown.
	 */
	public static double decayedOpens(IndexUsage usage, Instant now, Duration halfLife) {
		if(!usage.hasDecayedOpens()) {
			return 0;
		}

		var elapsed = Math.max(0, now.toEpochMilli() - usage.getDecayedAtMs());
		return usage.getDecayedOpens() * Math.pow(0.5, (double) elapsed / halfLife.toMillis());
	}
}
