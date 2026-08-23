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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

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
			TestObjectStorage.url(),
			TestObjectStorage.ACCESS_KEY,
			TestObjectStorage.SECRET_KEY,
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
			Optional.of(node),
			Optional.ofNullable(address),
			LEASE,
			names::get,
			name -> nodeLoads.getOrDefault(name, 0d),
			(name, count) -> nodeLoads.merge(name, (double) count, Double::sum),
			name -> flush.apply(name)
		);
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
	 * name it, address and all, keeping the load figure it was offered
	 * under, while the holder gives the index up.
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
			List.of(candidate("b", alive, "http://b:8080"))
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
		var owned = start(ownership);

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
}
