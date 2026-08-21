package se.l4.exofind.engine.index.state;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import se.l4.exofind.engine.logging.Log;
import se.l4.exofind.engine.storage.ObjectStorage;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * IndexerOwnership held through a lease object in the same bucket the
 * indexes live in, so it works wherever the indexes do without needing any
 * other infrastructure.
 *
 * The lease is acquired and renewed by replacing the object conditionally on
 * its tag, the same way the manifests are written - when two candidates race,
 * the storage refuses one of them. Renewal happens at a third of the lease
 * duration; a node that cannot renew before its lease lapses gives the role
 * up on its own rather than assume it still holds it, and the manifest CAS
 * covers the moment where it has lost the role but not yet noticed.
 */
public class ObjectStorageIndexerOwnership implements IndexerOwnership {
	private static final Log logger =
		Log.of(ObjectStorageIndexerOwnership.class);

	/**
	 * Object the lease is stored in, directly under the prefix next to the
	 * indexes.
	 */
	private static final String LEASE_NAME = "indexer-lease.ef.bin";

	/**
	 * How long an answer about the indexer's address is reused before the
	 * lease is read again. Long enough that forwarding every write does not
	 * mean reading the lease for every write, short enough that a failover
	 * stops sending writes to the old node quickly.
	 */
	private static final Duration ADDRESS_CACHE_TTL = Duration.ofSeconds(3);

	private final S3Client client;
	private final String bucket;
	private final String leaseKey;

	private final String node;
	private final Optional<String> address;
	private final Duration leaseDuration;

	private final ScheduledExecutorService executor;

	private Listener listener;

	/**
	 * Whether this node believes it holds the lease. Only touched on the
	 * executor thread, volatile so tests and logging can peek.
	 */
	private volatile boolean held;

	/**
	 * Tag the lease object carried when this node last wrote or read it, in
	 * quoted form. What renewals and takeovers are conditional on.
	 */
	private String leaseETag;

	/**
	 * When the lease this node holds lapses, according to its own clock.
	 * Failing to renew past this point means the role is no longer certain
	 * and is given up.
	 */
	private long heldUntil;

	/**
	 * The last answer about the indexer's address, reused until it is older
	 * than {@link #ADDRESS_CACHE_TTL}.
	 */
	private volatile CachedAddress addressCache;

	private record CachedAddress(Optional<String> address, long fetchedAtNanos) {
	}

