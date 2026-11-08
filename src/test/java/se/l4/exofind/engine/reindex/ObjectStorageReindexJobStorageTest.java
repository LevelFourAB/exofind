package se.l4.exofind.engine.reindex;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import se.l4.exofind.engine.index.state.TestObjectStorage;
import se.l4.exofind.engine.storage.ObjectStorage;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

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
			Optional.of(TestObjectStorage.url()),
			TestObjectStorage.auth(),
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

	/**
	 * A checkpoint the storage applied before a proxy in front of it answered
	 * with a server error was written. The status does not say that the write
	 * was refused, so the record is read back; reporting a failure would fail
	 * a job whose record moved on.
	 */
	@Test
	public void aRecordWriteThatLandedBeforeAServerErrorCountsAsWritten() throws Exception {
		// Given
		var version = jobStorage.write("catalogue", jobOf("catalogue", ReindexPhase.PENDING), null);
		var checkpoint = copying("catalogue", 1000);

		// When
		var written = answeringWithServerErrors().write("catalogue", checkpoint, version);

		// Then
		assertThat(written, is(notNullValue()));
		var stored = jobStorage.read("catalogue").orElseThrow();
		assertThat(stored.record(), is(checkpoint));
		assertThat(stored.version(), is(written));
	}

	/**
	 * Record storage over a client that lets every put through and then
	 * answers it with a gateway timeout.
	 */
	private ObjectStorageReindexJobStorage answeringWithServerErrors() {
		var client = Mockito.spy(storage.client());

		Mockito.doAnswer(invocation -> {
			invocation.callRealMethod();
			throw S3Exception.builder().statusCode(504).message("simulated gateway timeout").build();
		}).when(client).putObject(
			ArgumentMatchers.any(PutObjectRequest.class),
			ArgumentMatchers.any(RequestBody.class)
		);

		var wrapped = Mockito.spy(storage);
		Mockito.doReturn(client).when(wrapped).client();

		return new ObjectStorageReindexJobStorage(wrapped);
	}

	/**
	 * Another node lists the running jobs between the marker and the record
	 * of a job being started, and reads the record of the job before it. The
	 * job being started must still be listed afterwards, or a sweep never
	 * resumes it.
	 */
	@Test
	public void aJobStartedWhileAnotherNodeListsIsListedAfterwards() throws Exception {
		// Given
		var finished = jobStorage.write("catalogue", jobOf("catalogue", ReindexPhase.DONE), null);

		var otherNode = new ObjectStorageReindexJobStorage(storage);
		var listedBetween = new AtomicBoolean();
		var starting = listingBeforeTheRecord(() -> {
			if(!listedBetween.getAndSet(true)) {
				otherNode.listUnfinished();
			}
		});

		// When
		starting.write("catalogue", jobOf("catalogue", ReindexPhase.PENDING), finished);

		// Then
		assertThat(listedBetween.get(), is(true));
		assertThat(jobStorage.listUnfinished().size(), is(1));
	}

	/**
	 * Another node reads the record of the job before the one being started,
	 * and its delete of the marker arrives only after the start is complete.
	 * The job being started must still be listed afterwards.
	 */
	@Test
	public void aJobStartedBeforeAnotherNodeDeletesItsMarkerIsListedAfterwards()
		throws Exception {
		// Given
		var finished = jobStorage.write("catalogue", jobOf("catalogue", ReindexPhase.DONE), null);
		writeMarker("catalogue");

		var started = new AtomicBoolean();
		var otherNode = startingBeforeEachMarkerDelete(() -> {
			if(!started.getAndSet(true)) {
				jobStorage.write("catalogue", jobOf("catalogue", ReindexPhase.PENDING), finished);
			}
		});

		// When
		otherNode.listUnfinished();

		// Then
		assertThat(started.get(), is(true));
		assertThat(jobStorage.listUnfinished().size(), is(1));
	}

	/**
	 * Record storage over a client that runs a step just before each delete
	 * of a marker.
	 */
	private ObjectStorageReindexJobStorage startingBeforeEachMarkerDelete(StorageStep step) {
		var client = Mockito.spy(storage.client());

		Mockito.doAnswer(invocation -> {
			DeleteObjectRequest request = invocation.getArgument(0);
			if(request.key().contains("jobs/reindex/active/")) {
				step.run();
			}

			return invocation.callRealMethod();
		}).when(client).deleteObject(ArgumentMatchers.any(DeleteObjectRequest.class));

		var wrapped = Mockito.spy(storage);
		Mockito.doReturn(client).when(wrapped).client();

		return new ObjectStorageReindexJobStorage(wrapped);
	}

	/**
	 * Record storage over a client that runs a step just before each put of
	 * a record, after the marker of the job was written.
	 */
	private ObjectStorageReindexJobStorage listingBeforeTheRecord(StorageStep step) {
		var client = Mockito.spy(storage.client());

		Mockito.doAnswer(invocation -> {
			PutObjectRequest request = invocation.getArgument(0);
			if(request.key().contains("jobs/reindex/records/")) {
				step.run();
			}

			return invocation.callRealMethod();
		}).when(client).putObject(
			ArgumentMatchers.any(PutObjectRequest.class),
			ArgumentMatchers.any(RequestBody.class)
		);

		var wrapped = Mockito.spy(storage);
		Mockito.doReturn(client).when(wrapped).client();

		return new ObjectStorageReindexJobStorage(wrapped);
	}

	@FunctionalInterface
	private interface StorageStep {
		void run() throws IOException;
	}

	private static ReindexJobStore jobOf(String index, ReindexPhase phase) {
		var now = Instant.now();

		return new ReindexJob(
			"job-1",
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
			"tester",
			"node-a",
			now,
			now,
			null, null, null
		).toStore();
	}

	private static ReindexJobStore copying(String index, long copied) {
		var now = Instant.now();

		return new ReindexJob(
			"job-1",
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
			"tester",
			"node-a",
			now,
			now,
			null, null, null
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
