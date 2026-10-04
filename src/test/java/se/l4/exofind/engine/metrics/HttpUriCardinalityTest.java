package se.l4.exofind.engine.metrics;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * A request that no route matched is marked by {@link UnroutedRequests}. These
 * cover that the mark is collapsed into one series, and that nothing else
 * about the meter changes while it is. {@link HttpUriCardinalityNodeTest}
 * covers which requests are marked.
 */
public class HttpUriCardinalityTest {
	@Test
	void testAnUnroutedRequestIsUnknown() {
		var tags = record("405", HttpUriCardinality.UNROUTED);

		assertThat(values(tags, "uri"), hasItem(HttpUriCardinality.UNKNOWN));
		assertThat(values(tags, "uri"), not(hasItem(HttpUriCardinality.UNROUTED)));
	}

	/**
	 * Prometheus refuses a meter whose tag keys differ from those already
	 * registered under the same name, and throws the refusal out of the
	 * request being measured. Dropping a key while collapsing the path would
	 * answer the request 500 rather than 405.
	 */
	@Test
	void testCollapsingThePathKeepsEveryOtherTag() {
		var tags = record("405", HttpUriCardinality.UNROUTED);

		assertThat(values(tags, "method"), hasItem("GET"));
		assertThat(values(tags, "status"), hasItem("405"));
		assertThat(values(tags, "outcome"), hasItem("CLIENT_ERROR"));
	}

	@Test
	void testAMatchedRouteKeepsItsTemplate() {
		var template = "/v1alpha1/indexes/{name}/documents/{key}";
		var tags = record("200", template);

		assertThat(values(tags, "uri"), hasItem(template));
	}

	/**
	 * Unrouted requests of every status share one series per status rather
	 * than one per path.
	 */
	@Test
	void testUnroutedRequestsOfEveryStatusAreUnknown() {
		var registry = registryWithFilter();
		for(var status : List.of("405", "406", "413", "415", "500")) {
			timer(registry, status, HttpUriCardinality.UNROUTED);
		}

		var uris = new ArrayList<String>();
		for(var meter : registry.getMeters()) {
			uris.add(meter.getId().getTag("uri"));
		}

		assertThat(List.copyOf(uris), everyItem(is(HttpUriCardinality.UNKNOWN)));
	}

	private static List<Tag> record(String status, String uri) {
		var registry = registryWithFilter();
		timer(registry, status, uri);

		var tags = new ArrayList<Tag>();
		for(var meter : registry.getMeters()) {
			meter.getId().getTagsAsIterable().forEach(tags::add);
		}

		return tags;
	}

	private static void timer(SimpleMeterRegistry registry, String status, String uri) {
		registry.timer(
			"http.server.requests",
			Tags.of(
				"method", "GET",
				"outcome", "200".equals(status) ? "SUCCESS" : "CLIENT_ERROR",
				"status", status,
				"uri", uri
			)
		);
	}

	private static SimpleMeterRegistry registryWithFilter() {
		var registry = new SimpleMeterRegistry();
		registry.config().meterFilter(new HttpUriCardinality().unresolvedUris());
		return registry;
	}

	private static List<String> values(List<Tag> tags, String key) {
		var values = new ArrayList<String>();
		for(var tag : tags) {
			if(tag.getKey().equals(key)) {
				values.add(tag.getValue());
			}
		}

		return values;
	}
}
