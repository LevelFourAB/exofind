package se.l4.exofind.engine.api.v1alpha1.admin;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
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
import se.l4.exofind.engine.auth.ForbiddenException;
import se.l4.exofind.engine.auth.Grant;
import se.l4.exofind.engine.auth.Key;
import se.l4.exofind.engine.auth.Permission;
import se.l4.exofind.engine.auth.Principal;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.IndexSourceNotKeptException;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.LocalRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.settings.InMemorySearchSettingsStorage;
import se.l4.exofind.engine.index.settings.SearchSettings;
import se.l4.exofind.engine.index.state.LocalIndexerOwnership;
import se.l4.exofind.engine.index.state.NoopSyncProvider;
import se.l4.exofind.engine.reindex.ReindexInProgressException;
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
	SearchSettings searchSettings;
	IndexResource indexResource;
	ReindexResource resource;
	AuthContext auth;
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

		auth = new AuthContext();
		auth.set(Principal.unchecked());

		reindexJobs = TestReindexJobs.create(nodeState, indexes, registry, storageDirectory);
		searchSettings = new SearchSettings(
			new InMemorySearchSettingsStorage(),
			registry,
			new RegistryHints(registry, StorageMode.LOCAL),
			Duration.ofSeconds(10),
			Duration.ofMinutes(10)
		);

		resource = new ReindexResource(reindexJobs, auth);
		indexResource = new IndexResource(
			indexes,
			auth,
			new LocalIndexerOwnership(),
			reindexJobs,
			searchSettings
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
		return definition(null, "id");
	}

	/**
	 * A definition of one field, which is the primary key.
	 *
	 * @param source
	 *   how much of a document to keep, or {@code null} for the default
	 * @param key
	 *   name of the key field
	 */
	private IndexDefinition definition(IndexDefinition.Source source, String key) {
		return new IndexDefinition(
			source,
			null,
			Map.of(
				key,
				new StringFieldDefinition(
					null, true, null, null, null, null,
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
			null,
			null
		);
	}

	private void create(String name) {
		var response = indexResource.put(name, null, null, false, uriInfo, definition());
		assertThat(response.getStatus(), is(201));
	}

	/**
	 * Answer the rest of the test as a key granted these permissions over every
	 * index, in place of the unchecked principal the setup uses.
	 */
	private void answerAs(Permission... permissions) {
		auth.set(
			Principal.of(
				new Key(
					"test",
					"",
					"",
					Lists.immutable.of(
						new Grant(Sets.immutable.of(permissions), Lists.immutable.of("*"))
					),
					Instant.now(),
					null
				)
			)
		);
	}

	@Test
	public void aJobIsAcceptedAndItsStatusIsReadBack() throws Exception {
		create("books");
		create("books@2");

		var response = indexResource.reindex("books@2", new ReindexRequest(null, "manual"));
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

		indexResource.reindex("books@2", new ReindexRequest(null, "manual"));
		awaitPhase("books", "ready");

		var cancelled = resource.cancel("books");
		assertThat(cancelled.phase(), is("cancelled"));
	}

	@Test
	public void theListingNamesEveryJob() throws Exception {
		create("books");
		create("books@2");

		indexResource.reindex("books@2", new ReindexRequest(null, "manual"));
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

	/**
	 * A definition whose key the replay could not match is refused before the
	 * generation exists. A generation left behind would be refused the flag on
	 * every repeat, so the caller could not send the corrected request under
	 * the same name.
	 */
	@Test
	public void aReindexTheJobRefusesCreatesNoGeneration() {
		create("books");

		for(var attempt = 0; attempt < 2; attempt++) {
			assertThrows(
				ValidationException.class,
				() -> indexResource.put(
					"books@2", null, "manual", false, uriInfo, definition(null, "code")
				)
			);

			var info = (IndexInfo) indexResource.get("books").getEntity();
			assertThat(info.generations().size(), is(1));
		}
	}

	/**
	 * A live generation that keeps no copy of its documents has nothing to fill
	 * a new generation from, and the flag is refused before anything is created.
	 */
	@Test
	public void aReindexFromASourceThatKeepsNoDocumentsCreatesNoGeneration() {
		indexResource.put(
			"books", null, null, false, uriInfo, definition(IndexDefinition.Source.NONE, "id")
		);

		assertThrows(
			IndexSourceNotKeptException.class,
			() -> indexResource.put(
				"books@2", null, "manual", false, uriInfo,
				definition(IndexDefinition.Source.NONE, "id")
			)
		);

		var info = (IndexInfo) indexResource.get("books").getEntity();
		assertThat(info.generations().size(), is(1));
	}

	/**
	 * A job refused after the generation was created takes the generation with
	 * it, which is what a job refused by a race with another node looks like.
	 */
	@Test
	public void aJobRefusedAfterTheCreateTakesTheGenerationWithIt() {
		create("books");

		var refusing = spy(reindexJobs);
		doThrow(new ReindexInProgressException("books"))
			.when(refusing).start(any(), any(), any());

		var resource = new IndexResource(
			indexes,
			auth,
			new LocalIndexerOwnership(),
			refusing,
			searchSettings
		);

		assertThrows(
			ReindexInProgressException.class,
			() -> resource.put("books@2", null, "manual", false, uriInfo, definition())
		);

		var info = (IndexInfo) indexResource.get("books").getEntity();
		assertThat(info.generations().size(), is(1));
	}

	@Test
	public void promotingTheReadyTargetFinishesTheJob() throws Exception {
		create("books");
		create("books@2");

		indexResource.reindex("books@2", new ReindexRequest(null, "manual"));
		awaitPhase("books", "ready");

		var promoted = (IndexInfo) indexResource.promote("books@2").getEntity();
		assertThat(promoted.live(), is(true));

		awaitPhase("books", "done");
	}

	/**
	 * A job left on automatic promotion promotes the generation it filled, which
	 * is what `indexes.promote` is about. Without it the reindex permission
	 * alone would change what the index answers for.
	 */
	@Test
	public void startingAJobThatPromotesNeedsThePromotePermission() {
		create("books");
		create("books@2");

		answerAs(Permission.INDEXES_REINDEX);

		assertThrows(ForbiddenException.class, () -> indexResource.reindex("books@2", null));
		assertThrows(
			ForbiddenException.class,
			() -> indexResource.reindex("books@2", new ReindexRequest(null, "auto"))
		);

		// Nothing was started by either refusal
		assertThrows(ReindexNotFoundException.class, () -> resource.status("books"));
	}

	/**
	 * Manual promotion leaves the promote to a later request, which is checked
	 * for `indexes.promote` of its own.
	 */
	@Test
	public void startingAJobThatPromotesNothingNeedsOnlyTheReindexPermission() throws Exception {
		create("books");
		create("books@2");

		answerAs(Permission.INDEXES_REINDEX, Permission.INDEXES_READ);

		var response = indexResource.reindex("books@2", new ReindexRequest(null, "manual"));
		assertThat(response.getStatus(), is(202));

		awaitPhase("books", "ready");
	}

	@Test
	public void creatingAGenerationThatPromotesItselfNeedsThePromotePermission() {
		create("books");

		answerAs(Permission.INDEXES_WRITE, Permission.INDEXES_REINDEX, Permission.INDEXES_READ);

		assertThrows(
			ForbiddenException.class,
			() -> indexResource.put("books@2", null, "auto", false, uriInfo, definition())
		);

		// The refused request created no generation
		var info = (IndexInfo) indexResource.get("books").getEntity();
		assertThat(info.generations().size(), is(1));
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
