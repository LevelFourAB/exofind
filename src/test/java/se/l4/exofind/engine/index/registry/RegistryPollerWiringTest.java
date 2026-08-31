package se.l4.exofind.engine.index.registry;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.index.settings.SearchSettings;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Everything on a node that works from the registry reaches
 * {@link RegistryPoller} through the container. A part the container stops
 * handing over stops being refreshed while the rest of the node keeps running,
 * so the wiring is asserted here instead of being noticed later as staleness.
 */
@Isolated
@QuarkusTest
@TestProfile(RegistryPollerWiringTest.Node.class)
public class RegistryPollerWiringTest {
	public static class Node implements QuarkusTestProfile {
		@Override
		public Map<String, String> getConfigOverrides() {
			Path directory;
			try {
				directory = Files.createTempDirectory("exofind-poller-test");
			} catch(IOException e) {
				throw new IllegalStateException("Could not make a directory to run in", e);
			}

			return Map.of(
				"exofind.storage.mode", "local",
				"exofind.storage.local.directory", directory.toString()
			);
		}
	}

	@Inject
	Instance<RegistryPoller.Listener> listeners;

	@Test
	public void testOpenIndexesAreRefreshedFromThePoller() {
		assertThat(listeners.stream().anyMatch(Indexes.class::isInstance), is(true));
	}

	@Test
	public void testSearchSettingsAreRefreshedFromThePoller() {
		assertThat(listeners.stream().anyMatch(SearchSettings.class::isInstance), is(true));
	}
}
