package se.l4.exofind.engine.index.state;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class IndexUsageFileTest {
	private static final Duration HALF_LIFE = Duration.ofDays(7);

	@TempDir
	Path directory;

	@Test
	public void testMissingRecordReadsAsDefault() {
		assertThat(IndexUsageFile.read(directory), is(IndexUsage.getDefaultInstance()));
	}

	@Test
	public void testWrittenRecordIsReadBack() throws IOException {
		var now = Instant.now();
		var usage = IndexUsageFile.recordOpen(IndexUsage.getDefaultInstance(), now, HALF_LIFE);

		IndexUsageFile.write(directory, usage);

		assertThat(IndexUsageFile.read(directory), is(usage));
	}

	@Test
	public void testRecordWithoutCountReadsAsZeroOpens() {
		assertThat(
			IndexUsageFile.decayedOpens(IndexUsage.getDefaultInstance(), Instant.now(), HALF_LIFE),
			is(0.0)
		);
	}

	@Test
	public void testFirstOpenCountsAsOne() {
		var now = Instant.now();
		var usage = IndexUsageFile.recordOpen(IndexUsage.getDefaultInstance(), now, HALF_LIFE);

		assertThat(usage.getLastUsedMs(), is(now.toEpochMilli()));
		assertThat(IndexUsageFile.decayedOpens(usage, now, HALF_LIFE), is(1.0));
	}

	@Test
	public void testCountHalvesEveryHalfLife() {
		var then = Instant.now();
		var usage = IndexUsage.newBuilder()
			.setDecayedOpens(4)
			.setDecayedAtMs(then.toEpochMilli())
			.build();

		assertThat(
			IndexUsageFile.decayedOpens(usage, then.plus(HALF_LIFE), HALF_LIFE),
			closeTo(2.0, 0.0001)
		);
		assertThat(
			IndexUsageFile.decayedOpens(usage, then.plus(HALF_LIFE.multipliedBy(2)), HALF_LIFE),
			closeTo(1.0, 0.0001)
		);
	}

	@Test
	public void testOpenAfterHalfLifeDecaysBeforeCounting() {
		var then = Instant.now();
		var usage = IndexUsage.newBuilder()
			.setDecayedOpens(4)
			.setDecayedAtMs(then.toEpochMilli())
			.build();

		var opened = IndexUsageFile.recordOpen(usage, then.plus(HALF_LIFE), HALF_LIFE);

		assertThat(opened.getDecayedOpens(), closeTo(3.0, 0.0001));
	}

	/**
	 * A record stamped ahead of the clock - written before the clock stepped
	 * back - reads as current instead of growing the count.
	 */
	@Test
	public void testRecordFromTheFutureDoesNotGrow() {
		var now = Instant.now();
		var usage = IndexUsage.newBuilder()
			.setDecayedOpens(4)
			.setDecayedAtMs(now.plus(Duration.ofHours(1)).toEpochMilli())
			.build();

		assertThat(IndexUsageFile.decayedOpens(usage, now, HALF_LIFE), is(4.0));
	}

	@Test
	public void testUseMovesLastUsedAndKeepsTheCount() {
		var then = Instant.now();
		var usage = IndexUsageFile.recordOpen(IndexUsage.getDefaultInstance(), then, HALF_LIFE);

		var later = then.plus(Duration.ofHours(3));
		var used = IndexUsageFile.recordUse(usage, later);

		assertThat(used.getLastUsedMs(), is(later.toEpochMilli()));
		assertThat(used.getDecayedOpens(), is(usage.getDecayedOpens()));
		assertThat(used.getDecayedAtMs(), is(usage.getDecayedAtMs()));
	}
}
