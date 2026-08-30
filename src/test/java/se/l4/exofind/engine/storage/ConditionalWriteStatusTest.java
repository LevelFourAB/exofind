package se.l4.exofind.engine.storage;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * What {@link ObjectStorage#isConditionalWriteLost(S3Exception)} makes of the
 * answers a storage gives a conditional write, which is what every writer that
 * coordinates through one object depends on to tell a lost race from a failure.
 */
public class ConditionalWriteStatusTest {
	/**
	 * The answer from a storage that evaluated the condition against what it
	 * holds and found it did not hold.
	 */
	@Test
	void testPreconditionFailedIsALostWrite() {
		assertThat(ObjectStorage.isConditionalWriteLost(withStatus(412)), is(true));
	}

	/**
	 * What AWS S3 answers when a write to the same key at the same moment kept
	 * it from deciding the condition. Nothing was written, so the caller has
	 * the same work to do as for a refusal - reading a conflict as a failure is
	 * what turns a lost race into a node that cannot renew its claims.
	 */
	@Test
	void testConditionalRequestConflictIsALostWrite() {
		assertThat(ObjectStorage.isConditionalWriteLost(withStatus(409)), is(true));
	}

	/**
	 * Everything else is the storage failing rather than deciding, and must
	 * keep reaching the caller as an error.
	 */
	@Test
	void testOtherFailuresAreNotLostWrites() {
		assertThat(ObjectStorage.isConditionalWriteLost(withStatus(403)), is(false));
		assertThat(ObjectStorage.isConditionalWriteLost(withStatus(404)), is(false));
		assertThat(ObjectStorage.isConditionalWriteLost(withStatus(500)), is(false));
		assertThat(ObjectStorage.isConditionalWriteLost(withStatus(503)), is(false));
	}

	private static S3Exception withStatus(int status) {
		return (S3Exception) S3Exception.builder()
			.statusCode(status)
			.message("Status " + status)
			.build();
	}
}
