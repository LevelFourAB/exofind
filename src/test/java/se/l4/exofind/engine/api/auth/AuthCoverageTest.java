package se.l4.exofind.engine.api.auth;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.lang.reflect.Method;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.api.ApiEndpoints;
import se.l4.exofind.engine.auth.Permission;
import jakarta.ws.rs.PathParam;

/**
 * That every endpoint says what reaching it takes.
 *
 * <p>The filter refuses a resource method carrying no {@link RequiresPermission},
 * so forgetting one closes an endpoint rather than opening it - but it closes it
 * at the first request rather than at the build, which is late to find out. This
 * finds it here instead, by looking at what was actually compiled rather than at
 * a list somebody has to remember to add to.
 */
public class AuthCoverageTest {
	/**
	 * Path parameter the filter reads the index from. An endpoint about one
	 * index that named it something else would be checked against no index at
	 * all.
	 */
	private static final String INDEX_PARAMETER = "name";

	@Test
	void everyEndpointSaysWhatItRequires() throws Exception {
		var missing = new ArrayList<String>();

		for(var endpoint : ApiEndpoints.endpoints()) {
			if(endpoint.getAnnotation(RequiresPermission.class) == null) {
				missing.add(ApiEndpoints.describe(endpoint));
			}
		}

		assertThat(missing, is(empty()));
	}

	@Test
	void everyEndpointAboutOneIndexNamesItTheWayTheFilterReadsIt() throws Exception {
		var wrong = new ArrayList<String>();

		for(var endpoint : ApiEndpoints.endpoints()) {
			var required = endpoint.getAnnotation(RequiresPermission.class);

			if(
				required == null
					|| required.anyIndex()
					|| required.value().scope() != Permission.Scope.INDEX
			) {
				continue;
			}

			if(!namesAnIndex(endpoint)) {
				wrong.add(ApiEndpoints.describe(endpoint));
			}
		}

		assertThat(wrong, is(empty()));
	}

	@Test
	void theEndpointsAreActuallyBeingLookedAt() throws Exception {
		// A scan that found nothing would pass the two tests above without them
		// having checked anything
		assertThat(ApiEndpoints.endpoints(), is(not(empty())));
	}

	private static boolean namesAnIndex(Method endpoint) {
		for(var annotations : endpoint.getParameterAnnotations()) {
			for(var annotation : annotations) {
				if(
					annotation instanceof PathParam parameter
						&& INDEX_PARAMETER.equals(parameter.value())
				) {
					return true;
				}
			}
		}

		return false;
	}
}
