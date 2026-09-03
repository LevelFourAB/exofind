package se.l4.exofind.engine.index.registry;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.apache.commons.lang3.RandomStringUtils;
import org.eclipse.collections.api.factory.Lists;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.state.LocalCopy;
import se.l4.exofind.engine.index.state.ObjectStorageIndexRemovals;
import se.l4.exofind.engine.index.state.TestObjectStorage;
import se.l4.exofind.engine.storage.ObjectStorage;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Tests for auditing the registry against what the bucket holds and repairing
 * it from there.
 */
public class ObjectStorageRegistryAuditTest {
	ObjectStorage storage;
	ObjectStorageRegistryStorage registryStorage;
	ObjectStorageIndexRemovals removals;
	ObjectStorageRegistryAudit audit;

	@BeforeEach
	void setup() throws IOException {
		storage = new ObjectStorage(
			TestObjectStorage.url(),
			TestObjectStorage.ACCESS_KEY,
			TestObjectStorage.SECRET_KEY,
			Optional.empty(),
			TestObjectStorage.BUCKET,
			Optional.of("test" + RandomStringUtils.insecure().nextAlphabetic(10)),
			false
		);

		registryStorage = new ObjectStorageRegistryStorage(storage);
		removals = new ObjectStorageIndexRemovals(storage);
		audit = new ObjectStorageRegistryAudit(storage, registryStorage, removals);
	}

	/**
	 * Put an object under the path the indexes live in, standing in for what
	 * a push leaves behind.
	 */
	private void putIndexObject(String path, String contents) {
		storage.client().putObject(
			PutObjectRequest.builder()
				.bucket(storage.bucket())
				.key(storage.indexesPath() + "/" + path)
				.build(),
			RequestBody.fromString(contents)
		);
	}

	private void putManifest(String index, String generation) {
		putIndexObject(index + "/" + generation + "/" + LocalCopy.MANIFEST_FILE, "manifest");
		putIndexObject(index + "/" + generation + "/_0.cfs", "segment");
	}

	private void putIncomplete(String index, String generation) {
		putIndexObject(index + "/" + generation + "/_0.cfs", "segment");
	}

	private void putRegistry(IndexRegistryStore store) throws IOException {
		var current = registryStorage.read(null);
		registryStorage.write(
			store,
			current instanceof RegistryStorage.Read.Loaded loaded ? loaded.version() : null
		);
	}

	private void corruptRegistry() {
		storage.client().putObject(
			PutObjectRequest.builder()
				.bucket(storage.bucket())
				.key(storage.rootObject("registry/indexes.ef.bin"))
				.build(),
			RequestBody.fromBytes("not a registry".getBytes(StandardCharsets.UTF_8))
		);
	}

	private static IndexEntry entry(String name, String live, String... generations) {
		var entry = IndexEntry.newBuilder().setName(name);
		for(var generation : generations) {
			entry.addGenerations(GenerationEntry.newBuilder().setName(generation));
		}

		if(live != null) {
			entry.setLive(live);
		}

		return entry.build();
	}

	@Test
	public void testAgreementReportsEverythingRegistered() throws IOException {
		putManifest("books", "1");
		putRegistry(
			IndexRegistryStore.newBuilder()
				.addIndexes(entry("books", "1", "1"))
				.build()
		);

		var report = audit.audit();

		assertThat(report.registry(), is(RegistryAuditReport.Registry.PRESENT));
		assertThat(report.indexes().collect(RegistryAuditReport.AuditedIndex::name), contains("books"));

		var books = report.indexes().getFirst();
		assertThat(books.registered(), is(true));
		assertThat(books.live(), is("1"));
		assertThat(books.proposedLive(), is(nullValue()));
		assertThat(books.generations(), contains(
			new RegistryAuditReport.AuditedGeneration("1", true, RegistryAuditReport.Stored.SYNCED, null)
		));

		assertThat(audit.repair(true, Lists.immutable.empty()).isEmpty(), is(true));
	}

