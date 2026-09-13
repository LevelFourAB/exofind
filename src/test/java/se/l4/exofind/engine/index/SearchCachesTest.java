package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Tests for how the search caches of a node are sized from settings.
 */
public class SearchCachesTest {
	@Test
	public void testSettingsAreParsedAsSizes() {
		var caches = new SearchCaches(500, Optional.of("8M"), "4M", 32, 64, "16M");

		assertThat(caches.queryCache(), notNullValue());
		assertThat(caches.termStates(), notNullValue());
		assertThat(caches.automata(), notNullValue());
		assertThat(caches.facetScopes(), notNullValue());
	}

	@Test
	public void testTheQueryCacheSizeFollowsTheHeapWhenUnset() {
		var caches = new SearchCaches(500, Optional.empty(), "4M", 32, 64, "16M");

		assertThat(caches.queryCache(), notNullValue());
	}

	@Test
	public void testASizeThatIsNotASizeIsRefused() {
		assertThrows(
			IllegalArgumentException.class,
			() -> new SearchCaches(500, Optional.of("lots"), "4M", 32, 64, "16M")
		);
	}

	@Test
	public void testANegativeQueryCountIsRefused() {
		assertThrows(
			IllegalArgumentException.class,
			() -> new SearchCaches(-1, 1 << 20, 1 << 20, 16, 16, 1 << 20)
		);
	}
}
