package se.l4.exofind.engine.health;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

/**
 * The health endpoints as a probe meets them: on a node checking credentials,
 * asked without one.
 *
 * <p>Whether each check says the right thing is tested against the check
 * itself. This is here for what only a running node shows - that the checks are
 * registered under the phase they were meant for, and that the endpoints are
 * reachable by something that holds no key.
 */
@QuarkusTest
@TestProfile(HealthEndpointTest.CheckedNode.class)
public class HealthEndpointTest {
	/**
	 * How long a node is given to read its registry before it is taken to be
	 * one that never will.
	 */
	private static final Duration READY_WITHIN = Duration.ofSeconds(10);

	/**
	 * A node checking credentials the way any deployment outside dev mode does,
	 * so that a health endpoint answering says something about health rather
	 * than about authentication being off.
	 */
	public static class CheckedNode implements QuarkusTestProfile {
		@Override
		public Map<String, String> getConfigOverrides() {
			Path directory;
			try {
				directory = Files.createTempDirectory("exofind-health-test");
			} catch(IOException e) {
				throw new IllegalStateException("Could not make a directory to run in", e);
			}

			return Map.of(
				"exofind.storage.mode", "local",
				"local.storage.directory", directory.toString(),
				"exofind.auth.mode", "keys",
				"exofind.auth.root-key", "exok_root_health_test"
			);
		}
	}

	@Test
	void testTheNodeReportsItselfLive() {
		given()
			.get("/q/health/live")
			.then()
			.statusCode(200)
			.body("status", is("UP"))
			.body("checks.name", hasItem("index-refresh"));
	}

	@Test
	void testTheNodeBecomesReadyOnceItHasReadTheRegistry() throws InterruptedException {
		var deadline = System.nanoTime() + READY_WITHIN.toNanos();

		while(true) {
			var response = given()
				.get("/q/health/ready")
				.then()
				.extract();

			if(response.statusCode() == 200) {
				assertThat(response.jsonPath().getString("status"), is("UP"));
				assertThat(
					response.jsonPath().getList("checks.name"),
					hasItem("index-registry")
				);
				return;
			}

			if(System.nanoTime() > deadline) {
				fail(
					"Node never became ready, last answer was " + response.statusCode()
						+ " " + response.asString()
				);
			}

			Thread.sleep(100);
		}
	}
}
