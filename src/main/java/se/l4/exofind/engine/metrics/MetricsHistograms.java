package se.l4.exofind.engine.metrics;

import java.time.Duration;
import java.util.Set;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Decides what shape the engine's timers publish.
 *
 * <p>Every mode publishes buckets rather than quantiles. Buckets from
 * different label values add up, so a collector may drop the index name and
 * sum what is left and still read a correct percentile from the result.
 * Quantiles do not add up, and a collector that aggregates them produces a
 * number that means nothing - which is why no mode here publishes them.
 *
 * <p>{@code EXOFIND_METRICS_HISTOGRAM_MODE} chooses between them:
 *
 * <ul>
 * <li>{@code slo}, the default, publishes the bucket boundaries below. Around
 * a dozen buckets per timer, which stays affordable when the timers carry an
 * index name.
 * <li>{@code detailed} publishes Micrometer's own bucket set, which is some
 * tens of buckets per timer. Meant for a backend storing native histograms,
 * where the whole set costs about what one series costs.
 * <li>{@code none} publishes a count, a total and a maximum, and no buckets at
 * all. No percentile can be read from the result.
 * </ul>
 */
@Dependent
public class MetricsHistograms {
	/**
	 * Boundaries the {@code slo} mode publishes, covering a search that
	 * answers from memory up to one that has to be given up on.
	 */
	private static final Duration[] REQUEST_BUCKETS = {
		Duration.ofMillis(1),
		Duration.ofMillis(5),
		Duration.ofMillis(10),
		Duration.ofMillis(25),
		Duration.ofMillis(50),
		Duration.ofMillis(100),
		Duration.ofMillis(250),
		Duration.ofMillis(500),
		Duration.ofSeconds(1),
		Duration.ofSeconds(5),
		Duration.ofSeconds(10)
	};

	/**
	 * Boundaries for work measured against remote storage rather than against
	 * a person waiting, which is slower and worth following further out.
	 */
	private static final Duration[] SYNC_BUCKETS = {
		Duration.ofMillis(10),
		Duration.ofMillis(50),
		Duration.ofMillis(250),
		Duration.ofSeconds(1),
		Duration.ofSeconds(5),
		Duration.ofSeconds(15),
		Duration.ofSeconds(60),
		Duration.ofMinutes(5)
	};

	private static final Set<String> REQUEST_METERS = Set.of(
		Meters.SEARCH,
		Meters.WRITE
	);

	private static final Set<String> SYNC_METERS = Set.of(
		Meters.COMMIT,
		Meters.SYNC_PUSH,
		Meters.SYNC_PULL,
		Meters.STORAGE_OPERATION
	);

	@Produces
	@Singleton
	public MeterFilter histograms(
		@ConfigProperty(
			name = "exofind.metrics.histogram.mode",
			defaultValue = "slo"
		) String mode
	) {
		return new MeterFilter() {
			@Override
			public DistributionStatisticConfig configure(
				Meter.Id id,
				DistributionStatisticConfig config
			) {
				var buckets = bucketsFor(id.getName());
				if(buckets == null || "none".equals(mode)) {
					return config;
				}

				if("detailed".equals(mode)) {
					return DistributionStatisticConfig.builder()
						.percentilesHistogram(true)
						.build()
						.merge(config);
				}

				return DistributionStatisticConfig.builder()
					.serviceLevelObjectives(toNanos(buckets))
					.build()
					.merge(config);
			}
		};
	}

	private static Duration[] bucketsFor(String name) {
		if(REQUEST_METERS.contains(name)) {
			return REQUEST_BUCKETS;
		}

		if(SYNC_METERS.contains(name)) {
			return SYNC_BUCKETS;
		}

		return null;
	}

	private static double[] toNanos(Duration[] buckets) {
		var values = new double[buckets.length];
		for(var i = 0; i < buckets.length; i++) {
			values[i] = buckets[i].toNanos();
		}

		return values;
	}
}
