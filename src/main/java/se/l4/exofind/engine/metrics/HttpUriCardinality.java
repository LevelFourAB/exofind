package se.l4.exofind.engine.metrics;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Bounds the number of {@code uri} values the HTTP server meter can take.
 *
 * <p>Requests that reach a route are counted under the route's template.
 * Requests that reach none are marked by {@link UnroutedRequests} and
 * collapsed here into a single {@code UNKNOWN} series, which keeps the count
 * and the latency of such requests while spending one series on all of them.
 * The bound of {@code exofind.metrics.http.max-uri-tags} catches anything
 * that gets past both.
 */
@Dependent
public class HttpUriCardinality {
	/**
	 * Value the {@code uri} tag is given once it is no longer counted under
	 * its own name.
	 */
	static final String UNKNOWN = "UNKNOWN";

	/**
	 * The template {@link UnroutedRequests} gives a request that no route
	 * matched. No route has it. A client that sends it as a path is
	 * collapsed with the rest.
	 */
	static final String UNROUTED = "/{unrouted}";

	private static final String HTTP_SERVER_REQUESTS = "http.server.requests";
	private static final String URI = "uri";

	/**
	 * Replaces the value of {@code uri} and leaves every other tag alone.
	 *
	 * <p>Prometheus refuses a meter whose tag keys differ from those of a
	 * meter already registered under the same name, and the refusal is thrown
	 * out of the request being measured rather than logged. Dropping a key
	 * here would turn the request that triggered it into a 500.
	 */
	private static final MeterFilter COLLAPSE =
		MeterFilter.replaceTagValues(URI, uri -> UNKNOWN);

	/**
	 * Collapse the {@code uri} of a request that no route matched.
	 */
	@Produces
	@Singleton
	public MeterFilter unresolvedUris() {
		return new MeterFilter() {
			@Override
			public Meter.Id map(Meter.Id id) {
				if(!HTTP_SERVER_REQUESTS.equals(id.getName())) {
					return id;
				}

				if(!UNROUTED.equals(id.getTag(URI))) {
					return id;
				}

				return COLLAPSE.map(id);
			}
		};
	}

	/**
	 * Collapse the {@code uri} of every request past {@code maxUriTags}
	 * distinct values, counting the ones already accepted.
	 */
	@Produces
	@Singleton
	public MeterFilter boundedUris(
		@ConfigProperty(
			name = "exofind.metrics.http.max-uri-tags",
			defaultValue = "200"
		) int maxUriTags
	) {
		if(maxUriTags <= 0) {
			return new MeterFilter() {
				@Override
				public MeterFilterReply accept(Meter.Id id) {
					return MeterFilterReply.NEUTRAL;
				}
			};
		}

		return MeterFilter.maximumAllowableTags(
			HTTP_SERVER_REQUESTS,
			URI,
			maxUriTags,
			MeterFilter.replaceTagValues(URI, uri -> UNKNOWN)
		);
	}
}
