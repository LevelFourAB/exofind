package se.l4.exofind.engine.reindex;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.state.TestObjectStorage;
import se.l4.exofind.engine.storage.ObjectStorage;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Tests for how the bucket tracks which reindexes are still running. A node
 * looking for jobs to resume has to read the running jobs and not the whole
 * history, while a finished record stays readable for the status endpoints.
 */
public class ObjectStorageReindexJobStorageTest {
	ObjectStorage storage;
	ObjectStorageReindexJobStorage jobStorage;

	@BeforeEach
	void setup() throws IOException {
		storage = new ObjectStorage(
			TestObjectStorage.url(),
			TestObjectStorage.credentialsProvider(),
			Optional.empty(),
			TestObjectStorage.BUCKET,
			Optional.of("test" + RandomStringUtils.insecure().nextAlphabetic(10)),
			false
		);

		jobStorage = new ObjectStorageReindexJobStorage(storage);
	}

	@Test
	public void aRunningJobIsListedAmongTheUnfinished() throws Exception {
		var version = jobStorage.write("catalogue", jobOf("catalogue", ReindexPhase.PENDING), null);
		assertThat(version, is(notNullValue()));

		var unfinished = jobStorage.listUnfinished();
		assertThat(unfinished.size(), is(1));
		assertThat(unfinished.getFirst().record().getIndex(), is("catalogue"));
	}

	@Test
	public void aFinishedJobStaysReadableButIsNoLongerListedAmongTheUnfinished()
		throws Exception {
		var version = jobStorage.write("catalogue", jobOf("catalogue", ReindexPhase.PENDING), null);
		jobStorage.write("catalogue", jobOf("catalogue", ReindexPhase.DONE), version);

		assertThat(jobStorage.listUnfinished().size(), is(0));

		var read = jobStorage.read("catalogue").orElseThrow();
		assertThat(read.record().getPhase(), is(ReindexPhaseStore.REINDEX_PHASE_DONE));

		var all = jobStorage.list();
		assertThat(all.size(), is(1));
		assertThat(all.getFirst().record().getIndex(), is("catalogue"));
	}

	/**
	 * The listing returns the record as the checkpoints left it, not a copy
	 * taken when the job started.
	 */
	@Test
	public void anUnfinishedJobIsListedAtTheProgressItsRecordDescribes() throws Exception {
		var version = jobStorage.write("catalogue", jobOf("catalogue", ReindexPhase.PENDING), null);
		jobStorage.write("catalogue", copying("catalogue", 4200), version);

		var listed = jobStorage.listUnfinished().getFirst().record();
		assertThat(listed.getPhase(), is(ReindexPhaseStore.REINDEX_PHASE_COPYING));
		assertThat(listed.getDocumentsCopied(), is(4200L));
	}

	/**
	 * A node can die between writing the record that ends a job and deleting
	 * its marker.
	 */
	@Test
	public void aMarkerLeftByAFinishedJobIsRemoved() throws Exception {
		jobStorage.write("catalogue", jobOf("catalogue", ReindexPhase.DONE), null);
		writeMarker("catalogue");

		assertThat(jobStorage.listUnfinished().size(), is(0));
		assertThat(markers(), is(empty()));
	}

	/**
	 * The marker is written before the record, so an accept that failed
	 * halfway leaves a marker with no record behind it.
	 */
	@Test
	public void aMarkerWithNoRecordIsRemoved() throws Exception {
		writeMarker("catalogue");

		assertThat(jobStorage.listUnfinished().size(), is(0));
		assertThat(markers(), is(empty()));
	}

	@Test
	public void deletingARecordRemovesItsMarker() throws Exception {
		jobStorage.write("catalogue", jobOf("catalogue", ReindexPhase.PENDING), null);
		jobStorage.delete("catalogue");

		assertThat(jobStorage.read("catalogue").isPresent(), is(false));
		assertThat(markers(), is(empty()));
	}

	private static ReindexJobStore jobOf(String index, ReindexPhase phase) {
		var now = Instant.now();

		return new ReindexJob(
			index,
			"2",
			"1",
			phase,
			null,
			0,
			10,
			0,
			null,
			false,
			now,
			now
		).toStore();
	}

	private static ReindexJobStore copying(String index, long copied) {
		var now = Instant.now();

		return new ReindexJob(
			index,
			"2",
			"1",
			ReindexPhase.COPYING,
			"k" + copied,
			copied,
			10000,
			0,
			null,
			false,
			now,
			now
		).toStore();
	}

	/**
	 * Write a marker without a record, the way a node that died mid-accept
	 * leaves one behind.
	 */
	private void writeMarker(String index) {
		storage.client().putObject(
			PutObjectRequest.builder()
				.bucket(TestObjectStorage.BUCKET)
				.key(activePrefix() + index)
				.build(),
			RequestBody.empty()
		);
	}

	/**
	 * The indexes the bucket holds a marker for, read past the storage so that
	 * a marker it should have removed is still seen.
	 */
	private List<String> markers() {
		var prefix = activePrefix();

		return storage.client().listObjectsV2(
				ListObjectsV2Request.builder()
					.bucket(TestObjectStorage.BUCKET)
					.prefix(prefix)
					.build()
			)
			.contents()
			.stream()
			.map(object -> object.key().substring(prefix.length()))
			.toList();
	}

	private String activePrefix() {
		return storage.rootObject("jobs/reindex/active") + "/";
	}
}
