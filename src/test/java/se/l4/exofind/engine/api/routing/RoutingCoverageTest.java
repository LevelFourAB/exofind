package se.l4.exofind.engine.api.routing;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.lang.reflect.Method;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.api.ApiEndpoints;
import jakarta.ws.rs.PathParam;

/**
 * That every endpoint that changes something says which node serves it.
 *
 * <p>The filter refuses a mutating resource method carrying no
 * {@link ServedBy} - one that forgot would quietly serve a write on a node
 * that should have passed it along - but it refuses at the first request
 * rather than at the build, which is late to find out. This finds it here
 * instead, from what was actually compiled.
 */
public class RoutingCoverageTest {
	@Test
	void everyMutatingEndpointSaysWhichNodeServesIt() throws Exception {
		var missing = new ArrayList<String>();

		for(var endpoint : ApiEndpoints.endpoints()) {
			if(ApiEndpoints.isRead(endpoint)) {
				continue;
			}

			if(endpoint.getAnnotation(ServedBy.class) == null) {
				missing.add(ApiEndpoints.describe(endpoint));
			}
		}

		assertThat(missing, is(empty()));
	}

	/**
	 * Reads are served wherever they land and the filter never asks them
	 * where they run, so an annotation on one would say something that is
	 * not checked - which reads as if it were.
	 */
	@Test
	void noReadCarriesAnAnnotationTheFilterIgnores() throws Exception {
		var misleading = new ArrayList<String>();

		for(var endpoint : ApiEndpoints.endpoints()) {
			if(ApiEndpoints.isRead(endpoint) && endpoint.getAnnotation(ServedBy.class) != null) {
				misleading.add(ApiEndpoints.describe(endpoint));
			}
		}

		assertThat(misleading, is(empty()));
	}

	/**
	 * Which node serves an indexer request depends on which index it is
	 * about, read from the {@code name} path parameter - an endpoint that
	 * named it something else would be routed against no index at all.
	 */
	@Test
	void everyIndexerEndpointNamesItsIndexTheWayTheFilterReadsIt() throws Exception {
		var wrong = new ArrayList<String>();

		for(var endpoint : ApiEndpoints.endpoints()) {
			var servedBy = endpoint.getAnnotation(ServedBy.class);

			if(
				servedBy != null
					&& servedBy.value() == ServedBy.Node.INDEXER
					&& !namesAnIndex(endpoint)
			) {
				wrong.add(ApiEndpoints.describe(endpoint));
			}
		}

		assertThat(wrong, is(empty()));
	}

	@Test
	void theEndpointsAreActuallyBeingLookedAt() throws Exception {
		// A scan that found nothing would pass the tests above without them
		// having checked anything
		assertThat(ApiEndpoints.endpoints(), is(not(empty())));
	}

	private static boolean namesAnIndex(Method endpoint) {
		for(var annotations : endpoint.getParameterAnnotations()) {
			for(var annotation : annotations) {
				if(
					annotation instanceof PathParam parameter
						&& ServedBy.INDEX_PARAMETER.equals(parameter.value())
				) {
					return true;
				}
			}
		}

		return false;
	}
}
