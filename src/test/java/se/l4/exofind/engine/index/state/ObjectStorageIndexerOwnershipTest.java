package se.l4.exofind.engine.index.state;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.apache.commons.lang3.RandomStringUtils;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.set.ImmutableSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.storage.ObjectStorage;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

public class ObjectStorageIndexerOwnershipTest {
	/**
	 * Claim duration used by the tests. Rounds run at a third of this, which
	 * is what keeps the tests fast.
	 */
	private static final Duration LEASE = Duration.ofMillis(1500);

	S3Client s3Client;
	ObjectStorage storage;
	String storagePrefix;

	/**
	 * The names the registry is pretended to hold, handed to every candidate
	 * as its view of what exists.
	 */
	AtomicReference<ImmutableSet<String>> names;

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

		names = new AtomicReference<>(Sets.immutable.empty());
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
		return newOwnership(node, null);
	}

	private ObjectStorageIndexerOwnership newOwnership(String node, String address) {
		return new ObjectStorageIndexerOwnership(
			storage,
			Optional.of(node),
			Optional.ofNullable(address),
			LEASE,
			names::get
		);
	}

	/**
	 * Start competing, recording which indexes are currently held.
	 */
	private Set<String> start(ObjectStorageIndexerOwnership ownership) {
		Set<String> owned = ConcurrentHashMap.newKeySet();
		ownership.start((index, owner) -> {
			if(owner) {
				owned.add(index);
			} else {
				owned.remove(index);
			}
		});
		running.add(ownership);
		return owned;
	}

	private static IndexerLeadership.Claim claim(
		String index,
		String node,
		long expiresAt,
		String address
	) {
		var builder = IndexerLeadership.Claim.newBuilder()
			.setIndex(index)
			.setNode(node)
			.setExpiresAt(expiresAt);

		if(address != null) {
			builder.setAddress(address);
		}

		return builder.build();
	}

	private static IndexerLeadership.Candidate candidate(
		String node,
		long expiresAt,
		String address
	) {
		var builder = IndexerLeadership.Candidate.newBuilder()
			.setNode(node)
			.setExpiresAt(expiresAt);

		if(address != null) {
			builder.setAddress(address);
		}

		return builder.build();
	}

	/**
	 * Write a table directly, standing in for nodes this test does not run.
	 */
	private void writeTable(
		List<IndexerLeadership.Claim> claims,
		List<IndexerLeadership.Candidate> candidates
	) {
		var table = IndexerLeadership.newBuilder()
			.addAllClaims(claims)
			.addAllCandidates(candidates)
			.build();

		s3Client.putObject(
			PutObjectRequest.builder()
				.bucket(TestObjectStorage.BUCKET)
				.key(storagePrefix + "/indexer-leadership.ef.bin")
				.build(),
			RequestBody.fromBytes(table.toByteArray())
		);
	}

	/**
	 * The table as the storage holds it right now, empty when nothing has
	 * written one.
	 */
	private IndexerLeadership readTable() throws Exception {
		try(
			var response = s3Client.getObject(
				GetObjectRequest.builder()
					.bucket(TestObjectStorage.BUCKET)
					.key(storagePrefix + "/indexer-leadership.ef.bin")
					.build()
			)
		) {
			return IndexerLeadership.parseFrom(response.readAllBytes());
		} catch(S3Exception e) {
			if(e.statusCode() == 404) {
				return IndexerLeadership.getDefaultInstance();
			}

			throw e;
		}
	}

	private void await(BooleanSupplier condition, String description) throws InterruptedException {
		var deadline = System.currentTimeMillis() + 15_000;
		while(System.currentTimeMillis() < deadline) {
			if(condition.getAsBoolean()) {
				return;
			}

			Thread.sleep(25);
		}

		fail("Timed out waiting for " + description);
	}

	@Test
	void testSoleCandidateTakesEveryIndex() throws Exception {
		names.set(Sets.immutable.of("books", "games", "music"));

		var owned = start(newOwnership("a"));

		await(
			() -> owned.containsAll(Set.of("books", "games", "music")),
			"the only candidate to take every index"
		);
	}

	/**
	 * Two candidates end up with a fair share each - however the indexes were
	 * distributed when the second one arrived.
	 */
	@Test
	void testTwoCandidatesDivideTheIndexes() throws Exception {
		names.set(Sets.immutable.of("a", "b", "c", "d"));

		var first = start(newOwnership("node-1"));
		var second = start(newOwnership("node-2"));

		await(
			() -> first.size() == 2 && second.size() == 2,
			"the indexes to be divided evenly"
		);

		var all = new ArrayList<String>();
		all.addAll(first);
		all.addAll(second);
		assertThat(all, containsInAnyOrder("a", "b", "c", "d"));
	}

	/**
	 * Claims whose holder died stop being renewed and lapse, after which a
	 * candidate takes them over.
	 */
	@Test
	void testLapsedClaimsAreTakenOver() throws Exception {
		names.set(Sets.immutable.of("books", "games"));

		var lapsed = System.currentTimeMillis() - 10_000;
		writeTable(
			List.of(
				claim("books", "dead", lapsed, null),
				claim("games", "dead", lapsed, null)
			),
			List.of()
		);

		var owned = start(newOwnership("a"));

		await(
			() -> owned.containsAll(Set.of("books", "games")),
			"the lapsed claims to be taken over"
		);
	}

	/**
	 * An index whose holder is alive is never taken from it, however unevenly
	 * that leaves things.
	 */
	@Test
	void testLiveClaimsAreNotTaken() throws Exception {
		names.set(Sets.immutable.of("held", "free"));

		var alive = System.currentTimeMillis() + 60_000;
		writeTable(
			List.of(claim("held", "other", alive, null)),
			List.of(candidate("other", alive, null))
		);

		var owned = start(newOwnership("a"));
		await(() -> owned.contains("free"), "the free index to be taken");

		Thread.sleep(LEASE.toMillis());

		assertThat(owned.contains("held"), is(false));

		var table = readTable();
		var heldBy = table.getClaimsList().stream()
			.filter(c -> c.getIndex().equals("held"))
			.findFirst()
			.orElseThrow()
			.getNode();
		assertThat(heldBy, is("other"));
	}

	/**
	 * A candidate that shuts down hands its indexes over instead of making
	 * its successors wait the claims out.
	 */
	@Test
	void testStoppedCandidateHandsItsIndexesOver() throws Exception {
		names.set(Sets.immutable.of("books", "games"));

		var holder = newOwnership("a");
		var first = start(holder);
		await(
			() -> first.containsAll(Set.of("books", "games")),
			"the first candidate to take every index"
		);

		var second = start(newOwnership("b"));

		holder.stop();

		await(
			() -> second.containsAll(Set.of("books", "games")),
			"the second candidate to take everything over"
		);
	}

	/**
	 * A candidate that joins a running deployment is handed indexes one round
	 * at a time, until both hold a fair share.
	 */
	@Test
	void testNewCandidateIsHandedIndexes() throws Exception {
		names.set(Sets.immutable.of("a", "b", "c", "d"));

		var first = start(newOwnership("node-1"));
		await(() -> first.size() == 4, "the first candidate to take every index");

		var second = start(newOwnership("node-2"));

		await(
			() -> first.size() == 2 && second.size() == 2,
			"the indexes to be divided evenly"
		);
	}

	/**
	 * A write that found no holder appoints one there and then, without
	 * waiting for a round - including for an index the registry does not
	 * hold yet, which is every index while it is being created.
	 */
	@Test
	void testTryClaimTakesAFreeIndex() throws Exception {
		names.set(Sets.immutable.of("books"));

		var ownership = newOwnership("a");
		var owned = start(ownership);
		await(() -> owned.contains("books"), "the candidate to take the known index");

		assertThat(ownership.tryClaim("brand-new"), is(true));
		assertThat(owned.contains("brand-new"), is(true));

		var table = readTable();
		var heldBy = table.getClaimsList().stream()
			.filter(c -> c.getIndex().equals("brand-new"))
			.findFirst()
			.orElseThrow()
			.getNode();
		assertThat(heldBy, is("a"));
	}

	@Test
	void testTryClaimRefusesAnIndexHeldAlive() throws Exception {
		names.set(Sets.immutable.of("held"));

		var alive = System.currentTimeMillis() + 60_000;
		writeTable(
			List.of(claim("held", "other", alive, null)),
			List.of(candidate("other", alive, null))
		);

		var ownership = newOwnership("a");
		start(ownership);

		assertThat(ownership.tryClaim("held"), is(false));
	}

	/**
	 * A node that never started competing takes nothing, however free the
	 * index is - claiming is only for candidates.
	 */
	@Test
	void testTryClaimWithoutCompetingTakesNothing() {
		assertThat(newOwnership("a").tryClaim("books"), is(false));
	}

	/**
	 * The address in a live claim is where writes to that index are sent.
	 */
	@Test
	void testIndexerAddressNamesTheHolder() throws Exception {
		var alive = System.currentTimeMillis() + 60_000;
		writeTable(
			List.of(
				claim("books", "a", alive, "http://a:8080"),
				claim("games", "b", alive, "http://b:8080")
			),
			List.of()
		);

		var ownership = newOwnership("c");
		assertThat(ownership.indexerAddress("books"), is(Optional.of("http://a:8080")));
		assertThat(ownership.indexerAddress("games"), is(Optional.of("http://b:8080")));
	}

	/**
	 * An index nothing holds is answered with a live candidate, which takes
	 * the index by serving the write sent to it.
	 */
	@Test
	void testFreeIndexIsAnsweredWithACandidate() throws Exception {
		var alive = System.currentTimeMillis() + 60_000;
		writeTable(
			List.of(),
			List.of(candidate("a", alive, "http://a:8080"))
		);

		assertThat(
			newOwnership("b").indexerAddress("books"),
			is(Optional.of("http://a:8080"))
		);
	}

	/**
	 * A lapsed claim names nobody - its holder may be gone - so the index is
	 * answered like one nothing holds.
	 */
	@Test
	void testLapsedClaimFallsBackToTheCandidates() throws Exception {
		var now = System.currentTimeMillis();
		writeTable(
			List.of(claim("books", "dead", now - 10_000, "http://dead:8080")),
			List.of(candidate("a", now + 60_000, "http://a:8080"))
		);

		assertThat(
			newOwnership("b").indexerAddress("books"),
			is(Optional.of("http://a:8080"))
		);
	}

	/**
	 * A node never names itself - the question is only asked after this node
	 * could not serve the write.
	 */
	@Test
	void testOwnClaimNamesNoAddress() throws Exception {
		var alive = System.currentTimeMillis() + 60_000;
		writeTable(
			List.of(claim("books", "a", alive, "http://a:8080")),
			List.of(candidate("a", alive, "http://a:8080"))
		);

		assertThat(newOwnership("a").indexerAddress("books"), is(Optional.empty()));
	}

	/**
	 * A claim on an index the registry no longer holds is dropped by its own
	 * holder, so a deleted index does not stay claimed forever.
	 */
	@Test
	void testDeletedIndexIsDropped() throws Exception {
		names.set(Sets.immutable.of("books", "games"));

		var owned = start(newOwnership("a"));
		await(
			() -> owned.containsAll(Set.of("books", "games")),
			"the candidate to take every index"
		);

		names.set(Sets.immutable.of("books"));

		await(
			() -> !owned.contains("games"),
			"the claim on the deleted index to be dropped"
		);
		assertThat(owned.contains("books"), is(true));
	}

	/**
	 * Deleting the last index empties the registry, and the claims on what
	 * was deleted are dropped the same way as when other indexes remain.
	 */
	@Test
	void testEmptiedRegistryDropsEveryClaim() throws Exception {
		names.set(Sets.immutable.of("books", "games"));

		var owned = start(newOwnership("a"));
		await(
			() -> owned.containsAll(Set.of("books", "games")),
			"the candidate to take every index"
		);

		names.set(Sets.immutable.empty());

		await(owned::isEmpty, "the claims on the deleted indexes to be dropped");
	}

	/**
	 * A registry that has never been read answers {@code null} and is not an
	 * empty deployment: claims this node already holds are kept, and nothing
	 * new is claimed, until what exists is known.
	 */
	@Test
	void testUnreadRegistryKeepsHeldClaims() throws Exception {
		names.set(null);

		var alive = System.currentTimeMillis() + 60_000;
		writeTable(
			List.of(claim("books", "a", alive, null)),
			List.of(candidate("a", alive, null))
		);

		var owned = start(newOwnership("a"));

		await(() -> owned.contains("books"), "the candidate to keep its own claim");

		Thread.sleep(LEASE.toMillis());
		assertThat(owned, is(Set.of("books")));
	}

	/**
	 * A candidate that stops while holding nothing still takes its candidate
	 * entry out of the table, so free-index writes stop being sent its way at
	 * once instead of when the entry lapses.
	 */
	@Test
	void testStoppedCandidateWithoutIndexesLeavesTheTable() throws Exception {
		var candidate = newOwnership("a");
		start(candidate);

		await(() -> {
			try {
				return readTable().getCandidatesList().stream()
					.anyMatch(c -> c.getNode().equals("a"));
			} catch(Exception e) {
				return false;
			}
		}, "the candidate to appear in the table");

		candidate.stop();

		var remaining = readTable().getCandidatesList().stream()
			.filter(c -> c.getNode().equals("a"))
			.toList();
		assertThat(remaining, is(List.of()));
	}
}
