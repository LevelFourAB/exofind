package se.l4.exofind.engine.index.state;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.set.ImmutableSet;
import org.eclipse.collections.api.set.SetIterable;

import se.l4.exofind.engine.logging.Log;
import se.l4.exofind.engine.storage.ObjectStorage;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * IndexerOwnership held through a leadership table in the same bucket the
 * indexes live in, so it works wherever the indexes do without needing any
 * other infrastructure.
 *
 * The table is one object naming, per index, the node that writes it, next
 * to an entry per candidate saying it is alive. It is replaced whole,
 * conditionally on its tag the same way the manifests are - when two
 * candidates race, the storage refuses one of them, and the loser rereads on
 * its next round. Every candidate runs a round at a third of the claim
 * duration: it renews its own entries, takes claims that have lapsed, and
 * divides the indexes up - claiming free ones while it holds fewer than its
 * fair share of the count, and handing one over per round while it holds
 * more and another candidate holds less. A candidate that cannot renew
 * before its claims lapse gives every index up on its own rather than assume
 * it still holds them, and the manifest CAS covers the moment where it has
 * lost an index but not yet noticed.
 *
 * An index nothing holds - just created, or its holder just died - is taken
 * by whichever candidate is asked to write it, through {@link #tryClaim},
 * without waiting for a round. Fair shares guide the rounds; a write is
 * served first and balanced later.
 */
public class ObjectStorageIndexerOwnership implements IndexerOwnership {
	private static final Log logger =
		Log.of(ObjectStorageIndexerOwnership.class);

	/**
	 * Object the leadership table is stored in, directly under the prefix
	 * next to the indexes.
	 */
	private static final String TABLE_NAME = "indexer-leadership.ef.bin";

	/**
	 * How long a read of the table is reused before it is read again. Long
	 * enough that forwarding every write does not mean reading the table for
	 * every write, short enough that a failover stops sending writes to the
	 * old node quickly.
	 */
	private static final Duration TABLE_CACHE_TTL = Duration.ofSeconds(3);

	/**
	 * How many indexes are handed over per round while this node holds more
	 * than its fair share. Every handover costs the taker a pull and a writer
	 * reopen, so rebalancing is deliberately gradual rather than immediate.
	 */
	private static final int SHED_PER_ROUND = 1;

	/**
	 * How long a write may wait on {@link #tryClaim} before it is answered
	 * without the index instead. Bounded by the caller holding a request
	 * open, not by how long claiming could conceivably take.
	 */
	private static final Duration CLAIM_TIMEOUT = Duration.ofSeconds(10);

	private final S3Client client;
	private final String bucket;
	private final String tableKey;

	private final String node;
	private final Optional<String> address;
	private final Duration leaseDuration;

	/**
	 * The names the deployment's registry currently holds, read fresh every
	 * round, or {@code null} while the registry has never been read. What is
	 * divided up; a name not in it is not claimed and an own claim on one is
	 * dropped. A {@code null} answer claims and drops nothing - what exists
	 * is not known yet.
	 */
	private final Supplier<? extends SetIterable<String>> indexNames;

	/**
	 * The claim each index currently being claimed through {@link #tryClaim}
	 * will resolve to, so a burst of writes for one index costs one claim
	 * rather than queueing one each.
	 */
	private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pendingClaims =
		new ConcurrentHashMap<>();

	private final ScheduledExecutorService executor;

	private Listener listener;

	private volatile boolean started;

	/**
	 * The indexes this node believes it holds. Only replaced on the executor
	 * thread, volatile so {@link #tryClaim} can answer for an already held
	 * index without coordinating.
	 */
	private volatile ImmutableSet<String> held = Sets.immutable.empty();

	/**
	 * When the claims this node holds lapse, according to its own clock.
	 * Failing to renew past this point means holding them is no longer
	 * certain and they are given up. Only touched on the executor thread.
	 */
	private long heldUntil;

	/**
	 * The last read of the table, reused until it is older than
	 * {@link #TABLE_CACHE_TTL}. A {@code null} table means the last read
	 * failed and nothing is known.
	 */
	private volatile CachedTable tableCache;

	private record CachedTable(IndexerLeadership table, long fetchedAtNanos) {
	}

	/**
	 * The table as one round rewrote it, together with the indexes this node
	 * holds in it.
	 */
	private record Rebuilt(IndexerLeadership table, ImmutableSet<String> held) {
	}

	/**
	 * The table as the remote holds it, together with the tag it was served
	 * under.
	 */
	private record FetchedTable(IndexerLeadership table, String eTag) {
	}

	public ObjectStorageIndexerOwnership(
		ObjectStorage storage,
		Optional<String> nodeId,
		Optional<String> address,
		Duration leaseDuration,
		Supplier<? extends SetIterable<String>> indexNames
	) {
		this.client = storage.client();
		this.bucket = storage.bucket();
		this.tableKey = storage.rootObject(TABLE_NAME);

		this.node = nodeId.orElseGet(ObjectStorageIndexerOwnership::defaultNodeId);
		this.address = address;
		this.leaseDuration = leaseDuration;
		this.indexNames = indexNames;

		this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			var thread = new Thread(runnable, "indexer-ownership");
			thread.setDaemon(true);
			return thread;
		});
	}

	/**
	 * Identity to compete under when none is configured. The hostname
	 * carries meaning in most deployments, and the suffix keeps two nodes
	 * that happen to share one apart.
	 */
	private static String defaultNodeId() {
		String host;
		try {
			host = InetAddress.getLocalHost().getHostName();
		} catch(UnknownHostException e) {
			host = "node";
		}

		return host + "-" + Long.toHexString(ThreadLocalRandom.current().nextLong() & 0xffffffffL);
	}

	@Override
	public void start(Listener listener) {
		this.listener = listener;
		this.started = true;

		/*
		 * The name this node competes under is what every other line about
		 * indexes it writes is keyed by, and it is generated rather than
		 * configured unless NODE_ID says otherwise. Said here rather than
		 * only on taking an index, so that a candidate which never wins one
		 * still gives the name its lines will carry.
		 */
		var line = logger.atInfo().addKeyValue("node", node);

		// Without one, writes reaching another node are refused rather than sent here
		address.ifPresent(value -> line.addKeyValue("address", value));

		line.log("Competing for the indexer role");

		executor.execute(this::tick);
	}

	@Override
	public void stop() {
		started = false;

		executor.shutdown();
		try {
			if(!executor.awaitTermination(5, TimeUnit.SECONDS)) {
				executor.shutdownNow();
			}
		} catch(InterruptedException e) {
			Thread.currentThread().interrupt();
			executor.shutdownNow();
		}

		/*
		 * Even a node that holds nothing has a live candidate entry in the
		 * table, which free-index writes are otherwise forwarded to until it
		 * lapses.
		 */
		release();
	}

	@Override
	public boolean tryClaim(String index) {
		if(!started) {
			return false;
		}

		if(held.contains(index)) {
			return true;
		}

		/*
		 * An index whose holder is alive is the common case here - every
		 * write this node forwards asks first - and is answered from the
		 * cached table rather than by coordinating.
		 */
		var cached = cachedTable();
		if(cached != null && hasLiveClaimByOther(cached, index, System.currentTimeMillis())) {
			return false;
		}

		var claim = new CompletableFuture<Boolean>();
		var pending = pendingClaims.putIfAbsent(index, claim);
		if(pending == null) {
			// This caller starts the claim; everyone else waits on the same one
			pending = claim;
			try {
				executor.execute(() -> {
					try {
						claim.complete(claimTarget(index));
					} catch(Throwable t) {
						claim.completeExceptionally(t);
					} finally {
						pendingClaims.remove(index, claim);
					}
				});
			} catch(RejectedExecutionException e) {
				pendingClaims.remove(index, claim);
				claim.completeExceptionally(e);
			}
		}

		try {
			return pending.get(CLAIM_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		} catch(InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		} catch(ExecutionException | TimeoutException e) {
			logger.atDebug()
				.addKeyValue("node", node)
				.addKeyValue("index", index)
				.setCause(e)
				.log("Could not claim the index; " + e.getMessage());

			return false;
		}
	}

	@Override
	public boolean hasHolder(String index) {
		if(held.contains(index)) {
			return true;
		}

		var table = cachedTable();
		if(table == null) {
			return false;
		}

		var now = System.currentTimeMillis();
		for(var claim : table.getClaimsList()) {
			if(index.equals(claim.getIndex()) && claim.getExpiresAt() > now) {
				return true;
			}
		}

		return false;
	}

	@Override
	public Optional<String> indexerAddress(String index) {
		var table = cachedTable();
		if(table == null) {
			return Optional.empty();
		}

		var now = System.currentTimeMillis();

		for(var claim : table.getClaimsList()) {
			if(!index.equals(claim.getIndex())) {
				continue;
			}

			if(claim.getExpiresAt() <= now) {
				// Free to be taken, so it is answered like an index nothing holds
				break;
			}

			/*
			 * Naming this node itself would loop - this is only asked after
			 * this node could not serve the write. A live holder without an
			 * address cannot be sent anything either way.
			 */
			if(node.equals(claim.getNode()) || !claim.hasAddress()) {
				return Optional.empty();
			}

			return Optional.of(claim.getAddress());
		}

		/*
		 * Nothing holds the index, so the write is sent to a candidate that
		 * takes it by serving it. Picked by hashing the name over the live
		 * candidates, so every node sends writes for one index to the same
		 * place instead of scattering the first writes of a new index.
		 */
		var candidates = new ArrayList<IndexerLeadership.Candidate>();
		for(var candidate : table.getCandidatesList()) {
			if(
				candidate.getExpiresAt() > now
					&& candidate.hasAddress()
					&& !node.equals(candidate.getNode())
			) {
				candidates.add(candidate);
			}
		}

		if(candidates.isEmpty()) {
			return Optional.empty();
		}

		candidates.sort(Comparator.comparing(IndexerLeadership.Candidate::getNode));
		var picked = candidates.get(Math.floorMod(index.hashCode(), candidates.size()));
		return Optional.of(picked.getAddress());
	}

	/**
	 * One round of keeping the table in order, scheduling the next one when
	 * done. Never allowed to die - the loop is what holds and regains the
	 * indexes.
	 */
	private void tick() {
		try {
			coordinate();
		} catch(Exception e) {
			logger.atWarn()
				.addKeyValue("node", node)
				.setCause(e)
				.log("Could not update indexer leadership; " + e.getMessage());
		}

		/*
		 * Both a storage that cannot be reached and a round that keeps losing
		 * the conditional write end up here: past the expiry of the claims,
		 * holding them is no longer certain and they are given up.
		 */
		if(!held.isEmpty() && System.currentTimeMillis() >= heldUntil) {
			lostAll("the claims lapsed before they could be renewed");
		}

		/*
		 * Jittered so two candidates that collided once do not stay in phase
		 * and keep colliding round after round - repeated losses are what
		 * lets the claims lapse without their holder being gone.
		 */
		var third = leaseDuration.toMillis() / 3;
		var jitter = ThreadLocalRandom.current().nextLong(-third / 6, third / 6 + 1);

		try {
			executor.schedule(this::tick, third + jitter, TimeUnit.MILLISECONDS);
		} catch(RejectedExecutionException e) {
			// The executor is shutting down, the loop ends here
		}
	}

	/**
	 * Run one round: renew, take what has lapsed, divide the rest up. Every
	 * candidate rewrites the whole table, so losing the conditional write is
	 * routine; it is retried against the winner's table rather than waiting
	 * out a round, so a candidate that keeps losing does not drift toward its
	 * claims lapsing.
	 */
	private void coordinate() throws IOException {
		for(var attempt = 0; attempt < 3; attempt++) {
			var fetched = fetchTable();
			var current = fetched == null
				? IndexerLeadership.getDefaultInstance()
				: fetched.table();
			var now = System.currentTimeMillis();

			var rebuilt = rebuild(current, now, null);
			var etag = putTable(rebuilt.table(), fetched == null ? null : fetched.eTag());
			if(etag != null) {
				apply(now, rebuilt);
				return;
			}
		}
	}

	/**
	 * Take one index here and now, for a write that found no holder. Retried
	 * over a lost conditional write - the race was about the table, not
	 * necessarily about this index - and given up when another node turns out
	 * to hold the index alive.
	 */
	private boolean claimTarget(String index) {
		if(held.contains(index)) {
			return true;
		}

		for(var attempt = 0; attempt < 3; attempt++) {
			FetchedTable fetched;
			try {
				fetched = fetchTable();
			} catch(IOException e) {
				logger.atDebug()
					.addKeyValue("node", node)
					.addKeyValue("index", index)
					.setCause(e)
					.log("Could not read leadership to claim the index; " + e.getMessage());

				return false;
			}

			var current = fetched == null
				? IndexerLeadership.getDefaultInstance()
				: fetched.table();
			var now = System.currentTimeMillis();

			if(hasLiveClaimByOther(current, index, now)) {
				return false;
			}

			var rebuilt = rebuild(current, now, index);

			String etag;
			try {
				etag = putTable(rebuilt.table(), fetched == null ? null : fetched.eTag());
			} catch(IOException e) {
				logger.atDebug()
					.addKeyValue("node", node)
					.addKeyValue("index", index)
					.setCause(e)
					.log("Could not write leadership to claim the index; " + e.getMessage());

				return false;
			}

			if(etag != null) {
				apply(now, rebuilt);
				return true;
			}
		}

		return false;
	}

	/**
	 * Whether another node holds a live claim on the index, which is what
	 * makes it not free.
	 */
	private boolean hasLiveClaimByOther(IndexerLeadership table, String index, long now) {
		for(var claim : table.getClaimsList()) {
			if(
				index.equals(claim.getIndex())
					&& !node.equals(claim.getNode())
					&& claim.getExpiresAt() > now
			) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Rewrite the table for this round: this node's entries renewed, lapsed
	 * entries dropped, and the indexes divided up.
	 *
	 * @param forcedClaim
	 *   an index to take regardless of fair share, because a write for it is
	 *   waiting, or {@code null} for a normal round. The caller has already
	 *   checked that nothing holds it alive.
	 */
	private Rebuilt rebuild(IndexerLeadership current, long now, String forcedClaim) {
		var names = indexNames.get();
		var expiresAt = now + leaseDuration.toMillis();
		var builder = IndexerLeadership.newBuilder();

		// The candidates that are alive, with this node's entry renewed
		var otherCandidates = new ArrayList<IndexerLeadership.Candidate>();
		for(var candidate : current.getCandidatesList()) {
			if(!node.equals(candidate.getNode()) && candidate.getExpiresAt() > now) {
				otherCandidates.add(candidate);
			}
		}

		var self = IndexerLeadership.Candidate.newBuilder()
			.setNode(node)
			.setExpiresAt(expiresAt);
		address.ifPresent(self::setAddress);

		otherCandidates.sort(Comparator.comparing(IndexerLeadership.Candidate::getNode));
		builder.addAllCandidates(otherCandidates);
		builder.addCandidates(self);

		/*
		 * Sort the claims: this node's own are re-adopted whatever their
		 * expiry - nothing took them, so they are still its - while a lapsed
		 * claim of another node is dropped, which is what frees the index.
		 */
		var mine = new TreeSet<String>();
		var claimedByOthers = new HashSet<String>();
		var othersClaims = new ArrayList<IndexerLeadership.Claim>();
		var claimsPerNode = new HashMap<String, Integer>();

		for(var claim : current.getClaimsList()) {
			if(node.equals(claim.getNode())) {
				mine.add(claim.getIndex());
			} else if(claim.getExpiresAt() > now && claimedByOthers.add(claim.getIndex())) {
				othersClaims.add(claim);
				claimsPerNode.merge(claim.getNode(), 1, Integer::sum);
			}
		}

		if(forcedClaim != null) {
			mine.add(forcedClaim);
		}

		/*
		 * A registry that has never been read claims and drops nothing - a
		 * round that trusted it would drop every claim a restarted node still
		 * holds. An empty registry that has been read is an answer: every
		 * remaining claim is on a deleted index and is dropped.
		 */
		if(names != null) {
			mine.removeIf(name -> !names.contains(name) && !name.equals(forcedClaim));

			var fairShare = fairShare(names.size(), otherCandidates.size() + 1);

			for(var name : names.toSortedList()) {
				if(mine.size() >= fairShare) {
					break;
				}

				if(!mine.contains(name) && !claimedByOthers.contains(name)) {
					mine.add(name);
				}
			}

			/*
			 * Handing an index over only helps when somebody with room picks
			 * it up - shed toward a candidate below its share, never into the
			 * void. The forced claim is what this round is for and is never
			 * the one handed over.
			 */
			if(
				mine.size() > fairShare
					&& anyCandidateBelow(otherCandidates, claimsPerNode, fairShare)
			) {
				var toShed = SHED_PER_ROUND;
				var iterator = mine.descendingIterator();
				while(iterator.hasNext() && toShed > 0 && mine.size() > fairShare) {
					if(!iterator.next().equals(forcedClaim)) {
						iterator.remove();
						toShed--;
					}
				}
			}
		}

		builder.addAllClaims(othersClaims);

		for(var name : mine) {
			var claim = IndexerLeadership.Claim.newBuilder()
				.setIndex(name)
				.setNode(node)
				.setExpiresAt(expiresAt);
			address.ifPresent(claim::setAddress);

			builder.addClaims(claim);
		}

		return new Rebuilt(builder.build(), Sets.immutable.ofAll(mine));
	}

	/**
	 * How many indexes each candidate should end up holding, which claiming
	 * stops at and shedding works down toward.
	 */
	private static int fairShare(int indexes, int candidates) {
		return (indexes + candidates - 1) / candidates;
	}

	/**
	 * Whether some other live candidate holds fewer indexes than the fair
	 * share, meaning a handover has somewhere to go.
	 */
	private static boolean anyCandidateBelow(
		Iterable<IndexerLeadership.Candidate> candidates,
		HashMap<String, Integer> claimsPerNode,
		int fairShare
	) {
		for(var candidate : candidates) {
			if(claimsPerNode.getOrDefault(candidate.getNode(), 0) < fairShare) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Take a written round into effect: what it holds, for how long, and the
	 * listener told about every index that changed hands.
	 */
	private void apply(long now, Rebuilt rebuilt) {
		this.heldUntil = now + leaseDuration.toMillis();
		this.tableCache = new CachedTable(rebuilt.table(), System.nanoTime());

		var before = held;
		this.held = rebuilt.held();

		for(var name : rebuilt.held()) {
			if(!before.contains(name)) {
				logger.atInfo()
					.addKeyValue("node", node)
					.addKeyValue("index", name)
					.log("Took over writing the index");

				listener.onOwnershipChanged(name, true);
			}
		}

		for(var name : before) {
			if(!rebuilt.held().contains(name)) {
				logger.atInfo()
					.addKeyValue("node", node)
					.addKeyValue("index", name)
					.log("Handed over writing the index");

				listener.onOwnershipChanged(name, false);
			}
		}
	}

	/**
	 * Give every index up because holding them is no longer certain, telling
	 * the listener so the indexes stop taking writes here.
	 */
	private void lostAll(String reason) {
		var before = held;
		this.held = Sets.immutable.empty();

		for(var name : before) {
			logger.atError()
				.addKeyValue("node", node)
				.addKeyValue("index", name)
				.log("Giving up writing the index, " + reason);

			listener.onOwnershipRevoked(name);
		}
	}

	/**
	 * Step out of the table on shutdown - claims and candidacy both - so
	 * successors do not have to wait the claims out and free-index writes
	 * stop being sent here. Retried over a lost conditional write, because
	 * every candidate rewrites the table all the time and there is no next
	 * round to try again on. Best effort - failing means successors wait
	 * like for a crashed node.
	 */
	private void release() {
		try {
			for(var attempt = 0; attempt < 3; attempt++) {
				var fetched = fetchTable();
				if(fetched == null) {
					return;
				}

				var builder = IndexerLeadership.newBuilder();
				for(var candidate : fetched.table().getCandidatesList()) {
					if(!node.equals(candidate.getNode())) {
						builder.addCandidates(candidate);
					}
				}

				for(var claim : fetched.table().getClaimsList()) {
					if(!node.equals(claim.getNode())) {
						builder.addClaims(claim);
					}
				}

				var without = builder.build();
				if(without.equals(fetched.table())) {
					// Nothing in the table names this node, so there is nothing to step out of
					return;
				}

				if(putTable(without, fetched.eTag()) != null) {
					logger.atInfo()
						.addKeyValue("node", node)
						.log("Stepped out of the indexer role");

					return;
				}
			}

			logger.atWarn()
				.addKeyValue("node", node)
				.log(
					"Could not hand the held indexes over, another node kept"
						+ " changing the leadership; successors wait the claims out"
				);
		} catch(Exception e) {
			logger.atWarn()
				.addKeyValue("node", node)
				.setCause(e)
				.log("Could not release held indexes; " + e.getMessage());
		}
	}

	/**
	 * The table for answering where a write goes, read at most once per
	 * {@link #TABLE_CACHE_TTL}. {@code null} when the last read failed - an
	 * empty table is an answer, an unreadable one is not.
	 */
	private IndexerLeadership cachedTable() {
		var cached = tableCache;
		if(
			cached != null
				&& System.nanoTime() - cached.fetchedAtNanos() < TABLE_CACHE_TTL.toNanos()
		) {
			return cached.table();
		}

		IndexerLeadership table = null;
		try {
			var fetched = fetchTable();
			table = fetched == null ? IndexerLeadership.getDefaultInstance() : fetched.table();
		} catch(IOException e) {
			logger.atDebug()
				.addKeyValue("node", node)
				.setCause(e)
				.log("Could not read indexer leadership; " + e.getMessage());
		}

		this.tableCache = new CachedTable(table, System.nanoTime());
		return table;
	}

	private FetchedTable fetchTable() throws IOException {
		try(
			var response = client.getObject(
				GetObjectRequest.builder()
					.bucket(bucket)
					.key(tableKey)
					.build()
			)
		) {
			return new FetchedTable(
				IndexerLeadership.parseFrom(response.readAllBytes()),
				ObjectStorageSync.quoteETag(response.response().eTag())
			);
		} catch(S3Exception e) {
			if(e.statusCode() == 404) {
				return null;
			}

			throw new IOException("Unable to read indexer leadership; " + e.getMessage(), e);
		} catch(Exception e) {
			throw new IOException("Unable to read indexer leadership; " + e.getMessage(), e);
		}
	}

	/**
	 * Write the table, conditionally on the remote still holding the version
	 * this node last saw - or none at all when it saw none.
	 *
	 * @param table
	 * @param expectedETag
	 *   tag the current table object is expected to carry, or {@code null}
	 *   when there is expected to be none
	 * @return
	 *   the tag the written table is served under, or {@code null} when the
	 *   write was refused because another node changed the table first
	 * @throws IOException
	 *   if the storage could not be reached
	 */
	private String putTable(IndexerLeadership table, String expectedETag) throws IOException {
		var bytes = table.toByteArray();

		var requestBuilder = PutObjectRequest.builder()
			.bucket(bucket)
			.key(tableKey)
			.contentType("application/octet-stream");

		if(expectedETag != null) {
			requestBuilder.ifMatch(expectedETag);
		} else {
			requestBuilder.ifNoneMatch("*");
		}

		try {
			var response = client.putObject(
				requestBuilder.build(),
				RequestBody.fromBytes(bytes)
			);

			return ObjectStorageSync.quoteETag(response.eTag());
		} catch(S3Exception e) {
			if(e.statusCode() == 412) {
				return null;
			}

			throw new IOException("Unable to write indexer leadership; " + e.getMessage(), e);
		} catch(Exception e) {
			/*
			 * The storage may drop the connection while refusing a
			 * conditional write, which arrives as a connection failure
			 * rather than the refusal. Read the table back to tell what
			 * actually happened: exactly what was written means the write
			 * went through, a different table under a different tag means it
			 * was refused, and the tag this node conditioned on means the
			 * write never arrived and can be tried again.
			 */
			var current = tryFetchTable();
			if(current != null) {
				if(current.table().equals(table)) {
					return current.eTag();
				}

				if(!Objects.equals(current.eTag(), expectedETag)) {
					return null;
				}
			}

			throw new IOException("Unable to write indexer leadership; " + e.getMessage(), e);
		}
	}

	/**
	 * Fetch the table for disambiguating a failed write, where a second
	 * failure just means the original one stands.
	 */
	private FetchedTable tryFetchTable() {
		try {
			return fetchTable();
		} catch(IOException e) {
			return null;
		}
	}
}
