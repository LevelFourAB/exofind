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
 * <p>Requests that reach a route are already counted under the route's
 * template, so the bound only ever reaches a request that did not. Those are
 * collapsed into a single {@code UNKNOWN} series, which keeps the count and
 * the latency of such requests while spending one series on all of them.
 */
@Dependent
public class HttpUriCardinality {
	/**
	 * Value the {@code uri} tag is given once it is no longer counted under
	 * its own name.
	 */
	static final String UNKNOWN = "UNKNOWN";

	private static final String HTTP_SERVER_REQUESTS = "http.server.requests";
	private static final String URI = "uri";
	private static final String STATUS = "status";
	private static final String METHOD_NOT_ALLOWED = "405";

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
	 * Collapse the {@code uri} of a request that matched a path but not a
	 * method.
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

				/*
				 * A request whose path matches a resource but whose method does
				 * not is answered 405 before the route is resolved, and Quarkus
				 * reports the path as it arrived. That path holds whatever the
				 * client put in the template - a document key is unbounded and
				 * client-chosen - so leaving it alone lets any client mint
				 * series without limit.
				 */
				if(!METHOD_NOT_ALLOWED.equals(id.getTag(STATUS))) {
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
