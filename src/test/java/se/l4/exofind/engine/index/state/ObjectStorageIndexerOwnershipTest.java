package se.l4.exofind.engine.index.state;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.storage.ObjectStorage;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public class ObjectStorageIndexerOwnershipTest {
	/**
	 * Lease used by the tests. Ticks run at a third of this, which is what
	 * keeps the tests fast.
	 */
	private static final Duration LEASE = Duration.ofMillis(1500);

	S3Client s3Client;
	ObjectStorage storage;
	String storagePrefix;

	List<ObjectStorageIndexerOwnership> running = new ArrayList<>();

	@BeforeEach
	void setup() throws Exception {
		s3Client = TestObjectStorage.client();

		storagePrefix = "test" + RandomStringUtils.insecure().nextAlphabetic(10);
		storage = new ObjectStorage(
			TestObjectStorage.url(),
			TestObjectStorage.ACCESS_KEY,
			TestObjectStorage.SECRET_KEY,
			Optional.empty(),
			TestObjectStorage.BUCKET,
			Optional.of(storagePrefix),
			false
		);
	}

	@AfterEach
	void cleanup() throws Exception {
		for(var ownership : running) {
			ownership.stop();
		}

		var objects = s3Client.listObjectsV2Paginator(
			b -> b.bucket(TestObjectStorage.BUCKET).prefix(storagePrefix)
		).contents().stream().toList();

		for(var object : objects) {
			s3Client.deleteObject(
				DeleteObjectRequest.builder()
					.bucket(TestObjectStorage.BUCKET)
					.key(object.key())
					.build()
			);
		}
	}

	private ObjectStorageIndexerOwnership newOwnership(String node) {
		return new ObjectStorageIndexerOwnership(
			storage,
			Optional.of(node),
			Optional.empty(),
			LEASE
		);
	}

	/**
	 * Start competing for the role, recording whether it is currently held.
	 */
	private AtomicBoolean start(ObjectStorageIndexerOwnership ownership) {
		var owner = new AtomicBoolean();
		ownership.start(owner::set);
		running.add(ownership);
		return owner;
	}

	/**
	 * Write a lease directly, standing in for a node this test does not run.
	 */
	private void writeLease(String node, long expiresAt) throws Exception {
		writeLease(node, expiresAt, null);
	}

	private void writeLease(String node, long expiresAt, String address) throws Exception {
		var builder = IndexerLease.newBuilder()
			.setNode(node)
			.setExpiresAt(expiresAt);

		if(address != null) {
			builder.setAddress(address);
		}

		var bytes = builder.build()
			.toByteArray();

		s3Client.putObject(
			PutObjectRequest.builder()
				.bucket(TestObjectStorage.BUCKET)
				.key(storagePrefix + "/indexer-lease.ef.bin")
				.build(),
			RequestBody.fromBytes(bytes)
		);
	}

	private void await(BooleanSupplier condition, String description) throws InterruptedException {
		var deadline = System.currentTimeMillis() + 10_000;
		while(System.currentTimeMillis() < deadline) {
			if(condition.getAsBoolean()) {
				return;
			}

			Thread.sleep(25);
		}

		fail("Timed out waiting for " + description);
	}

	@Test
	void testCandidateAcquiresFreeRole() throws Exception {
		var owner = start(newOwnership("a"));

		await(owner::get, "the node to acquire the role");
	}

	/**
	 * While one node holds a live lease, another candidate keeps waiting
	 * rather than taking the role from it.
	 */
	@Test
	void testSecondCandidateWaitsWhileRoleIsHeld() throws Exception {
		var a = start(newOwnership("a"));
		await(a::get, "the first node to acquire the role");

		var b = start(newOwnership("b"));
		Thread.sleep(LEASE.toMillis());

		assertThat(b.get(), is(false));
		assertThat(a.get(), is(true));
	}

	/**
	 * A node that shuts down hands the role over instead of making its
	 * successor wait the lease out.
	 */
	@Test
	void testRoleMovesWhenHolderStops() throws Exception {
		var holder = newOwnership("a");
		var a = start(holder);
		await(a::get, "the first node to acquire the role");

		var b = start(newOwnership("b"));

		holder.stop();
		await(b::get, "the second node to take the role over");
	}

	/**
	 * A lease whose holder died stops being renewed and lapses, after which
	 * a candidate takes it over.
	 */
	@Test
	void testExpiredLeaseIsTakenOver() throws Exception {
		writeLease("dead", System.currentTimeMillis() - 10_000);

		var owner = start(newOwnership("a"));
		await(owner::get, "the node to take over the lapsed lease");
	}

	/**
	 * A holder whose lease is replaced under it notices on renewal and gives
	 * the role up, rather than continue as a second writer.
	 */
	@Test
	void testHolderGivesUpWhenLeaseIsTakenOver() throws Exception {
		var owner = start(newOwnership("a"));
		await(owner::get, "the node to acquire the role");

		writeLease("thief", System.currentTimeMillis() + 60_000);

		await(() -> !owner.get(), "the node to give the role up");
	}

	/**
	 * The address in a live lease is what other nodes send their callers to.
	 */
	@Test
	void testIndexerAddressIsReadFromLease() throws Exception {
		writeLease("a", System.currentTimeMillis() + 60_000, "http://a:8080");

		assertThat(
			newOwnership("b").indexerAddress(),
			is(Optional.of("http://a:8080"))
		);
	}

	/**
	 * A lapsed lease names nobody - its holder may be gone, and sending
	 * callers to it helps no one.
	 */
	@Test
	void testExpiredLeaseNamesNoIndexer() throws Exception {
		writeLease("a", System.currentTimeMillis() - 10_000, "http://a:8080");

		assertThat(newOwnership("b").indexerAddress(), is(Optional.empty()));
	}

	/**
	 * A node never points a caller back at itself - the caller only asks
	 * after this node could not serve the write.
	 */
	@Test
	void testOwnLeaseNamesNoIndexer() throws Exception {
		writeLease("a", System.currentTimeMillis() + 60_000, "http://a:8080");

		assertThat(newOwnership("a").indexerAddress(), is(Optional.empty()));
	}

	/**
	 * A holder that offered no address cannot be pointed at.
	 */
	@Test
	void testLeaseWithoutAddressNamesNoIndexer() throws Exception {
		writeLease("a", System.currentTimeMillis() + 60_000);

		assertThat(newOwnership("b").indexerAddress(), is(Optional.empty()));
	}
}
