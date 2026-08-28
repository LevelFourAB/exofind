package se.l4.exofind.engine.api.v1alpha1.admin;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.api.auth.AuthContext;
import se.l4.exofind.engine.api.v1alpha1.admin.model.FieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexInfo;
import se.l4.exofind.engine.api.v1alpha1.admin.model.ReindexInfo;
import se.l4.exofind.engine.api.v1alpha1.admin.model.ReindexRequest;
import se.l4.exofind.engine.api.v1alpha1.admin.model.StringFieldDefinition;
import se.l4.exofind.engine.auth.Principal;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.LocalRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.settings.InMemorySearchSettingsStorage;
import se.l4.exofind.engine.index.settings.SearchSettings;
import se.l4.exofind.engine.index.state.LocalIndexerOwnership;
import se.l4.exofind.engine.index.state.NoopSyncProvider;
import se.l4.exofind.engine.reindex.ReindexJobs;
import se.l4.exofind.engine.reindex.ReindexNotFoundException;
import se.l4.exofind.engine.reindex.TestReindexJobs;
import se.l4.exofind.engine.storage.StorageMode;
import jakarta.ws.rs.core.UriInfo;

/**
 * Tests for the reindex endpoints - starting a job, reading where it stands,
 * cancelling it, and the creation flag that starts one with the generation.
 */
public class ReindexResourceTest {
	private static final Duration WAIT = Duration.ofSeconds(10);

	@TempDir
	Path storageDirectory;

	Indexes indexes;
	ReindexJobs reindexJobs;
	IndexResource indexResource;
	ReindexResource resource;
	UriInfo uriInfo;

	@BeforeEach
	void setup() throws IOException {
		var nodeState = new NodeState(true);
		nodeState.updateOwnership(true);

		var registry = new IndexRegistry(
			new LocalRegistryStorage(storageDirectory.resolve("registry.ef.bin")),
			Duration.ofMinutes(5)
		);

		indexes = new Indexes(
			nodeState,
			new NoopSyncProvider(),
			registry,
			new RegistryHints(registry, StorageMode.LOCAL),
			storageDirectory,
			OptionalInt.empty(),
			Duration.ofMinutes(5),
			Duration.ofMinutes(10),
			4,
			Duration.ofSeconds(10),
			0,
			Duration.ZERO,
			Optional.empty(),
			Optional.empty(),
			Duration.ofHours(24),
			Duration.ofHours(168),
			Duration.ofHours(1)
		);

		var auth = new AuthContext();
		auth.set(Principal.unchecked());

		reindexJobs = TestReindexJobs.create(nodeState, indexes, registry, storageDirectory);
		resource = new ReindexResource(reindexJobs, auth);
		indexResource = new IndexResource(
			indexes,
			auth,
			new LocalIndexerOwnership(),
			reindexJobs,
			new SearchSettings(
				new InMemorySearchSettingsStorage(),
				registry,
				new RegistryHints(registry, StorageMode.LOCAL),
				Duration.ofSeconds(10),
				Duration.ofMinutes(10)
			)
		);

		uriInfo = mock(UriInfo.class);
		when(uriInfo.getAbsolutePath())
			.thenReturn(URI.create("http://localhost/v1alpha1/admin/indexes/books"));
	}

	@AfterEach
	void cleanup() {
		indexes.close();
	}

	private IndexDefinition definition() {
		return new IndexDefinition(
			null,
			null,
			Map.of(
				"id",
				new StringFieldDefinition(
					true, null, null, null, null,
					new FieldDefinition.Filter(),
					null,
					null,
					null,
					null,
					null,
					null
				)
			),
			null,
			null,
			null
		);
	}

	private void create(String name) {
		var response = indexResource.put(name, null, null, false, uriInfo, definition());
		assertThat(response.getStatus(), is(201));
	}

	@Test
	public void aJobIsAcceptedAndItsStatusIsReadBack() throws Exception {
		create("books");
		create("books@2");

		var response = resource.reindex("books@2", new ReindexRequest(null, "manual"));
		assertThat(response.getStatus(), is(202));

		var accepted = (ReindexInfo) response.getEntity();
		assertThat(accepted.index(), is("books"));
		assertThat(accepted.target(), is("books@2"));
		assertThat(accepted.source(), is("books@1"));
		assertThat(accepted.promote(), is("manual"));

		awaitPhase("books", "ready");
	}

	@Test
	public void anIndexWithoutAJobAnswersNotFound() {
		create("books");

		assertThrows(
			ReindexNotFoundException.class,
			() -> resource.status("books")
		);
	}

	@Test
	public void cancellingAnswersTheClosedRecord() throws Exception {
		create("books");
		create("books@2");

		resource.reindex("books@2", new ReindexRequest(null, "manual"));
		awaitPhase("books", "ready");

		var cancelled = resource.cancel("books");
		assertThat(cancelled.phase(), is("cancelled"));
	}

	@Test
	public void theListingNamesEveryJob() throws Exception {
		create("books");
		create("books@2");

		resource.reindex("books@2", new ReindexRequest(null, "manual"));
		awaitPhase("books", "ready");

		var listed = resource.list();
		assertThat(
			listed.reindexes().stream().map(ReindexInfo::index).toList(),
			contains("books")
		);
	}

	@Test
	public void creatingAGenerationWithTheFlagStartsTheJob() throws Exception {
		create("books");

		var response = indexResource.put("books@2", null, "manual", false, uriInfo, definition());
		assertThat(response.getStatus(), is(201));

		awaitPhase("books", "ready");
		assertThat(resource.status("books").target(), is("books@2"));
	}

	@Test
	public void theFlagOnAnythingButANewGenerationIsRefused() {
		create("books");

		// The index already exists, so the request creates nothing to fill
		assertThrows(
			ValidationException.class,
			() -> indexResource.put("books", null, "auto", false, uriInfo, definition())
		);

		// Creating the index itself leaves nothing to read from
		assertThrows(
			ValidationException.class,
			() -> indexResource.put("shops", null, "auto", false, uriInfo, definition())
		);
	}

	@Test
	public void aFlagValueTheJobWouldRefuseCreatesNothing() {
		create("books");

		assertThrows(
			ValidationException.class,
			() -> indexResource.put("books@2", null, "always", false, uriInfo, definition())
		);

		// The refused request left no generation behind
		var info = (IndexInfo) indexResource.get("books").getEntity();
		assertThat(info.generations().size(), is(1));
	}

	@Test
	public void promotingTheReadyTargetFinishesTheJob() throws Exception {
		create("books");
		create("books@2");

		resource.reindex("books@2", new ReindexRequest(null, "manual"));
		awaitPhase("books", "ready");

		var promoted = (IndexInfo) indexResource.promote("books@2").getEntity();
		assertThat(promoted.live(), is(true));

		awaitPhase("books", "done");
	}

	private void awaitPhase(String index, String phase) throws Exception {
		var deadline = System.nanoTime() + WAIT.toNanos();
		while(true) {
			try {
				if(phase.equals(resource.status(index).phase())) {
					return;
				}
			} catch(ReindexNotFoundException e) {
				// Not written yet
			}

			if(System.nanoTime() > deadline) {
				throw new AssertionError(
					"The job of " + index + " did not reach " + phase + " within " + WAIT
				);
			}

			Thread.sleep(20);
		}
	}
}
