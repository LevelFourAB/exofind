package se.l4.exofind.engine.api.v1alpha1.search;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.api.v1alpha1.FreshnessTokens;
import se.l4.exofind.engine.api.v1alpha1.documents.DocumentResource;
import se.l4.exofind.engine.api.v1alpha1.documents.model.DocumentsRequest;
import se.l4.exofind.engine.api.v1alpha1.search.model.FreshnessRequest;
import se.l4.exofind.engine.api.v1alpha1.search.model.SearchRequest;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.freshness.Freshness;
import se.l4.exofind.engine.freshness.FreshnessWaiter;
import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.LocalRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.settings.InMemorySearchSettingsStorage;
import se.l4.exofind.engine.index.settings.SearchSettings;
import se.l4.exofind.engine.index.state.NoopSyncProvider;
import se.l4.exofind.engine.reindex.TestReindexJobs;
import se.l4.exofind.engine.storage.StorageMode;

/**
 * The freshness token through the API: a write answers with one, a search
 * hands it back and is answered once the node holds the write, and a token
 * the node cannot act on is refused with a code that says why.
 */
public class SearchFreshnessResourceTest {
	@TempDir
	Path storageDirectory;

	Indexes indexes;
	SearchResource search;
	DocumentResource documents;

	@BeforeEach
	void setup() throws IOException {
		var nodeState = new NodeState(true);
		nodeState.updateOwnership(true);

		var registry = new IndexRegistry(
			new LocalRegistryStorage(storageDirectory.resolve("registry.ef.bin")),
			Duration.ofMinutes(5)
		);

		/*
		 * A commit policy that would wait five seconds on its own, so a
		 * search answered sooner shows the token asked the writer to commit.
		 */
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
			10000,
			Duration.ofSeconds(5),
			Optional.empty(),
			Optional.empty(),
			Duration.ofHours(24),
			Duration.ofHours(168),
			Duration.ofHours(1)
		);

		var searchSettings = new SearchSettings(
			new InMemorySearchSettingsStorage(),
			registry,
			new RegistryHints(registry, StorageMode.LOCAL),
			Duration.ofSeconds(10),
			Duration.ofMinutes(10)
		);

		var waiter = new FreshnessWaiter(indexes, searchSettings, Duration.ofSeconds(10));

		search = new SearchResource(
			indexes,
			searchSettings,
			waiter,
			SearchResourceTest.metrics(),
			SearchLimits.defaults(),
			Duration.ZERO,
			Duration.ZERO
		);
		documents = new DocumentResource(
			indexes,
			new ObjectMapper(),
			TestReindexJobs.create(nodeState, indexes, registry, storageDirectory),
			waiter
		);

		indexes.create(
			"books",
			IndexDef.newBuilder()
				.putFields(
					"id",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setString(StringFieldTypeDef.getDefaultInstance())
						)
						.setPrimaryKey(true)
						.build()
				)
				.build()
		);
	}

	@AfterEach
	void cleanup() {
		indexes.close();
	}

	private static SearchRequest demanding(String token) {
		return new SearchRequest(
			null, null, null, null, null, null, null, null, null, null, null, null, null, null,
			null, null, null, null, new FreshnessRequest(token)
		);
	}

	@Test
	public void testAWriteAnswersWithTheStateItLandsIn() {
		var response = documents.add(
			"books",
			null,
			new DocumentsRequest(List.of(Map.of("id", "1")))
		);

		var state = FreshnessTokens.decode(response.freshness(), null, "books");

		assertThat(state, is(Freshness.ofCommit(IndexName.of("books", "1"), 1)));
	}

	@Test
	public void testASearchHandedTheTokenSeesTheWrite() {
		var written = documents.add(
			"books",
			null,
			new DocumentsRequest(List.of(Map.of("id", "1")))
		);

		var started = System.nanoTime();
		var response = search.search("books", demanding(written.freshness()));

		assertThat(response.hits().size(), is(1));
		assertThat(
			Duration.ofNanos(System.nanoTime() - started),
			is(lessThan(Duration.ofSeconds(4)))
		);

		var answered = FreshnessTokens.decode(response.freshness(), null, "books");
		assertThat(answered.generation(), is("1"));
		assertThat(answered.commit(), is(1L));
		assertThat(answered.hasSettingsVersion(), is(true));
	}

	@Test
	public void testASearchWithoutATokenAnswersFromWhatTheNodeHolds() {
		documents.add("books", null, new DocumentsRequest(List.of(Map.of("id", "1"))));

		var response = search.search("books", null);

		assertThat(response.hits().size(), is(0));
		assertThat(response.freshness(), is(notNullValue()));
	}

	@Test
	public void testARemovalAnswersWithTheStateInItsHeader() {
		documents.add("books", null, new DocumentsRequest(List.of(Map.of("id", "1"))));

		var response = documents.delete("books", "1");
		var token = response.getHeaderString(FreshnessTokens.HEADER);

		assertThat(response.getStatus(), is(204));
		assertThat(
			FreshnessTokens.decode(token, null, "books"),
			is(Freshness.ofCommit(IndexName.of("books", "1"), 1))
		);
	}

	@Test
	public void testAScanAnswersWithTheStateItReadFrom() {
		var written = documents.add(
			"books",
			null,
			new DocumentsRequest(List.of(Map.of("id", "1")))
		);
		indexes.getOrThrow("books");

		var response = documents.scan("books", null, null);

		assertThat(
			FreshnessTokens.decode(response.freshness(), null, "books").generation(),
			is("1")
		);
		assertThat(written.freshness(), is(notNullValue()));
	}

	@Test
	public void testATokenTheEngineDidNotIssueIsRefused() {
		var e = assertThrows(
			ValidationException.class,
			() -> search.search("books", demanding("not-a-token"))
		);

		var error = e.getErrors().getOnly();
		assertThat(error.getCode(), is("search:freshness:invalid"));
		assertThat(error.getLocation().describe(), is("freshness.atLeast"));
	}

	@Test
	public void testATokenOfAFormatThisNodeDoesNotReadIsRefused() {
		var issued = Base64.getUrlDecoder().decode(
			FreshnessTokens.encode(Freshness.ofCommit(IndexName.of("books", "1"), 1))
		);
		issued[0] = 7;
		var token = Base64.getUrlEncoder().withoutPadding().encodeToString(issued);

		var e = assertThrows(
			ValidationException.class,
			() -> search.search("books", demanding(token))
		);

		var error = e.getErrors().getOnly();
		assertThat(error.getCode(), is("search:freshness:version_unsupported"));
		assertThat(error.getArguments().get("version"), is(7));
	}

	@Test
	public void testATokenOfAnotherIndexIsRefused() {
		var token = FreshnessTokens.encode(Freshness.ofCommit(IndexName.of("films", "1"), 1));

		var e = assertThrows(
			ValidationException.class,
			() -> search.search("books", demanding(token))
		);

		assertThat(e.getErrors().getOnly().getCode(), is("search:freshness:index_mismatch"));
	}
}