	public ObjectStorageIndexerOwnership(
		ObjectStorage storage,
		Optional<String> nodeId,
		Optional<String> address,
		Duration leaseDuration
	) {
		this.client = storage.client();
		this.bucket = storage.bucket();
		this.leaseKey = storage.rootObject(LEASE_NAME);

		this.node = nodeId.orElseGet(ObjectStorageIndexerOwnership::defaultNodeId);
		this.address = address;
		this.leaseDuration = leaseDuration;

		this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			var thread = new Thread(runnable, "indexer-ownership");
			thread.setDaemon(true);
			return thread;
		});
	}

	/**
	 * Identity to hold the lease under when none is configured. The hostname
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

		/*
		 * The name this node competes under is what every other line about the
		 * role is keyed by, and it is generated rather than configured unless
		 * NODE_ID says otherwise. Said here rather than only on acquiring the
		 * role, so that a candidate which never wins the lease still gives the
		 * name its lines will carry.
		 */
		var line = logger.atInfo().addKeyValue("node", node);

		// Without one, writes reaching another node are refused rather than sent here
		address.ifPresent(value -> line.addKeyValue("address", value));

		line.log("Competing for the indexer role");

		executor.execute(this::tick);
	}

	@Override
	public void stop() {
		executor.shutdown();
		try {
			if(!executor.awaitTermination(5, TimeUnit.SECONDS)) {
				executor.shutdownNow();
			}
		} catch(InterruptedException e) {
			Thread.currentThread().interrupt();
			executor.shutdownNow();
		}

		if(held) {
			held = false;
			release();
		}
	}

	@Override
	public Optional<String> indexerAddress() {
		var cached = addressCache;
		if(
			cached != null
				&& System.nanoTime() - cached.fetchedAtNanos() < ADDRESS_CACHE_TTL.toNanos()
		) {
			return cached.address();
		}

		var address = Optional.<String>empty();
		try {
			var current = fetchLease();

			/*
			 * Only a live lease held by someone else names a node worth
			 * forwarding to. Naming this node itself would loop - this is
			 * only asked after this node could not serve the write.
			 */
			if(
				current != null
					&& current.lease().getExpiresAt() > System.currentTimeMillis()
					&& current.lease().hasAddress()
					&& !current.lease().getNode().equals(node)
			) {
				address = Optional.of(current.lease().getAddress());
			}
		} catch(IOException e) {
			logger.atDebug()
				.addKeyValue("node", node)
				.setCause(e)
				.log("Could not read indexer lease for its address; " + e.getMessage());
		}

		this.addressCache = new CachedAddress(address, System.nanoTime());
		return address;
	}

	/**
	 * One round of keeping the lease in order, scheduling the next one when
	 * done. Never allowed to die - the loop is what holds or regains the
	 * role.
	 */
	private void tick() {
		try {
			if(held) {
				renew();
			} else {
				acquire();
			}
		} catch(Exception e) {
			logger.atWarn()
				.addKeyValue("node", node)
				.setCause(e)
				.log("Could not update indexer lease; " + e.getMessage());
		}

		try {
			executor.schedule(this::tick, leaseDuration.toMillis() / 3, TimeUnit.MILLISECONDS);
		} catch(java.util.concurrent.RejectedExecutionException e) {
			// The executor is shutting down, the loop ends here
		}
	}

	/**
	 * Try to take the lease. It is free when there is none, when the one
	 * there has lapsed, and when it turns out to be this node's own - left
	 * over from before a restart.
	 */
	private void acquire() throws IOException {
		var current = fetchLease();
		var now = System.currentTimeMillis();

		if(
			current != null
				&& !current.lease().getNode().equals(node)
				&& current.lease().getExpiresAt() > now
		) {
			// Someone else holds a live lease, check again next tick
			return;
		}

		var lease = buildLease(now);
		var etag = putLease(lease, current == null ? null : current.eTag());
		if(etag == null) {
			// Another candidate won the race, next tick sees who
			return;
		}

		this.leaseETag = etag;
		this.heldUntil = lease.getExpiresAt();
		this.held = true;

		logger.atInfo()
			.addKeyValue("node", node)
			.log("Acquired the indexer role");

		listener.onOwnershipChanged(true);
	}

	/**
	 * Extend the lease this node holds. A refusal means another node has
	 * taken it over; a failure to reach the storage is retried on following
	 * ticks until the lease lapses, at which point holding the role is no
	 * longer certain and it is given up.
	 */
	private void renew() throws IOException {
		var lease = buildLease(System.currentTimeMillis());

		String etag;
		try {
			etag = putLease(lease, leaseETag);
		} catch(IOException e) {
			if(System.currentTimeMillis() >= heldUntil) {
				lost("the lease lapsed before it could be renewed");
				return;
			}

			logger.atWarn()
				.addKeyValue("node", node)
				.setCause(e)
				.log("Could not renew indexer lease yet; " + e.getMessage());
			return;
		}

		if(etag == null) {
			lost("the lease was taken over by another node");
			return;
		}

		this.leaseETag = etag;
		this.heldUntil = lease.getExpiresAt();
	}

	private void lost(String reason) {
		this.held = false;
		this.leaseETag = null;

		logger.atError()
			.addKeyValue("node", node)
			.log("Giving up the indexer role, " + reason);

		listener.onOwnershipChanged(false);
	}

	/**
	 * Hand the lease over on shutdown by writing it back already lapsed, so a
	 * successor does not have to wait it out. Best effort - failing means the
	 * successor waits like for a crashed node.
	 */
	private void release() {
		try {
			putLease(
				buildLease(System.currentTimeMillis() - leaseDuration.toMillis()),
				leaseETag
			);

			logger.atInfo()
				.addKeyValue("node", node)
				.log("Released the indexer role");
		} catch(Exception e) {
			logger.atWarn()
				.addKeyValue("node", node)
				.setCause(e)
				.log("Could not release indexer lease; " + e.getMessage());
		}
	}

	private IndexerLease buildLease(long now) {
		var builder = IndexerLease.newBuilder()
			.setNode(node)
			.setExpiresAt(now + leaseDuration.toMillis());

		address.ifPresent(builder::setAddress);

		return builder.build();
	}

	/**
	 * The lease as the remote holds it, together with the tag it was served
	 * under.
	 */
	private record FetchedLease(IndexerLease lease, String eTag) {
	}

	private FetchedLease fetchLease() throws IOException {
		try(
			var response = client.getObject(
				GetObjectRequest.builder()
					.bucket(bucket)
					.key(leaseKey)
					.build()
			)
		) {
			return new FetchedLease(
				IndexerLease.parseFrom(response.readAllBytes()),
				ObjectStorageSync.quoteETag(response.response().eTag())
			);
		} catch(S3Exception e) {
			if(e.statusCode() == 404) {
				return null;
			}

			throw new IOException("Unable to read indexer lease; " + e.getMessage(), e);
		} catch(Exception e) {
			throw new IOException("Unable to read indexer lease; " + e.getMessage(), e);
		}
	}

	/**
	 * Write the lease, conditionally on the remote still holding the version
	 * this node last saw - or none at all when it saw none.
	 *
	 * @param lease
	 * @param expectedETag
	 *   tag the current lease object is expected to carry, or {@code null}
	 *   when there is expected to be none
	 * @return
	 *   the tag the written lease is served under, or {@code null} when the
	 *   write was refused because another node changed the lease first
	 * @throws IOException
	 *   if the storage could not be reached
	 */
	private String putLease(IndexerLease lease, String expectedETag) throws IOException {
		var bytes = lease.toByteArray();

		var requestBuilder = PutObjectRequest.builder()
			.bucket(bucket)
			.key(leaseKey)
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

			throw new IOException("Unable to write indexer lease; " + e.getMessage(), e);
		} catch(Exception e) {
			/*
			 * The storage may drop the connection while refusing a
			 * conditional write, which arrives as a connection failure
			 * rather than the refusal. Read the lease back to tell what
			 * actually happened: exactly what was written means the write
			 * went through, another node's lease means it was refused, and
			 * this node's older lease means the write never arrived and can
			 * be tried again.
			 */
			var current = tryFetchLease();
			if(current != null) {
				if(current.lease().equals(lease)) {
					return current.eTag();
				}

				if(!current.lease().getNode().equals(lease.getNode())) {
					return null;
				}
			}

			throw new IOException("Unable to write indexer lease; " + e.getMessage(), e);
		}
	}

	/**
	 * Fetch the lease for disambiguating a failed write, where a second
	 * failure just means the original one stands.
	 */
	private FetchedLease tryFetchLease() {
		try {
			return fetchLease();
		} catch(IOException e) {
			return null;
		}
	}
}
