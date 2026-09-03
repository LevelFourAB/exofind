package se.l4.exofind.engine.api.v1alpha1.admin;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ListIterable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.api.v1alpha1.admin.model.RegistryRepairRequest;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.InMemoryRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryAudit;
import se.l4.exofind.engine.index.registry.RegistryAuditReport;
import se.l4.exofind.engine.index.registry.RegistryAuditUnavailableException;
import se.l4.exofind.engine.index.registry.RegistryRepairResult;
import se.l4.exofind.engine.storage.StorageMode;
import jakarta.enterprise.inject.Instance;

/**
 * Tests for the registry audit endpoints - what the report and the repair
 * answer as, and that a node storing locally refuses both.
 */
public class RegistryResourceTest {
	InMemoryRegistryStorage storage;
	IndexRegistry registry;
	RecordingAudit audit;

	static class RecordingAudit implements RegistryAudit {
		RegistryAuditReport report;
		RegistryRepairResult result;
		Boolean promoteNewest;
		ListIterable<IndexName> restore;

		@Override
		public RegistryAuditReport audit() {
			return report;
		}

		@Override
		public RegistryRepairResult repair(boolean promoteNewest, ListIterable<IndexName> restore) {
			this.promoteNewest = promoteNewest;
			this.restore = restore;
			return result;
		}
	}

	@BeforeEach
	void setup() {
		storage = new InMemoryRegistryStorage();
		registry = new IndexRegistry(storage, Duration.ofMinutes(5));
		audit = new RecordingAudit();
	}

	@SuppressWarnings("unchecked")
	private RegistryResource resource(StorageMode mode) {
		Instance<RegistryAudit> instance = mock(Instance.class);
		when(instance.get()).thenReturn(audit);

		return new RegistryResource(mode, instance, registry);
	}

	@Test
	public void testLocalModeRefusesTheAudit() {
		var resource = resource(StorageMode.LOCAL);

		assertThrows(RegistryAuditUnavailableException.class, () -> resource.audit());
		assertThrows(
			RegistryAuditUnavailableException.class,
			() -> resource.repair(null)
		);
	}

	@Test
	public void testAuditIsAnsweredAsRead() {
		audit.report = new RegistryAuditReport(
			RegistryAuditReport.Registry.ABSENT,
			Lists.immutable.of(
				new RegistryAuditReport.AuditedIndex(
					"books",
					false,
					null,
					"2",
					null,
					Lists.immutable.of(
						new RegistryAuditReport.AuditedGeneration(
							"1", false, RegistryAuditReport.Stored.INCOMPLETE, null
						),
						new RegistryAuditReport.AuditedGeneration(
							"2", false, RegistryAuditReport.Stored.SYNCED, null
						)
					)
				),
				new RegistryAuditReport.AuditedIndex(
					"movies",
					false,
					null,
					null,
					Instant.parse("2026-09-03T10:15:00Z"),
					Lists.immutable.of(
						new RegistryAuditReport.AuditedGeneration(
							"1", false, RegistryAuditReport.Stored.SYNCED,
							Instant.parse("2026-09-01T08:00:00Z")
						)
					)
				)
			),
			Lists.immutable.of("Bad.Name")
		);

		var response = resource(StorageMode.OBJECT).audit();

		assertThat(response.registry(), is(RegistryAuditReport.Registry.ABSENT));
		assertThat(response.unusable(), contains("Bad.Name"));

		var books = response.indexes().get(0);
		assertThat(books.name(), is("books"));
		assertThat(books.registered(), is(false));
		assertThat(books.live(), is(nullValue()));
		assertThat(books.proposedLive(), is("2"));
		assertThat(books.removedAt(), is(nullValue()));
		assertThat(books.generations().get(1).stored(), is(RegistryAuditReport.Stored.SYNCED));
		assertThat(books.generations().get(1).removedAt(), is(nullValue()));

		// A deleted index says when, as a timestamp a client can read
		var movies = response.indexes().get(1);
		assertThat(movies.removedAt(), is("2026-09-03T10:15:00Z"));
		assertThat(movies.generations().get(0).removedAt(), is("2026-09-01T08:00:00Z"));
	}

	@Test
	public void testRepairWithoutABodyDoesNotPromote() {
		audit.result = new RegistryRepairResult(
			Lists.immutable.empty(),
			Lists.immutable.empty(),
			Lists.immutable.empty(),
			Lists.immutable.empty()
		);

		var response = resource(StorageMode.OBJECT).repair(null);

		assertThat(audit.promoteNewest, is(false));
		assertThat(audit.restore, emptyIterable());
		assertThat(response.createdIndexes(), emptyIterable());
		assertThat(response.restored(), emptyIterable());
	}

	@Test
	public void testRepairAnswersWithWhatChanged() {
		audit.result = new RegistryRepairResult(
			Lists.immutable.of("books"),
			Lists.immutable.of("books@1", "books@2"),
			Lists.immutable.of("books@2"),
			Lists.immutable.of("books")
		);

		var response = resource(StorageMode.OBJECT)
			.repair(new RegistryRepairRequest(true, List.of("books", "movies@2")));

		assertThat(audit.promoteNewest, is(true));
		assertThat(audit.restore, contains(IndexName.of("books"), IndexName.of("movies", "2")));
		assertThat(response.createdIndexes(), contains("books"));
		assertThat(response.addedGenerations(), contains("books@1", "books@2"));
		assertThat(response.promoted(), contains("books@2"));
		assertThat(response.restored(), contains("books"));
	}

	/**
	 * A name that could never be an index or a generation is refused before
	 * the storage is touched.
	 */
	@Test
	public void testRepairRefusesAnUnusableRestoreName() {
		assertThrows(
			ValidationException.class,
			() -> resource(StorageMode.OBJECT)
				.repair(new RegistryRepairRequest(false, List.of("books@1@2")))
		);

		assertThat(audit.restore, is(nullValue()));
	}

	/**
	 * A repair that changed the registry is served from this node at once
	 * rather than at its next refresh.
	 */
	@Test
	public void testRepairThatChangedSomethingRefreshesTheCopy() {
		audit.result = new RegistryRepairResult(
			Lists.immutable.of("books"),
			Lists.immutable.of("books@1"),
			Lists.immutable.empty(),
			Lists.immutable.empty()
		);

		resource(StorageMode.OBJECT).repair(null);

		assertThat(storage.reads, is(1));
	}
}