	/**
	 * A deployment whose registry object is gone still holds every index, and
	 * the repair writes a registry naming them again.
	 */
	@Test
	public void testLostRegistryIsRebuilt() throws IOException {
		putManifest("books", "1");
		putManifest("books", "2");
		putManifest("movies", "1");

		var report = audit.audit();
		assertThat(report.registry(), is(RegistryAuditReport.Registry.ABSENT));
		assertThat(
			report.indexes().collect(RegistryAuditReport.AuditedIndex::proposedLive),
			contains("2", "1")
		);

		var result = audit.repair(true, Lists.immutable.empty());
		assertThat(result.createdIndexes(), contains("books", "movies"));
		assertThat(result.addedGenerations(), contains("books@1", "books@2", "movies@1"));
		assertThat(result.promoted(), contains("books@2", "movies@1"));

		var indexes = RegistryCodec.fromStored(
			((RegistryStorage.Read.Loaded) registryStorage.read(null)).indexes()
		);

		assertThat(indexes.collect(RegisteredIndex::name), contains("books", "movies"));
		assertThat(indexes.getFirst().live(), is("2"));
		assertThat(indexes.getLast().live(), is("1"));
	}

	/**
	 * Without being asked to promote, a rebuilt index answers for nothing
	 * until an operator promotes a generation.
	 */
	@Test
	public void testRepairWithoutPromotingLeavesIndexesUnanswering() throws IOException {
		putManifest("books", "1");

		var result = audit.repair(false, Lists.immutable.empty());
		assertThat(result.createdIndexes(), contains("books"));
		assertThat(result.promoted(), emptyIterable());

		var indexes = RegistryCodec.fromStored(
			((RegistryStorage.Read.Loaded) registryStorage.read(null)).indexes()
		);

		assertThat(indexes.getFirst().live(), is(nullValue()));
	}

	@Test
	public void testCorruptRegistryIsRebuilt() throws IOException {
		putManifest("books", "1");
		corruptRegistry();

		var report = audit.audit();
		assertThat(report.registry(), is(RegistryAuditReport.Registry.CORRUPT));

		var result = audit.repair(true, Lists.immutable.empty());
		assertThat(result.createdIndexes(), contains("books"));

		var read = registryStorage.read(null);
		var indexes = RegistryCodec.fromStored(
			((RegistryStorage.Read.Loaded) read).indexes()
		);

		assertThat(indexes.collect(RegisteredIndex::name), contains("books"));
	}

	/**
	 * A generation the registry does not name is registered onto the entry
	 * that is already there, which keeps everything else about the entry -
	 * what it answers for, the feature names it carries - exactly as stored.
	 */
	@Test
	public void testUnregisteredGenerationJoinsItsIndex() throws IOException {
		putManifest("books", "1");
		putManifest("books", "2");
		putRegistry(
			IndexRegistryStore.newBuilder()
				.addIndexes(
					entry("books", "1", "1").toBuilder()
						.addRequiredFeatures("future.thing")
						.build()
				)
				.build()
		);

		var result = audit.repair(true, Lists.immutable.empty());
		assertThat(result.createdIndexes(), emptyIterable());
		assertThat(result.addedGenerations(), contains("books@2"));
		assertThat(result.promoted(), emptyIterable());

		var stored = ((RegistryStorage.Read.Loaded) registryStorage.read(null)).indexes();
		var books = stored.getIndexes(0);

		assertThat(books.getLive(), is("1"));
		assertThat(books.getRequiredFeaturesList(), contains("future.thing"));
		assertThat(books.getGenerationsCount(), is(2));
	}

	/**
	 * A prefix without a manifest has never finished a push, so it is
	 * reported and never registered - there is nothing there to serve from.
	 */
	@Test
	public void testPrefixWithoutManifestIsReportedNotRegistered() throws IOException {
		putManifest("books", "1");
		putIncomplete("books", "2");
		putIncomplete("movies", "1");

		var report = audit.audit();

		var books = report.indexes().getFirst();
		assertThat(books.generations(), contains(
			new RegistryAuditReport.AuditedGeneration("1", false, RegistryAuditReport.Stored.SYNCED, null),
			new RegistryAuditReport.AuditedGeneration("2", false, RegistryAuditReport.Stored.INCOMPLETE, null)
		));

		var movies = report.indexes().getLast();
		assertThat(movies.proposedLive(), is(nullValue()));

		var result = audit.repair(true, Lists.immutable.empty());
		assertThat(result.createdIndexes(), contains("books"));
		assertThat(result.addedGenerations(), contains("books@1"));
	}

