package se.l4.exofind.engine.index.state;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IndexWriteLoad is how heavily each index has recently been written on this
 * node: a count of changed documents per index name, halved for every
 * half-life that passes. It decides which index a node over its fair share
 * hands over when the indexes are divided among the candidates - the most
 * idle one, so a busy index stays with the writer that is warm for it.
 *
 * <p>The figures are this node's own view and are kept in memory only; after
 * a restart every index reads as idle until writes arrive again. Names are
 * per index, without a generation, so every generation of a name counts
 * toward one figure. Safe for concurrent use.
 */
public class IndexWriteLoad {
	/**
	 * How long it takes for a recorded write to count half: long enough that
	 * the figure holds steady across many rebalance rounds, short enough
	 * that a shift in traffic shows within the hour.
	 */
	public static final Duration DEFAULT_HALF_LIFE = Duration.ofMinutes(10);

	private final long halfLifeMs;

	/**
	 * One figure per name ever written while the node runs. Never pruned - a
	 * name whose index is gone leaves one small entry behind, and nothing
	 * asks for it again.
	 */
	private final ConcurrentHashMap<String, Load> loads = new ConcurrentHashMap<>();

	private record Load(double value, long recordedAt) {
	}

	public IndexWriteLoad() {
		this(DEFAULT_HALF_LIFE);
	}

	public IndexWriteLoad(Duration halfLife) {
		this.halfLifeMs = halfLife.toMillis();
	}

	/**
	 * Record that {@code changes} documents of the index changed at
	 * {@code now}.
	 *
	 * @param index
	 *   name of the index, without a generation
	 * @param changes
	 *   how many documents the change covered
	 * @param now
	 *   when the change happened, as milliseconds since the Unix epoch
	 */
	public void record(String index, long changes, long now) {
		loads.compute(index, (key, load) -> new Load(decayed(load, now) + changes, now));
	}

	/**
	 * The figure as of {@code now}: every recorded change, halved for every
	 * half-life that has passed since it was recorded. Zero for an index
	 * never written here.
	 *
	 * @param index
	 *   name of the index, without a generation
	 * @param now
	 *   as milliseconds since the Unix epoch
	 */
	public double get(String index, long now) {
		return decayed(loads.get(index), now);
	}

	/**
	 * The recorded figure brought up to {@code now}. A figure stamped in the
	 * future reads as current rather than grown.
	 */
	private double decayed(Load load, long now) {
		if(load == null) {
			return 0;
		}

		var elapsed = Math.max(0, now - load.recordedAt());
		return load.value() * Math.pow(0.5, (double) elapsed / halfLifeMs);
	}
}
