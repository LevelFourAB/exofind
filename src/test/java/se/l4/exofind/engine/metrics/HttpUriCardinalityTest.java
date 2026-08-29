package se.l4.exofind.engine.metrics;

import static org.hamcrest.MatcherAssert.assertThat;
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
 * A request that matches a path but not a method is reported under the path as
 * it arrived, which holds whatever the client put in the template. These cover
 * that the path stops being a tag value, and that nothing else about the meter
 * changes while it does.
 */
public class HttpUriCardinalityTest {
	private static final String LEAKED =
		"/v1alpha1/indexes/books/documents/a-client-chosen-key";

	@Test
	void testKeyInAnUnmatchedPathIsNotATagValue() {
		var tags = record("405", LEAKED);

		assertThat(values(tags, "uri"), hasItem(HttpUriCardinality.UNKNOWN));
		assertThat(values(tags, "uri"), not(hasItem(LEAKED)));
	}

	/**
	 * Prometheus refuses a meter whose tag keys differ from those already
	 * registered under the same name, and throws the refusal out of the
	 * request being measured. Dropping a key while collapsing the path would
	 * answer the request 500 rather than 405.
	 */
	@Test
	void testCollapsingThePathKeepsEveryOtherTag() {
		var tags = record("405", LEAKED);

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
	 * Every path a client can invent collapses onto one series rather than one
	 * each.
	 */
	@Test
	void testManyUnmatchedPathsShareOneSeries() {
		var registry = registryWithFilter();
		for(var i = 0; i < 50; i++) {
			timer(registry, "405", LEAKED + i);
		}

		var uris = new ArrayList<String>();
		for(var meter : registry.getMeters()) {
			uris.add(meter.getId().getTag("uri"));
		}

		assertThat(List.copyOf(uris), is(List.of(HttpUriCardinality.UNKNOWN)));
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