	/**
	 * A registered generation with nothing behind it is a finding rather
	 * than something a repair can fix.
	 */
	@Test
	public void testRegisteredGenerationMissingFromStorageIsReported() throws IOException {
		putManifest("books", "1");
		putRegistry(
			IndexRegistryStore.newBuilder()
				.addIndexes(entry("books", "1", "1", "2"))
				.build()
		);

		var report = audit.audit();
		assertThat(report.indexes().getFirst().generations(), contains(
			new RegistryAuditReport.AuditedGeneration("1", true, RegistryAuditReport.Stored.SYNCED, null),
			new RegistryAuditReport.AuditedGeneration("2", true, RegistryAuditReport.Stored.MISSING, null)
		));

		assertThat(audit.repair(true, Lists.immutable.empty()).isEmpty(), is(true));
	}

	/**
	 * Promoting the newest generation is by number, so a generation named by
	 * hand - which says nothing about age - is never picked over one that
	 * counts.
	 */
	@Test
	public void testHandNamedGenerationsAreNotPromoted() throws IOException {
		putManifest("books", "alpha");
		putManifest("books", "2");

		var report = audit.audit();
		assertThat(report.indexes().getFirst().proposedLive(), is("2"));

		putManifest("movies", "alpha");

		assertThat(audit.audit().indexes().getLast().proposedLive(), is(nullValue()));
	}

	/**
	 * A prefix whose name no index or generation may carry is reported as
	 * unusable and never registered.
	 */
	@Test
	public void testUnusableNamesAreReportedNotRegistered() throws IOException {
		putManifest("books", "1");
		putIndexObject("Bad.Name/1/" + LocalCopy.MANIFEST_FILE, "manifest");
		putIndexObject("books2/Bad.Gen/" + LocalCopy.MANIFEST_FILE, "manifest");

		var report = audit.audit();
		assertThat(report.unusable(), contains("Bad.Name", "books2/Bad.Gen"));
		assertThat(
			report.indexes().collect(RegistryAuditReport.AuditedIndex::name),
			contains("books", "books2")
		);

		var result = audit.repair(true, Lists.immutable.empty());
		assertThat(result.createdIndexes(), contains("books"));
	}

	/**
	 * A repair that loses the race against another write is rebuilt on top
	 * of what the other wrote rather than overwriting it.
	 */
	@Test
	public void testRepairRebuildsOnConcurrentChange() throws IOException {
		putManifest("books", "1");

		var refusingStorage = new RegistryStorage() {
			boolean refused;

			@Override
			public Read read(String knownVersion) throws IOException {
				return registryStorage.read(knownVersion);
			}

			@Override
			public String write(IndexRegistryStore indexes, String expectedVersion)
				throws IOException {
				if(!refused) {
					refused = true;

					// Another node creates an index between the read and the write
					putRegistry(
						IndexRegistryStore.newBuilder()
							.addIndexes(entry("movies", null, "1"))
							.build()
					);

					return null;
				}

				return registryStorage.write(indexes, expectedVersion);
			}
		};

		var racing = new ObjectStorageRegistryAudit(storage, refusingStorage, removals);
		var result = racing.repair(false, Lists.immutable.empty());
		assertThat(result.createdIndexes(), contains("books"));

		var indexes = RegistryCodec.fromStored(
			((RegistryStorage.Read.Loaded) registryStorage.read(null)).indexes()
		);

		assertThat(indexes.collect(RegisteredIndex::name), contains("books", "movies"));
	}

	@Test
	public void testCorruptContentsAreReadAsCorrupt() throws IOException {
		corruptRegistry();

		var read = registryStorage.read(null);
		assertThat(read instanceof RegistryStorage.Read.Corrupt, is(true));
	}

	/**
	 * Deleting an index leaves its search settings object behind, and that
	 * object alone keeping the prefix listable is not the index still being
	 * held - it is not reported and a repair restores nothing from it.
	 */
	@Test
	public void testPrefixHoldingOnlySettingsIsNotAnIndex() throws IOException {
		putIndexObject("books/settings.ef.bin", "settings");

		var report = audit.audit();
		assertThat(report.indexes(), emptyIterable());
		assertThat(report.unusable(), emptyIterable());

		assertThat(audit.repair(true, Lists.immutable.empty()).isEmpty(), is(true));
	}

