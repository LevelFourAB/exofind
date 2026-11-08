package se.l4.exofind.engine.reindex;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.google.protobuf.UnknownFieldSet;

/**
 * How a job is written to and read back from its record, including a record
 * an earlier version wrote without the fields this version keeps.
 */
public class ReindexJobTest {
	/**
	 * A field number that reindex.proto does not use yet.
	 */
	private static final int FUTURE_FIELD = 99;

	@Test
	public void aJobSurvivesTheRoundTrip() {
		var started = Instant.parse("2026-08-28T10:15:30Z");
		var finished = Instant.parse("2026-08-28T10:41:17Z");
		var job = new ReindexJob(
			"6f1c2a9d8b3e4c05",
			"products",
			"2",
			"1",
			ReindexPhase.DONE,
			"prod_99",
			2400000,
			2400000,
			0,
			null,
			true,
			"3f9a1c7e2b8d4650",
			"node-a-7f21",
			started,
			finished,
			finished,
			Instant.parse("2026-08-01T08:00:00Z"),
			Instant.parse("2026-08-28T10:15:00Z")
		);

		var read = ReindexJob.fromStore(job.toStore()).orElseThrow();
		assertThat(read, is(job));
	}

	/**
	 * A record written before ids, principals, nodes, end times and the
	 * creation times of the generations were kept reads back with those left
	 * empty rather than failing to read.
	 */
	@Test
	public void aRecordOfAnEarlierVersionReadsBackWithoutTheNewerFields() {
		var now = Instant.now();
		var record = ReindexJobStore.newBuilder()
			.setIndex("products")
			.setTarget("2")
			.setSource("1")
			.setPhase(ReindexPhaseStore.REINDEX_PHASE_DONE)
			.setDocumentsCopied(10)
			.setSourceDocCount(10)
			.setBacklog(0)
			.setManualPromote(false)
			.setStartedAt(now.toEpochMilli())
			.setUpdatedAt(now.toEpochMilli())
			.build();

		var read = ReindexJob.fromStore(record).orElseThrow();
		assertThat(read.id(), is(nullValue()));
		assertThat(read.startedBy(), is(nullValue()));
		assertThat(read.node(), is(nullValue()));
		assertThat(read.finishedAt(), is(nullValue()));
		assertThat(read.sourceCreatedAt(), is(nullValue()));
		assertThat(read.targetCreatedAt(), is(nullValue()));
		assertThat(read.phase(), is(ReindexPhase.DONE));
	}

	/**
	 * The end time is set by the first write of a finished phase and left
	 * where it is by every write after it, while the node and the update time
	 * follow every write.
	 */
	@Test
	public void theEndTimeIsSetOnceByTheWriteThatFinishesTheJob() {
		var started = Instant.parse("2026-08-28T10:15:30Z");
		var job = new ReindexJob(
			"job-1", "products", "2", "1",
			ReindexPhase.REPLAYING,
			null, 10, 10, 0, null, false, "tester", "node-a",
			started, started, null, null, null
		);

		var replaying = job.withBacklog(3).written("node-a", started.plusSeconds(10));
		assertThat(replaying.finishedAt(), is(nullValue()));
		assertThat(replaying.updatedAt(), is(started.plusSeconds(10)));

		var cancelled = replaying
			.withPhase(ReindexPhase.CANCELLED)
			.written("node-b", started.plusSeconds(20));
		assertThat(cancelled.finishedAt(), is(started.plusSeconds(20)));
		assertThat(cancelled.node(), is("node-b"));

		var rewritten = cancelled.written("node-b", started.plusSeconds(30));
		assertThat(rewritten.finishedAt(), is(started.plusSeconds(20)));
		assertThat(rewritten.updatedAt(), is(started.plusSeconds(30)));
	}

	/**
	 * A record a newer version wrote can hold fields this version does not
	 * know. A checkpoint written over it must keep them, or a newer node that
	 * picks the job up again reads it without part of what was asked for.
	 */
	@Test
	public void aCheckpointKeepsTheFieldsANewerVersionWrote() {
		// Given
		var started = Instant.parse("2026-08-28T10:15:30Z");
		var job = new ReindexJob(
			"job-1", "products", "2", "1",
			ReindexPhase.COPYING,
			"prod_10", 10, 100, 0, null, false, "tester", "node-a",
			started, started, null, null, null
		);
		var newer = job.toStore().toBuilder()
			.setUnknownFields(
				UnknownFieldSet.newBuilder()
					.addField(
						FUTURE_FIELD,
						UnknownFieldSet.Field.newBuilder().addVarint(1).build()
					)
					.build()
			)
			.build();

		// When
		var rewritten = ReindexJob.fromStore(newer).orElseThrow()
			.withBacklog(1)
			.written("node-b", started.plusSeconds(10))
			.toStore();

		// Then
		assertThat(rewritten.getUnknownFields().hasField(FUTURE_FIELD), is(true));
	}
}
