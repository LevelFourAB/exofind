package se.l4.exofind.engine.metrics;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.index.FacetWarmer;
import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.IndexState;
import se.l4.exofind.engine.logging.Log;
import se.l4.exofind.engine.reindex.ReindexJobs;
import se.l4.exofind.engine.reindex.ReindexPhase;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Registers what this node reports about itself and about the indexes it
 * holds.
 *
 * <p>The node-level gauges are read when the registry is scraped and carry no
 * index name. The per-index gauges are rebuilt on a schedule, since an index
 * that is gone has to stop being reported rather than keep the value it last
 * had. Each per-index meter carries one row per open generation, tagged with
 * both {@link Meters#TAG_INDEX} and {@link Meters#TAG_GENERATION}.
 *
 * <p>A deployment holding many indexes pays for one series per open
 * generation per per-index meter. {@code EXOFIND_METRICS_INDEX_ENABLED} turns
 * those off and leaves the node-level meters in place.
 * {@code exofind.index.unhealthy} is reported either way, since it carries
 * rows only for indexes that are not usable.
 */
@ApplicationScoped
public class NodeMetrics {
	private static final Log logger = Log.of(NodeMetrics.class);

	private final MeterRegistry registry;
	private final Indexes indexes;
	private final NodeState nodeState;
	private final ReindexJobs reindexJobs;
	private final FacetWarmer facetWarmer;
	private final boolean perIndex;
	private final Duration interval;

	private final ScheduledExecutorService executor;

	/**
	 * What the local copies took at the last refresh. Held here rather than
	 * measured when scraped, because it walks every index directory.
	 */
	private volatile long diskUsed;

	private MultiGauge unhealthy;
	private MultiGauge documents;
	private MultiGauge pendingChanges;
	private MultiGauge pendingAge;
	private MultiGauge diskBytes;

	NodeMetrics(
		MeterRegistry registry,
		Indexes indexes,
		NodeState nodeState,
		ReindexJobs reindexJobs,
		FacetWarmer facetWarmer,
		@ConfigProperty(
			name = "exofind.metrics.index.enabled",
			defaultValue = "true"
		) boolean perIndex,
		@ConfigProperty(
			name = "exofind.metrics.index.interval",
			defaultValue = "30s"
		) Duration interval
	) {
		this.registry = registry;
		this.indexes = indexes;
		this.nodeState = nodeState;
		this.reindexJobs = reindexJobs;
		this.facetWarmer = facetWarmer;
		this.perIndex = perIndex;
		this.interval = interval;

		this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			var thread = new Thread(runnable, "exofind-metrics");
			thread.setDaemon(true);
			return thread;
		});
	}

	void onStart(@Observes StartupEvent event) {
		registerNodeGauges();
		registerIndexGauges();
		countOwnershipChanges();

		executor.scheduleWithFixedDelay(
			this::refresh,
			0,
			interval.toMillis(),
			TimeUnit.MILLISECONDS
		);
	}

	void onStop(@Observes ShutdownEvent event) {
		executor.shutdownNow();
	}

	private void registerNodeGauges() {
		Gauge.builder(Meters.INDEXES_OPEN, indexes, self -> self.getOpen().size())
			.description("Generations open on this node")
			.register(registry);

		Gauge.builder(Meters.INDEXES_TOTAL, indexes, self -> self.getIndexNames().size())
			.description("Index names the deployment holds")
			.register(registry);

		Gauge.builder(Meters.INDEXES_OWNED, indexes, this::countOwned)
			.description("Index names this node currently writes")
			.register(registry);

		Gauge.builder(
				Meters.REGISTRY_REFRESH_AGE,
				indexes,
				self -> self.getTimeSinceRefresh().toMillis() / 1000d
			)
			.description("Seconds since the refresh loop finished a pass")
			.baseUnit("seconds")
			.register(registry);

		/*
		 * One series per state rather than one per index and state. Which
		 * indexes are in a state needing attention is answered by
		 * exofind.index.unhealthy, which carries rows only while there are any.
		 */
		for(var state : IndexState.values()) {
			Gauge.builder(Meters.INDEX_STATE, indexes, self -> countInState(self, state))
				.tag(Meters.TAG_STATE, state.name())
				.description("Open generations in this synchronization state")
				.register(registry);
		}

		Gauge.builder(Meters.DISK_USED_BYTES, this, self -> self.diskUsed)
			.description("Bytes the local copies take")
			.baseUnit("bytes")
			.register(registry);

		indexes.getDiskMaxSize().ifPresent(max ->
			Gauge.builder(Meters.DISK_MAX_BYTES, indexes, self -> max)
				.description("Bytes the local copies may take")
				.baseUnit("bytes")
				.register(registry)
		);

		Gauge.builder(
				Meters.DOCUMENT_CACHE_HITS,
				indexes,
				self -> self.getDocumentCacheStats().hitCount()
			)
			.description("Reads served from the document cache")
			.register(registry);

		Gauge.builder(
				Meters.DOCUMENT_CACHE_MISSES,
				indexes,
				self -> self.getDocumentCacheStats().missCount()
			)
			.description("Reads the document cache did not hold")
			.register(registry);

		Gauge.builder(
				Meters.DOCUMENT_CACHE_EVICTIONS,
				indexes,
				self -> self.getDocumentCacheStats().evictionCount()
			)
			.description("Entries the document cache dropped")
			.register(registry);

		Gauge.builder(
				Meters.FACET_CACHE_HITS,
				indexes,
				self -> self.getFacetCacheStats().hits()
			)
			.description("Facets answered from what an earlier search counted over the same scope")
			.register(registry);

		Gauge.builder(
				Meters.FACET_CACHE_MISSES,
				indexes,
				self -> self.getFacetCacheStats().misses()
			)
			.description("Facets that had to be counted")
			.register(registry);

		Gauge.builder(
				Meters.FACET_CACHE_EVICTIONS,
				indexes,
				self -> self.getFacetCacheStats().evictions()
			)
			.description("Facet scope entries dropped for ones asked for more recently")
			.register(registry);

		Gauge.builder(
				Meters.FACET_SEGMENT_HITS,
				indexes,
				self -> self.getFacetCacheStats().segmentHits()
			)
			.description("Segments whose counts over everything the index holds were reused")
			.register(registry);

		Gauge.builder(
				Meters.FACET_SEGMENT_MISSES,
				indexes,
				self -> self.getFacetCacheStats().segmentMisses()
			)
			.description("Segments a facet had to count over everything the index holds")
			.register(registry);

		Gauge.builder(
				Meters.FACET_STATE_BYTES,
				indexes,
				self -> self.getFacetCacheStats().heldBytes()
			)
			.description("Heap the facet state of every open reader takes, estimated")
			.baseUnit("bytes")
			.register(registry);

		Gauge.builder(Meters.FACET_WARM_QUEUED, facetWarmer, FacetWarmer::queued)
			.description("Indexes waiting to have the facet state of their latest reader prepared")
			.register(registry);

		/*
		 * One series per phase rather than one per job, so a deployment
		 * reindexing many indexes at once pays the same as one reindexing a
		 * single index. Which index a job belongs to is asked of the reindex
		 * endpoint, which answers from the durable record.
		 */
		for(var phase : ReindexPhase.values()) {
			Gauge.builder(Meters.REINDEX_ACTIVE, reindexJobs, self -> countInPhase(self, phase))
				.tag(Meters.TAG_PHASE, phase.name())
				.description("Reindex jobs this node knows of in this phase")
				.register(registry);
		}
	}

	private static double countInPhase(ReindexJobs jobs, ReindexPhase phase) {
		var count = 0;
		for(var job : jobs.list()) {
			if(job.phase() == phase) {
				count++;
			}
		}

		return count;
	}

	private void registerIndexGauges() {
		this.unhealthy = MultiGauge.builder(Meters.INDEX_UNHEALTHY)
			.description("Open generations that are not usable")
			.register(registry);

		this.diskBytes = MultiGauge.builder(Meters.INDEX_DISK_BYTES)
			.description("Bytes an index takes in this node's directory")
			.baseUnit("bytes")
			.register(registry);

		this.documents = MultiGauge.builder(Meters.INDEX_DOCUMENTS)
			.description("Documents in an index this node writes")
			.register(registry);

		this.pendingChanges = MultiGauge.builder(Meters.INDEX_PENDING_CHANGES)
			.description("Changes waiting for a commit")
			.register(registry);

		this.pendingAge = MultiGauge.builder(Meters.INDEX_PENDING_AGE)
			.description("Seconds the oldest change waiting for a commit has waited")
			.baseUnit("seconds")
			.register(registry);
	}

	/**
	 * Count index names gained and lost. Read as a rate, this is how often the
	 * deployment moves writers around; a name that keeps changing hands is
	 * what makes writes slow without any one node looking unhealthy.
	 */
	private void countOwnershipChanges() {
		nodeState.addListener(new NodeState.Listener() {
			@Override
			public void onOwnershipChanged(NodeState state, String index) {
				/*
				 * A null name is every index changing hands at once, which a
				 * node that can not be contested does as it starts. Whether
				 * that was a gain is answered by the node, since there is no
				 * one name to ask about.
				 */
				var held = index == null
					? state.holdsEveryIndex()
					: state.isIndexer(index);

				registry.counter(
						Meters.OWNERSHIP_CHANGE,
						Meters.TAG_DIRECTION,
						held ? Meters.DIRECTION_GAINED : Meters.DIRECTION_LOST
					)
					.increment();
			}

			@Override
			public void onOwnershipRevoked(NodeState state, String index) {
				registry.counter(
						Meters.OWNERSHIP_CHANGE,
						Meters.TAG_DIRECTION, Meters.DIRECTION_REVOKED
					)
					.increment();
			}
		});
	}

	private double countOwned(Indexes indexes) {
		var count = 0;
		for(var name : indexes.getIndexNames()) {
			if(nodeState.isIndexer(name)) {
				count++;
			}
		}

		return count;
	}

	private static double countInState(Indexes indexes, IndexState state) {
		var count = 0;
		for(var index : indexes.getOpen().values()) {
			if(index.getState() == state) {
				count++;
			}
		}

		return count;
	}

	/**
	 * Rebuild the per-index rows. Rows are replaced whole, so an index that is
	 * gone stops being reported rather than keeping the value it last had.
	 */
	void refresh() {
		try {
			var unhealthyRows = new ArrayList<MultiGauge.Row<?>>();
			var documentRows = new ArrayList<MultiGauge.Row<?>>();
			var pendingChangeRows = new ArrayList<MultiGauge.Row<?>>();
			var pendingAgeRows = new ArrayList<MultiGauge.Row<?>>();

			for(var entry : indexes.getOpen().entrySet()) {
				var tags = tagsFor(entry.getKey());
				var index = entry.getValue();
				var state = index.getState();

				if(state != IndexState.USABLE) {
					unhealthyRows.add(MultiGauge.Row.of(
						tags.and(Meters.TAG_STATE, state.name()),
						1
					));
				}

				if(!perIndex || index.isReadOnly()) {
					/*
					 * Every replica of an index reports the same document count
					 * and the same commit backlog, so only the node writing it
					 * reports them. Reporting them everywhere multiplies those
					 * series by the number of nodes holding a copy.
					 */
					continue;
				}

				documentRows.add(MultiGauge.Row.of(tags, documentCount(index)));
				pendingChangeRows.add(MultiGauge.Row.of(tags, index.getPendingChanges()));
				pendingAgeRows.add(MultiGauge.Row.of(
					tags,
					index.getPendingAge().toMillis() / 1000d
				));
			}

			unhealthy.register(unhealthyRows, true);
			documents.register(documentRows, true);
			pendingChanges.register(pendingChangeRows, true);
			pendingAge.register(pendingAgeRows, true);

			refreshDisk();
		} catch(RuntimeException e) {
			// Letting this out would cancel the schedule and freeze every row
			logger.atWarn()
				.setCause(e)
				.log("Could not refresh the index metrics; " + e.getMessage());
		}
	}

	private void refreshDisk() {
		try {
			var sizes = indexes.getLocalCopySizes();

			var rows = new ArrayList<MultiGauge.Row<?>>(sizes.size());
			var total = 0L;
			for(var entry : sizes.entrySet()) {
				total += entry.getValue();

				if(perIndex) {
					rows.add(MultiGauge.Row.of(tagsFor(entry.getKey()), entry.getValue()));
				}
			}

			diskBytes.register(rows, true);
			diskUsed = total;
		} catch(IOException e) {
			logger.atDebug()
				.setCause(e)
				.log("Could not measure what the local copies take on disk");
		}
	}

	/**
	 * Split a name a generation was opened under into the index name grants
	 * and dashboards are written against, and the generation under it.
	 */
	private static Tags tagsFor(String opened) {
		var parsed = IndexName.parse(opened);
		return Tags.of(
			Meters.TAG_INDEX, parsed.index(),
			Meters.TAG_GENERATION, parsed.generation() == null ? "" : parsed.generation()
		);
	}

	/**
	 * Read the document count, answering {@code -1} when the index can not be
	 * read right now. A generation being pulled has no reader yet, and a
	 * refresh must not fail for it.
	 */
	private static double documentCount(Index index) {
		try {
			return index.getDocumentCount();
		} catch(IOException | RuntimeException e) {
			return -1;
		}
	}
}