	/**
	 * Beside real generations the settings object changes nothing - it is a
	 * content key rather than a generation prefix.
	 */
	@Test
	public void testSettingsObjectBesideGenerationsChangesNothing() throws IOException {
		putManifest("books", "1");
		putIndexObject("books/settings.ef.bin", "settings");

		var report = audit.audit();
		assertThat(
			report.indexes().collect(RegistryAuditReport.AuditedIndex::name),
			contains("books")
		);
		assertThat(report.indexes().getFirst().generations(), contains(
			new RegistryAuditReport.AuditedGeneration("1", false, RegistryAuditReport.Stored.SYNCED, null)
		));

		var result = audit.repair(true, Lists.immutable.empty());
		assertThat(result.createdIndexes(), contains("books"));
		assertThat(result.addedGenerations(), contains("books@1"));
	}

	/**
	 * A deleted index waits in the storage for the sweep. The audit says so
	 * and when, and a repair leaves it be - registering it again would undo
	 * the delete behind the operator's back.
	 */
	@Test
	public void testDeletedIndexIsReportedAndNotRegistered() throws IOException {
		putManifest("books", "1");
		removals.mark(IndexName.of("books"));

		var report = audit.audit();
		var books = report.indexes().getFirst();
		assertThat(books.registered(), is(false));
		assertThat(books.removedAt(), is(notNullValue()));
		assertThat(books.proposedLive(), is(nullValue()));
		assertThat(books.generations(), contains(
			new RegistryAuditReport.AuditedGeneration("1", false, RegistryAuditReport.Stored.SYNCED, null)
		));

		assertThat(audit.repair(true, Lists.immutable.empty()).isEmpty(), is(true));
		assertThat(registryStorage.read(null) instanceof RegistryStorage.Read.Absent, is(true));
	}

	/**
	 * Asking the repair to restore a deleted index takes its mark away and
	 * registers it like any other storage the registry does not name.
	 */
	@Test
	public void testRestoringADeletedIndexRegistersIt() throws IOException {
		putManifest("books", "1");
		putManifest("books", "2");
		removals.mark(IndexName.of("books"));

		var result = audit.repair(true, Lists.immutable.of(IndexName.of("books")));

		assertThat(result.restored(), contains("books"));
		assertThat(result.createdIndexes(), contains("books"));
		assertThat(result.addedGenerations(), contains("books@1", "books@2"));
		assertThat(result.promoted(), contains("books@2"));
		assertThat(removals.markedAt(IndexName.of("books")), is(Optional.empty()));

		var books = audit.audit().indexes().getFirst();
		assertThat(books.registered(), is(true));
		assertThat(books.removedAt(), is(nullValue()));
	}

	/**
	 * A generation deleted on its own is reported with its own time and is
	 * neither registered nor proposed for promotion, while the rest of its
	 * index is repaired as usual.
	 */
	@Test
	public void testDeletedGenerationIsSkipped() throws IOException {
		putManifest("books", "1");
		putManifest("books", "2");
		removals.mark(IndexName.of("books", "2"));

		var report = audit.audit();
		var books = report.indexes().getFirst();
		assertThat(books.removedAt(), is(nullValue()));
		assertThat(books.proposedLive(), is("1"));
		assertThat(books.generations().getFirst().removedAt(), is(nullValue()));
		assertThat(books.generations().getLast().removedAt(), is(notNullValue()));

		var result = audit.repair(true, Lists.immutable.empty());
		assertThat(result.createdIndexes(), contains("books"));
		assertThat(result.addedGenerations(), contains("books@1"));
		assertThat(result.promoted(), contains("books@1"));

		var restored = audit.repair(true, Lists.immutable.of(IndexName.of("books", "2")));
		assertThat(restored.restored(), contains("books@2"));
		assertThat(restored.addedGenerations(), contains("books@2"));
		// The index is already registered, so what it answers for is left alone
		assertThat(restored.promoted(), emptyIterable());
	}

	/**
	 * Restoring a name that carries no mark changes nothing, and is not
	 * reported as restored.
	 */
	@Test
	public void testRestoringAnUnmarkedNameChangesNothing() throws IOException {
		putManifest("books", "1");

		var result = audit.repair(true, Lists.immutable.of(IndexName.of("movies")));

		assertThat(result.restored(), emptyIterable());
		assertThat(result.createdIndexes(), contains("books"));
	}
}
