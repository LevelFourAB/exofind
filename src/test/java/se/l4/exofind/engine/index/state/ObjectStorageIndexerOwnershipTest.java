package se.l4.exofind.engine.index.state;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import org.apache.commons.lang3.RandomStringUtils;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.set.ImmutableSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import com.google.protobuf.UnknownFieldSet;

import se.l4.exofind.engine.storage.ObjectStorage;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/*
 * These tests wait out lease rounds instead of using the CPU, and each one
 * gets a storage prefix of its own, so the methods run in parallel.
 */
@Execution(ExecutionMode.CONCURRENT)
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

	/**
	 * The write load per index name, one map per node the way the production
	 * figures are - a node knows nothing about the writes of another. A name
	 * not in a node's map is idle there.
	 */
	ConcurrentHashMap<String, ConcurrentHashMap<String, Double>> loads;

	/**
	 * The flush a handover waits on before the claim is released, asked per
	 * index name. Done at once unless a test slows it down.
	 */
	Function<String, CompletableFuture<Void>> flush;

	List<ObjectStorageIndexerOwnership> running = new ArrayList<>();

	@BeforeEach
	void setup() throws Exception {
		s3Client = TestObjectStorage.client();

		storagePrefix = "test" + RandomStringUtils.insecure().nextAlphabetic(10);
		storage = new ObjectStorage(
			Optional.of(TestObjectStorage.url()),
			TestObjectStorage.auth(),
			Optional.empty(),
			TestObjectStorage.BUCKET,
			Optional.of(storagePrefix),
			false
		);

		names = new AtomicReference<>(Sets.immutable.empty());
		loads = new ConcurrentHashMap<>();
		flush = name -> CompletableFuture.completedFuture(null);
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
		var nodeLoads = loads(node);
		return new ObjectStorageIndexerOwnership(
			storage,
			node,
			Optional.ofNullable(address),
			LEASE,
			names::get,
			name -> nodeLoads.getOrDefault(name, 0d),
			(name, count) -> nodeLoads.merge(name, (double) count, Double::sum),
			name -> flush.apply(name)
		);
	}

	/**
	 * A candidate that reads and writes the table through an interceptor,
	 * which can fail or stall the requests of this one node.
	 */
	private ObjectStorageIndexerOwnership newFaultyOwnership(
		String node,
		ExecutionInterceptor faults
	) throws IOException {
		var faulty = new ObjectStorage(
			Optional.of(TestObjectStorage.url()),
			TestObjectStorage.auth(),
			Optional.empty(),
			TestObjectStorage.BUCKET,
			Optional.of(storagePrefix),
			false,
			faults
		);

		return new ObjectStorageIndexerOwnership(
			faulty,
			node,
			Optional.empty(),
			LEASE,
			names::get,
			name -> 0d,
			(name, count) -> {},
			name -> flush.apply(name)
		);
	}

	/**
	 * Fails reads of the leadership table while {@link #fail} is on, and
	 * stalls them while {@link #stallMillis} is above zero. This stands in
	 * for a storage that one node cannot reach, or that answers slowly.
	 */
	static final class TableReadFaults implements ExecutionInterceptor {
		final AtomicBoolean fail = new AtomicBoolean();
		final AtomicLong stallMillis = new AtomicLong();

		@Override
		public void beforeExecution(Context.BeforeExecution context, ExecutionAttributes attributes) {
			if(
				!(context.request() instanceof GetObjectRequest get)
					|| !get.key().endsWith("indexer-leadership.ef.bin")
			) {
				return;
			}

			var stall = stallMillis.get();
			if(stall > 0) {
				try {
					Thread.sleep(stall);
				} catch(InterruptedException e) {
					Thread.currentThread().interrupt();
				}

				throw SdkClientException.create("simulated read timeout of the leadership table");
			}

			if(fail.get()) {
				throw SdkClientException.create("simulated unreachable leadership table");
			}
		}
	}

	/**
	 * The write load figures of one node.
	 */
	private ConcurrentHashMap<String, Double> loads(String node) {
		return loads.computeIfAbsent(node, n -> new ConcurrentHashMap<>());
	}

	/**
	 * Start competing, recording which indexes are currently held.
	 */
	private Set<String> start(ObjectStorageIndexerOwnership ownership) {
		return start(ownership, ConcurrentHashMap.newKeySet());
	}

	/**
	 * Start competing, recording which indexes are currently held and, in
	 * {@code revoked}, which of them were taken away rather than handed over -
	 * the difference between what may still be pushed and what may not.
	 */
	private Set<String> start(ObjectStorageIndexerOwnership ownership, Set<String> revoked) {
		Set<String> owned = ConcurrentHashMap.newKeySet();
		ownership.start(new IndexerOwnership.Listener() {
			@Override
			public void onOwnershipChanged(String index, boolean owner) {
				if(owner) {
					owned.add(index);
				} else {
					owned.remove(index);
				}
			}

			@Override
			public void onOwnershipRevoked(String index) {
				owned.remove(index);
				revoked.add(index);
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
	 * Shutting down is a handover too: the claims stay in the table until the
	 * flush of what was held here has finished, so a successor that takes an
	 * index the moment the claim goes can never pull a manifest the shutdown
	 * flush had not written yet.
	 */
	@Test
	void testStopReleasesTheClaimsOnlyAfterTheFlush() throws Exception {
		names.set(Sets.immutable.of("books"));

		var pending = new CompletableFuture<Void>();
		flush = name -> pending;

		var ownership = newOwnership("a");
		var owned = start(ownership);
		await(() -> owned.contains("books"), "the candidate to take the index");

		var stopping = new Thread(ownership::stop);
		stopping.start();

		await(owned::isEmpty, "the index to stop being written here");

		// The flush is still running, so the claim has to stay in the table
		Thread.sleep(300);
		var claim = readTable().getClaimsList().stream()
			.filter(c -> c.getIndex().equals("books"))
			.findFirst()
			.orElseThrow();
		assertThat(claim.getNode(), is("a"));

		pending.complete(null);
		stopping.join(15_000);
		assertThat(stopping.isAlive(), is(false));

		var table = readTable();
		assertThat(table.getClaimsList(), is(List.of()));
		assertThat(table.getCandidatesList(), is(List.of()));
	}

	private long expiryOf(String index) throws Exception {
		return readTable().getClaimsList().stream()
			.filter(c -> c.getIndex().equals(index))
			.findFirst()
			.orElseThrow()
			.getExpiresAt();
	}

	/**
	 * The rounds end when a stop begins, and the claims would lapse one lease
	 * after the last round. The stop renews them while it waits for the
	 * flush, so no other node takes an index for at least one lease from the
	 * start of the stop.
	 */
	@Test
	void testStopKeepsTheClaimsLiveForTheLeaseWhileTheFlushRuns() throws Exception {
		names.set(Sets.immutable.of("books"));

		var pending = new CompletableFuture<Void>();
		flush = name -> pending;

		var holder = newOwnership("a");
		var owned = start(holder);
		await(() -> owned.contains("books"), "the first candidate to take the index");

		var successor = newOwnership("b");
		start(successor);

		// Stop 250 ms after a round renewed the claim, before the next round starts
		long stopAt;
		while(true) {
			stopAt = expiryOf("books") - LEASE.toMillis() + 250;
			if(System.currentTimeMillis() < stopAt - 20) {
				break;
			}

			Thread.sleep(5);
		}
		Thread.sleep(Math.max(0, stopAt - System.currentTimeMillis()));

		var stopStarted = System.currentTimeMillis();
		var stopping = new Thread(holder::stop);
		stopping.start();
		running.remove(holder);

		var takenAt = new AtomicLong();
		await(
			() -> {
				if(successor.tryClaim("books")) {
					takenAt.set(System.currentTimeMillis());
					return true;
				}

				return false;
			},
			"the second candidate to take the index"
		);

		assertThat(pending.isDone(), is(false));
		assertThat(
			"milliseconds from the start of the stop until the index was taken",
			takenAt.get() - stopStarted,
			greaterThanOrEqualTo(LEASE.toMillis())
		);

		pending.complete(null);
		stopping.join(15_000);
		assertThat(stopping.isAlive(), is(false));
	}

	/**
	 * The renewals end with the wait. A flush that never ends leaves the
	 * claims to lapse, and the stop returns.
	 */
	@Test
	void testStopRenewsTheClaimsForOneLeaseAtMost() throws Exception {
		names.set(Sets.immutable.of("books"));
		flush = name -> new CompletableFuture<>();

		var holder = newOwnership("a");
		var owned = start(holder);
		await(() -> owned.contains("books"), "the first candidate to take the index");

		var successorOwned = start(newOwnership("b"));

		var stopping = new Thread(holder::stop);
		stopping.start();
		running.remove(holder);

		await(
			() -> successorOwned.contains("books"),
			"the second candidate to take the index after the claim lapsed"
		);

		stopping.join(15_000);
		assertThat(stopping.isAlive(), is(false));
	}

	/**
	 * Wait until a round renews the claim on the index.
	 */
	private void awaitRenewal(String index) throws Exception {
		var before = expiryOf(index);
		await(
			() -> {
				try {
					return expiryOf(index) != before;
				} catch(Exception e) {
					return false;
				}
			},
			"the claim on " + index + " to be renewed"
		);
	}

	/**
	 * Other nodes may take a claim from the moment it lapses. A node that
	 * cannot renew its claims must give them up before that moment, or it
	 * serves writes for an index another node already writes.
	 */
	@Test
	void testUnrenewableClaimsAreGivenUpBeforeTheyLapse() throws Exception {
		names.set(Sets.immutable.of("books"));

		var faults = new TableReadFaults();
		var holder = newFaultyOwnership("a", faults);
		Set<String> revoked = ConcurrentHashMap.newKeySet();
		var owned = start(holder, revoked);
		await(() -> owned.contains("books"), "the candidate to take the index");

		awaitRenewal("books");
		faults.fail.set(true);

		// Every later write of the holder reads first, so this expiry is the last one
		Thread.sleep(200);
		var lapsesAt = expiryOf("books");

		await(() -> revoked.contains("books"), "the candidate to give the index up");
		assertThat(
			"milliseconds the index was kept after its claim lapsed",
			System.currentTimeMillis() - lapsesAt,
			lessThan(0L)
		);
	}

	/**
	 * A round that never returns, for example on a request that the client
	 * timeout cannot end, must not keep the claims either.
	 */
	@Test
	void testAStalledRoundDoesNotKeepALapsedIndex() throws Exception {
		names.set(Sets.immutable.of("books"));

		var faults = new TableReadFaults();
		var holder = newFaultyOwnership("a", faults);
		Set<String> revoked = ConcurrentHashMap.newKeySet();
		var owned = start(holder, revoked);
		await(() -> owned.contains("books"), "the first candidate to take the index");

		var successor = newOwnership("b");
		start(successor);

		awaitRenewal("books");
		faults.stallMillis.set(LEASE.toMillis() * 5);

		await(() -> successor.tryClaim("books"), "the second candidate to take the index");

		assertThat(revoked.contains("books"), is(true));
		assertThat(owned.contains("books"), is(false));
	}

	/**
	 * A claim that ends up naming another node was taken away rather than
	 * handed over, which is what happens to a candidate whose claims lapsed
	 * while it was paused. The successor may already be writing, so the loss
	 * is reported as a revocation - nothing the index still holds here may be
	 * pushed under a claim that is no longer this node's.
	 */
	@Test
	void testAClaimTakenByAnotherNodeIsRevoked() throws Exception {
		names.set(Sets.immutable.of("books"));

		Set<String> revoked = ConcurrentHashMap.newKeySet();
		var owned = start(newOwnership("a"), revoked);
		await(() -> owned.contains("books"), "the candidate to take the index");

		/*
		 * The table as it looks once the claims of a paused node lapsed and a
		 * successor took the index over.
		 */
		var alive = System.currentTimeMillis() + 60_000;
		writeTable(
			List.of(claim("books", "successor", alive, null)),
			List.of(candidate("successor", alive, null))
		);

		await(() -> revoked.contains("books"), "the lost claim to be revoked");
		assertThat(owned.contains("books"), is(false));
	}

	/**
	 * A handover this node chose is not a revocation: the claim stays in the
	 * table while the flush runs, so what the index still holds here is pushed
	 * before the successor takes over.
	 */
	@Test
	void testAShedIndexIsHandedOverRatherThanRevoked() throws Exception {
		names.set(Sets.immutable.of("a", "b"));

		Set<String> revoked = ConcurrentHashMap.newKeySet();
		var first = start(newOwnership("node-1"), revoked);
		await(() -> first.size() == 2, "the first candidate to take every index");

		var second = start(newOwnership("node-2"));

		await(
			() -> first.size() == 1 && second.size() == 1,
			"the indexes to be divided evenly"
		);

		assertThat(revoked.isEmpty(), is(true));
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
	 * A node over its fair share hands over the indexes it has not been
	 * writing, so the busy ones stay where their writer is warm.
	 */
	@Test
	void testShedPrefersTheWriteIdleIndexes() throws Exception {
		names.set(Sets.immutable.of("a", "b", "c", "d"));

		var first = start(newOwnership("node-1"));
		await(() -> first.size() == 4, "the first candidate to take every index");

		loads("node-1").put("b", 100d);
		loads("node-1").put("c", 100d);

		var second = start(newOwnership("node-2"));

		await(
			() -> first.size() == 2 && second.size() == 2,
			"the indexes to be divided evenly"
		);

		assertThat(first, containsInAnyOrder("b", "c"));
		assertThat(second, containsInAnyOrder("a", "d"));
	}

	/**
	 * With no writes seen every index is equally idle, and shedding falls
	 * back to name order - the highest name goes first - so which indexes
	 * move stays stable.
	 */
	@Test
	void testShedWithoutLoadFallsBackToNameOrder() throws Exception {
		names.set(Sets.immutable.of("a", "b", "c", "d"));

		var first = start(newOwnership("node-1"));
		await(() -> first.size() == 4, "the first candidate to take every index");

		var second = start(newOwnership("node-2"));

		await(
			() -> first.size() == 2 && second.size() == 2,
			"the indexes to be divided evenly"
		);

		assertThat(first, containsInAnyOrder("a", "b"));
		assertThat(second, containsInAnyOrder("c", "d"));
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

	/**
	 * A create claims the index before it writes the registry, so the claim
	 * has to survive the rounds that run while the write is still in flight -
	 * a round that took it for a claim on a deleted index would leave the
	 * create with nothing to write to.
	 */
	@Test
	void testAClaimTakenAheadOfTheRegistryIsKept() throws Exception {
		names.set(Sets.immutable.of("books"));

		var ownership = newOwnership("a");
		var owned = start(ownership);
		await(() -> owned.contains("books"), "the candidate to take the known index");

		assertThat(ownership.tryClaim("brand-new"), is(true));

		// Long enough for several rounds to have had the chance to drop it
		Thread.sleep(LEASE.toMillis() * 2);

		assertThat(owned.contains("brand-new"), is(true));
		assertThat(
			readTable().getClaimsList().stream()
				.anyMatch(c -> c.getIndex().equals("brand-new") && c.getNode().equals("a")),
			is(true)
		);
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
	 * The overview reads the table the way it is: every live candidate and
	 * every live claim, whoever asks - the asking node does not have to
	 * compete, or even appear in the table.
	 */
	@Test
	void testOverviewListsCandidatesAndClaims() throws Exception {
		var alive = System.currentTimeMillis() + 60_000;
		writeTable(
			List.of(
				claim("games", "b", alive, null),
				claim("books", "a", alive, "http://a:8080")
			),
			List.of(
				candidate("b", alive, null),
				candidate("a", alive, "http://a:8080")
			)
		);

		var overview = newOwnership("c").overview();
		assertThat(overview.isPresent(), is(true));

		var candidates = overview.get().candidates();
		assertThat(candidates.size(), is(2));
		assertThat(candidates.get(0).node(), is("a"));
		assertThat(candidates.get(0).address(), is(Optional.of("http://a:8080")));
		assertThat(candidates.get(0).expiresAt().toEpochMilli(), is(alive));
		assertThat(candidates.get(1).node(), is("b"));
		assertThat(candidates.get(1).address(), is(Optional.empty()));

		var claims = overview.get().claims();
		assertThat(claims.size(), is(2));
		assertThat(claims.get(0).index(), is("books"));
		assertThat(claims.get(0).node(), is("a"));
		assertThat(claims.get(0).address(), is(Optional.of("http://a:8080")));
		assertThat(claims.get(1).index(), is("games"));
		assertThat(claims.get(1).node(), is("b"));
		assertThat(claims.get(1).address(), is(Optional.empty()));
	}

	/**
	 * A lapsed entry names nobody - its holder may be gone - so the overview
	 * answers it the way it answers an index nothing holds: by leaving it out.
	 */
	@Test
	void testOverviewLeavesLapsedEntriesOut() throws Exception {
		var now = System.currentTimeMillis();
		writeTable(
			List.of(
				claim("books", "a", now + 60_000, "http://a:8080"),
				claim("games", "dead", now - 10_000, null)
			),
			List.of(
				candidate("a", now + 60_000, "http://a:8080"),
				candidate("dead", now - 10_000, null)
			)
		);

		var overview = newOwnership("b").overview();
		assertThat(overview.isPresent(), is(true));

		assertThat(overview.get().candidates().size(), is(1));
		assertThat(overview.get().candidates().get(0).node(), is("a"));

		assertThat(overview.get().claims().size(), is(1));
		assertThat(overview.get().claims().get(0).index(), is("books"));
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

	/**
	 * With counts even but load not - one node holding every busy index -
	 * a busy index is offered up, taken by the idle node, and an idle one
	 * comes back the other way, so both nodes end up with one busy index
	 * each and the counts stay even.
	 */
	@Test
	void testBusyIndexesSpreadWhenCountsAreEven() throws Exception {
		names.set(Sets.immutable.of("a", "b", "c", "d"));

		var first = start(newOwnership("node-1"));
		var second = start(newOwnership("node-2"));

		await(
			() -> first.size() == 2 && second.size() == 2,
			"the indexes to be divided evenly"
		);

		var busy = List.copyOf(first);
		for(var name : busy) {
			loads("node-1").put(name, 5_000d);
		}

		await(
			() -> first.size() == 2 && second.size() == 2
				&& first.stream().filter(busy::contains).count() == 1
				&& second.stream().filter(busy::contains).count() == 1,
			"one busy index to move to the idle node"
		);
	}

	/**
	 * A single hot index is never offered - moving it would hand the
	 * imbalance over rather than narrow it - so it stays where its writer
	 * is warm however lopsided the load looks.
	 */
	@Test
	void testSingleHotIndexStaysPut() throws Exception {
		names.set(Sets.immutable.of("a", "b"));

		var first = start(newOwnership("node-1"));
		var second = start(newOwnership("node-2"));

		await(
			() -> first.size() == 1 && second.size() == 1,
			"the indexes to be divided evenly"
		);

		var hot = first.iterator().next();
		loads("node-1").put(hot, 100_000d);

		Thread.sleep(LEASE.toMillis() * 2);

		assertThat(first, containsInAnyOrder(hot));
	}

	/**
	 * A claim whose offer was answered is handed to the taker: rewritten to
	 * name it, address and session and all, keeping the load figure it was
	 * offered under, while the holder gives the index up.
	 */
	@Test
	void testAnsweredOfferIsHandedToTheTaker() throws Exception {
		names.set(Sets.immutable.of("books", "games"));

		var alive = System.currentTimeMillis() + 60_000;
		writeTable(
			List.of(
				claim("books", "a", alive, null).toBuilder()
					.setLoadBucket(7)
					.setOffered(true)
					.setTaker("b")
					.build(),
				claim("games", "a", alive, null)
			),
			List.of(candidate("b", alive, "http://b:8080").toBuilder().setSession("b-session").build())
		);

		var owned = start(newOwnership("a"));

		await(() -> {
			try {
				return readTable().getClaimsList().stream()
					.anyMatch(c -> c.getIndex().equals("books") && c.getNode().equals("b"));
			} catch(Exception e) {
				return false;
			}
		}, "the answered offer to be handed over");

		assertThat(owned.contains("games"), is(true));
		assertThat(owned.contains("books"), is(false));

		var handed = readTable().getClaimsList().stream()
			.filter(c -> c.getIndex().equals("books"))
			.findFirst()
			.orElseThrow();
		assertThat(handed.getNode(), is("b"));
		assertThat(handed.getAddress(), is("http://b:8080"));
		assertThat(handed.getSession(), is("b-session"));
		assertThat(handed.getLoadBucket(), is(7));
	}

	/**
	 * A handover releases the claim only after the flush of what the index
	 * holds here has finished, so the taker can never pull a manifest the
	 * flush had not written yet. Until then the claim stays with the holder,
	 * taker and all, and the index is not writable here.
	 */
	@Test
	void testAnsweredOfferWaitsForTheFlush() throws Exception {
		names.set(Sets.immutable.of("books", "games"));

		var pending = new CompletableFuture<Void>();
		flush = name -> "books".equals(name)
			? pending
			: CompletableFuture.completedFuture(null);

		var alive = System.currentTimeMillis() + 60_000;
		writeTable(
			List.of(
				claim("books", "a", alive, null).toBuilder()
					.setLoadBucket(7)
					.setOffered(true)
					.setTaker("b")
					.build(),
				claim("games", "a", alive, null)
			),
			List.of(candidate("b", alive, "http://b:8080"))
		);

		var ownership = newOwnership("a");
		Set<String> revoked = ConcurrentHashMap.newKeySet();
		var owned = start(ownership, revoked);

		await(() -> owned.contains("games"), "the other index to be kept");

		Thread.sleep(LEASE.toMillis() * 2);

		var held = readTable().getClaimsList().stream()
			.filter(c -> c.getIndex().equals("books"))
			.findFirst()
			.orElseThrow();
		assertThat(held.getNode(), is("a"));
		assertThat(held.getTaker(), is("b"));

		assertThat(owned.contains("books"), is(false));
		assertThat(ownership.tryClaim("books"), is(false));

		pending.complete(null);

		await(() -> {
			try {
				var books = readTable().getClaimsList().stream()
					.filter(c -> c.getIndex().equals("books"))
					.findFirst()
					.orElseThrow();
				return "b".equals(books.getNode());
			} catch(Exception e) {
				return false;
			}
		}, "the finished flush to hand the claim to the taker");

		// A finished handover is not a loss, whatever the rounds after it see
		Thread.sleep(LEASE.toMillis());
		assertThat(revoked.isEmpty(), is(true));
	}

	/**
	 * The claim of an index that is draining ends up naming another node
	 * before the flush is done, which is what a claim that lapsed while the
	 * node was paused looks like. The successor may already write, so the
	 * loss is reported as a revocation - the queued flush must push nothing.
	 */
	@Test
	void testADrainingIndexWhoseClaimIsTakenIsRevoked() throws Exception {
		names.set(Sets.immutable.of("books", "games"));

		Set<String> flushAsked = ConcurrentHashMap.newKeySet();
		var pending = new CompletableFuture<Void>();
		flush = name -> {
			flushAsked.add(name);
			return "books".equals(name)
				? pending
				: CompletableFuture.completedFuture(null);
		};

		var alive = System.currentTimeMillis() + 60_000;
		writeTable(
			List.of(
				claim("books", "a", alive, null).toBuilder()
					.setLoadBucket(7)
					.setOffered(true)
					.setTaker("b")
					.build(),
				claim("games", "a", alive, null)
			),
			List.of(candidate("b", alive, "http://b:8080"))
		);

		Set<String> revoked = ConcurrentHashMap.newKeySet();
		var owned = start(newOwnership("a"), revoked);

		await(
			() -> owned.contains("games") && flushAsked.contains("books"),
			"the index to start draining"
		);
		assertThat(revoked.isEmpty(), is(true));

		var later = System.currentTimeMillis() + 60_000;
		writeTable(
			List.of(
				claim("books", "successor", later, null),
				claim("games", "a", later, null)
			),
			List.of(candidate("b", later, null), candidate("successor", later, null))
		);

		await(() -> revoked.contains("books"), "the lost claim on the draining index to be revoked");
		assertThat(revoked.contains("games"), is(false));
	}

	/**
	 * A flush that could not push calls the handover off: the claim never
	 * reaches the taker, because it would send the taker to a manifest that
	 * is missing documents this node answered for. The index is written here
	 * again instead, and the loss is not a revocation - what it holds is
	 * still this node's to push.
	 */
	@Test
	void testAFailedFlushKeepsTheClaimAndTheIndex() throws Exception {
		names.set(Sets.immutable.of("books", "games"));

		var attempts = new AtomicInteger();
		flush = name -> {
			if(!"books".equals(name)) {
				return CompletableFuture.completedFuture(null);
			}

			attempts.incrementAndGet();
			return CompletableFuture.failedFuture(
				new IOException("The storage refused the push")
			);
		};

		var alive = System.currentTimeMillis() + 60_000;
		writeTable(
			List.of(
				claim("books", "a", alive, null).toBuilder()
					.setLoadBucket(7)
					.setOffered(true)
					.setTaker("b")
					.build(),
				claim("games", "a", alive, null)
			),
			List.of(candidate("b", alive, "http://b:8080"))
		);

		Set<String> revoked = ConcurrentHashMap.newKeySet();
		var owned = start(newOwnership("a"), revoked);

		await(
			() -> owned.contains("books"),
			"the called-off handover to hand the index back"
		);

		await(() -> attempts.get() >= 2, "the flush to be asked for again");

		Thread.sleep(LEASE.toMillis());

		var held = readTable().getClaimsList().stream()
			.filter(c -> c.getIndex().equals("books"))
			.findFirst()
			.orElseThrow();
		assertThat(held.getNode(), is("a"));
		assertThat(held.getTaker(), is("b"));

		assertThat(revoked.isEmpty(), is(true));
	}

	/**
	 * The handover a failed flush called off is started anew on a later
	 * round, so a storage hiccup delays a rebalance rather than stopping it.
	 */
	@Test
	void testAHandoverIsMadeAgainAfterAFailedFlush() throws Exception {
		names.set(Sets.immutable.of("books", "games"));

		var attempts = new AtomicInteger();
		flush = name -> {
			if("books".equals(name) && attempts.incrementAndGet() == 1) {
				return CompletableFuture.failedFuture(
					new IOException("The storage refused the push")
				);
			}

			return CompletableFuture.completedFuture(null);
		};

		var alive = System.currentTimeMillis() + 60_000;
		writeTable(
			List.of(
				claim("books", "a", alive, null).toBuilder()
					.setLoadBucket(7)
					.setOffered(true)
					.setTaker("b")
					.build(),
				claim("games", "a", alive, null)
			),
			List.of(candidate("b", alive, "http://b:8080"))
		);

		start(newOwnership("a"));

		await(() -> {
			try {
				var books = readTable().getClaimsList().stream()
					.filter(c -> c.getIndex().equals("books"))
					.findFirst()
					.orElseThrow();
				return "b".equals(books.getNode());
			} catch(Exception e) {
				return false;
			}
		}, "the flush that was made again to hand the claim to the taker");

		assertThat(attempts.get(), is(2));
	}

	/**
	 * A claim whose offer was answered but whose index was deleted from the
	 * registry is dropped rather than handed to the taker.
	 */
	@Test
	void testDeletedIndexIsNotHandedToTheTaker() throws Exception {
		names.set(Sets.immutable.of("games"));

		var alive = System.currentTimeMillis() + 60_000;
		writeTable(
			List.of(
				claim("books", "a", alive, null).toBuilder()
					.setLoadBucket(7)
					.setOffered(true)
					.setTaker("b")
					.build(),
				claim("games", "a", alive, null)
			),
			List.of(candidate("b", alive, "http://b:8080"))
		);

		var owned = start(newOwnership("a"));
		await(() -> owned.contains("games"), "the remaining index to be kept");

		await(() -> {
			try {
				return readTable().getClaimsList().stream()
					.noneMatch(c -> c.getIndex().equals("books"));
			} catch(Exception e) {
				return false;
			}
		}, "the claim on the deleted index to be dropped");
	}

	/**
	 * An under-loaded candidate answers an offer by naming itself as taker,
	 * and does not move the claim itself - only the holder does that.
	 */
	@Test
	void testIdleCandidateAnswersAnOffer() throws Exception {
		names.set(Sets.immutable.of("books", "films", "games", "music"));

		var alive = System.currentTimeMillis() + 60_000;
		writeTable(
			List.of(
				claim("books", "other", alive, null).toBuilder()
					.setLoadBucket(9)
					.setOffered(true)
					.build(),
				claim("games", "other", alive, null).toBuilder()
					.setLoadBucket(9)
					.build()
			),
			List.of(candidate("other", alive, null))
		);

		var owned = start(newOwnership("a"));
		await(() -> owned.size() == 2, "the free indexes to be claimed");

		await(() -> {
			try {
				var books = readTable().getClaimsList().stream()
					.filter(c -> c.getIndex().equals("books"))
					.findFirst()
					.orElseThrow();
				return "a".equals(books.getTaker()) && "other".equals(books.getNode());
			} catch(Exception e) {
				return false;
			}
		}, "the idle candidate to answer the offer");
	}

	/**
	 * The bucket a load is carried in the table as is its bit length, so it
	 * only moves when the load roughly doubles or halves.
	 */
	@Test
	void testLoadBuckets() {
		assertThat(ObjectStorageIndexerOwnership.loadBucket(0), is(0));
		assertThat(ObjectStorageIndexerOwnership.loadBucket(0.5), is(0));
		assertThat(ObjectStorageIndexerOwnership.loadBucket(1), is(1));
		assertThat(ObjectStorageIndexerOwnership.loadBucket(3), is(2));
		assertThat(ObjectStorageIndexerOwnership.loadBucket(4), is(3));
		assertThat(ObjectStorageIndexerOwnership.loadBucket(1024), is(11));
	}

	/**
	 * Two processes that compete under one node name - a misconfigured
	 * {@code EXOFIND_NODE_ID}, or a restarted node whose paused earlier
	 * process wakes up - never both believe they hold an index.
	 */
	@Test
	void testTwoProcessesUnderOneNameDoNotBothHoldAnIndex() throws Exception {
		names.set(Sets.immutable.of("books"));

		var first = start(newOwnership("a"));
		await(() -> first.contains("books"), "the first process to take the index");

		var second = start(newOwnership("a"));
		Thread.sleep(LEASE.toMillis() * 2);

		assertThat(first.contains("books") && second.contains("books"), is(false));
		assertThat(first.contains("books"), is(true));
	}

	/**
	 * A claim that names this node under another session belongs to an
	 * earlier process that did not release it. It may still write, so the
	 * new process waits for the claim to lapse and then takes it under its
	 * own session.
	 */
	@Test
	void testAClaimOfAnEarlierProcessIsWaitedOut() throws Exception {
		names.set(Sets.immutable.of("books"));

		var expiresAt = System.currentTimeMillis() + LEASE.toMillis();
		writeTable(
			List.of(claim("books", "a", expiresAt, null).toBuilder().setSession("earlier").build()),
			List.of()
		);

		var owned = start(newOwnership("a"));

		Thread.sleep(LEASE.toMillis() / 2);
		assertThat("the claim of the earlier process is still live", owned.contains("books"), is(false));

		await(() -> owned.contains("books"), "the lapsed claim to be taken");
		assertThat(System.currentTimeMillis(), greaterThanOrEqualTo(expiresAt));

		var claim = readTable().getClaimsList().stream()
			.filter(c -> c.getIndex().equals("books"))
			.findFirst()
			.orElseThrow();
		assertThat(claim.getNode(), is("a"));
		assertThat(claim.hasSession(), is(true));
		assertThat(claim.getSession().equals("earlier"), is(false));
	}

	/**
	 * A process that stops steps only its own entries out of the table. The
	 * claims of another process under the same node name stay, as that
	 * process still writes the indexes.
	 */
	@Test
	void testAStoppingProcessLeavesTheClaimsOfAnotherUnderTheSameName() throws Exception {
		names.set(Sets.immutable.of("books"));

		var first = start(newOwnership("a"));
		await(() -> first.contains("books"), "the first process to take the index");

		var second = newOwnership("a");
		start(second);
		Thread.sleep(LEASE.toMillis() / 2);

		second.stop();
		running.remove(second);

		var claim = readTable().getClaimsList().stream()
			.filter(c -> c.getIndex().equals("books"))
			.findFirst();
		assertThat("the claim of the first process is still in the table", claim.isPresent(), is(true));
		assertThat(claim.get().getNode(), is("a"));
		assertThat(first.contains("books"), is(true));
	}

	/**
	 * A claim moves to the taker only after the flush of the holder. That
	 * holds when the listener fails on the notice that the index is handed
	 * over: the handover is called off, as no flush was asked for, and the
	 * round after it starts the handover anew.
	 */
	@Test
	void testAListenerThatFailsOnAHandoverKeepsTheClaimUntilAFlush() throws Exception {
		names.set(Sets.immutable.of("books", "games"));

		Set<String> flushAsked = ConcurrentHashMap.newKeySet();
		flush = name -> {
			flushAsked.add(name);
			return CompletableFuture.completedFuture(null);
		};

		var thrown = new AtomicBoolean();
		Set<String> owned = ConcurrentHashMap.newKeySet();
		var ownership = newOwnership("a");
		ownership.start(new IndexerOwnership.Listener() {
			@Override
			public void onOwnershipChanged(String index, boolean owner) {
				if(!owner && "books".equals(index) && thrown.compareAndSet(false, true)) {
					throw new IllegalStateException("simulated listener failure");
				}

				if(owner) {
					owned.add(index);
				} else {
					owned.remove(index);
				}
			}

			@Override
			public void onOwnershipRevoked(String index) {
				owned.remove(index);
			}
		});
		running.add(ownership);
		await(() -> owned.containsAll(Set.of("books", "games")), "a to take both indexes");

		// A live candidate b answers an offer of books
		var alive = System.currentTimeMillis() + 60_000;
		var claims = new ArrayList<IndexerLeadership.Claim>();
		for(var claim : readTable().getClaimsList()) {
			var renewed = claim.toBuilder().setExpiresAt(alive);
			if(claim.getIndex().equals("books")) {
				renewed.setLoadBucket(7).setOffered(true).setTaker("b");
			}

			claims.add(renewed.build());
		}
		writeTable(claims, List.of(candidate("a", alive, null), candidate("b", alive, null)));

		await(() -> {
			try {
				return readTable().getClaimsList().stream()
					.anyMatch(c -> c.getIndex().equals("books") && c.getNode().equals("b"));
			} catch(Exception e) {
				return false;
			}
		}, "the handover to finish");

		assertThat("the listener failed on the first handover", thrown.get(), is(true));
		assertThat("the flush was asked for before the claim moved", flushAsked.contains("books"), is(true));
	}

	/**
	 * A listener that fails on one index must not keep the others from
	 * hearing about theirs. A node that gives its claims up tells the
	 * listener about every index, so none of them goes on taking writes.
	 */
	@Test
	void testAListenerThatFailsOnOneIndexIsStillToldAboutTheOthers() throws Exception {
		names.set(Sets.immutable.of("books", "games"));

		var faults = new TableReadFaults();
		var holder = newFaultyOwnership("a", faults);

		var thrown = new AtomicBoolean();
		Set<String> owned = ConcurrentHashMap.newKeySet();
		Set<String> revoked = ConcurrentHashMap.newKeySet();
		holder.start(new IndexerOwnership.Listener() {
			@Override
			public void onOwnershipChanged(String index, boolean owner) {
				if(owner) {
					owned.add(index);
				} else {
					owned.remove(index);
				}
			}

			@Override
			public void onOwnershipRevoked(String index) {
				if(thrown.compareAndSet(false, true)) {
					throw new IllegalStateException("simulated listener failure");
				}

				owned.remove(index);
				revoked.add(index);
			}
		});
		running.add(holder);
		await(() -> owned.containsAll(Set.of("books", "games")), "a to take both indexes");

		// When the claims cannot be renewed, the node gives both indexes up
		faults.fail.set(true);
		await(thrown::get, "the listener to fail on the first index");

		await(() -> revoked.size() == 1, "the listener to hear about the other index");
	}

	/**
	 * Every candidate rewrites the whole table each round. A field a newer
	 * version added survives a round of this build, at the top level of the
	 * table and on the claims and the candidate entry the round renews.
	 */
	@Test
	void testARoundKeepsFieldsItCannotRead() throws Exception {
		names.set(Sets.immutable.of("books"));

		var newer = UnknownFieldSet.newBuilder()
			.addField(15, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
			.build();
		var alive = System.currentTimeMillis() + 60_000;
		var table = IndexerLeadership.newBuilder()
			.addClaims(claim("books", "a", alive, null).toBuilder().setUnknownFields(newer))
			.addCandidates(candidate("a", alive, null).toBuilder().setUnknownFields(newer))
			.addCandidates(candidate("newer", alive, null))
			.setUnknownFields(newer)
			.build();
		s3Client.putObject(
			PutObjectRequest.builder()
				.bucket(TestObjectStorage.BUCKET)
				.key(storagePrefix + "/indexer-leadership.ef.bin")
				.build(),
			RequestBody.fromBytes(table.toByteArray())
		);

		var owned = start(newOwnership("a"));
		await(() -> owned.contains("books"), "a to keep its claim");
		awaitRenewal("books");

		var renewed = readTable();
		assertThat("the top level", renewed.getUnknownFields().hasField(15), is(true));

		var claim = renewed.getClaimsList().stream()
			.filter(c -> c.getIndex().equals("books"))
			.findFirst()
			.orElseThrow();
		assertThat(claim.getExpiresAt() < alive, is(true));
		assertThat("the renewed claim", claim.getUnknownFields().hasField(15), is(true));

		var self = renewed.getCandidatesList().stream()
			.filter(c -> c.getNode().equals("a"))
			.findFirst()
			.orElseThrow();
		assertThat("the renewed candidate entry", self.getUnknownFields().hasField(15), is(true));
	}
}
