package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.index.schema.DoubleFieldTypeDef;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.state.NoopSync;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;

/**
 * Tests for the document cache - that a search through it answers exactly like
 * one without it, that a page read again is answered from the cache, and that
 * an index closing takes its entries with it.
 */
public class DocumentCacheTest {
	@TempDir
	Path root;

	private final List<Index> opened = new ArrayList<>();

	@AfterEach
	void cleanup() throws IOException {
		for(var index : opened) {
			index.close();
		}
	}

	@Test
	public void testASearchThroughTheCacheAnswersLikeOneWithout() throws IOException {
		var cached = library("cached", DocumentCache.sized(1 << 20));
		var plain = library("plain", DocumentCache.disabled());

		var request = SearchRequest.create()
			.withQuery(Query.text("silent"))
			.build();

		assertSameHits(cached.search(request), plain.search(request));
	}

	@Test
	public void testAskingForSomeFieldsCutsTheCachedDocumentDownTheSameWay() throws IOException {
		var cached = library("cached", DocumentCache.sized(1 << 20));
		var plain = library("plain", DocumentCache.disabled());

		var request = SearchRequest.create()
			.withQuery(Query.text("silent"))
			.withFields("name")
			.build();

		var throughCache = cached.search(request);
		assertSameHits(throughCache, plain.search(request));

		var document = throughCache.hits().getFirst().document();
		assertThat(document.get("name"), is("The Silent Patient"));
		assertThat(document.get("weight"), is(nullValue()));
	}

	@Test
	public void testValuesOfEveryShapeComeBackAsGiven() throws IOException {
		var index = library("cached", DocumentCache.sized(1 << 20));

		var request = SearchRequest.create()
			.withQuery(Query.text("silent"))
			.build();

		// Read twice so the values checked are the ones a cache hit gives back
		index.search(request);
		var document = index.search(request).hits().getFirst().document();

		assertThat(document.get("id"), is("1"));
		assertThat(document.get("name"), is("The Silent Patient"));
		assertThat(document.get("weight"), is(0.5));
		assertThat(
			document.getAll("tags"),
			contains((Object) "thriller", "mystery")
		);
	}

	@Test
	public void testAPageReadAgainIsAnsweredFromTheCache() throws IOException {
		var cache = DocumentCache.sized(1 << 20);
		var index = library("cached", cache);

		var request = SearchRequest.create()
			.withQuery(Query.text("silent"))
			.build();

		index.search(request);
		var misses = cache.stats().missCount();

		index.search(request);

		assertThat(cache.stats().hitCount(), greaterThan(0L));
		assertThat(cache.stats().missCount(), is(misses));
	}

	@Test
	public void testHighlightsComeThroughTheCache() throws IOException {
		var index = library("cached", DocumentCache.sized(1 << 20));

		var request = SearchRequest.create()
			.withQuery(Query.text("silent"))
			.addHighlight("name")
			.build();

		// Twice, so the text fragments are cut from was answered by the cache
		index.search(request);
		var result = index.search(request);

		assertThat(
			result.hits().getFirst().highlights().get("name").castToList(),
			contains("The <em>Silent</em> Patient")
		);
	}

	@Test
	public void testClosingTheIndexDropsItsEntries() throws IOException {
		var cache = DocumentCache.sized(1 << 20);
		var index = library("cached", cache);

		index.search(SearchRequest.create().withQuery(Query.text("silent")).build());
		assertThat(cache.entries(), greaterThan(0L));

		index.close();
		opened.remove(index);

		assertThat(cache.entries(), is(0L));
	}

	/**
	 * An index whose documents hold every shape a stored value can have: the
	 * primary key, highlighted text, a number, several values of one field -
	 * and through the kept copy of the document, the source bytes.
	 */
	private Index library(String name, DocumentCache cache) throws IOException {
		var path = root.resolve(name);
		Files.createDirectories(path);

		var nodeState = new NodeState(true);
		nodeState.updateOwnership(true);

		var index = new Index(
			nodeState,
			name,
			path,
			new NoopSync(),
			CommitPolicy.disabled(),
			cache
		);
		opened.add(index);
		index.pull();

		index.updateDefinition(
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields("name", string(highlightedMatching()).setStored(true).build())
				.putFields(
					"weight",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setDouble(DoubleFieldTypeDef.getDefaultInstance())
						)
						.setStored(true)
						.build()
				)
				.putFields("tags", string().setMultiple(true).setStored(true).build())
				.build()
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "The Silent Patient"),
				new Document.Value("weight", 0.5),
				new Document.Value("tags", "thriller"),
				new Document.Value("tags", "mystery")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "Spring Cleaning"),
				new Document.Value("weight", 1.25),
				new Document.Value("tags", "household")
			)
		);

		index.commit();
		return index;
	}

	/**
	 * Compare two results hit by hit through the encoding documents round-trip
	 * through, which sees every field and value where {@link Document} itself
	 * carries no equality.
	 */
	private static void assertSameHits(SearchResult actual, SearchResult expected) {
		assertThat(actual.hits().size(), is(expected.hits().size()));

		for(var i = 0; i < expected.hits().size(); i++) {
			assertThat(
				DocumentSource.encode(actual.hits().get(i).document()),
				is(DocumentSource.encode(expected.hits().get(i).document()))
			);
		}
	}

	private static FieldDef.Builder string() {
		return string(StringFieldTypeDef.newBuilder());
	}

	private static FieldDef.Builder string(StringFieldTypeDef.Builder type) {
		return FieldDef.newBuilder()
			.setType(FieldTypeDef.newBuilder().setString(type));
	}

	private static StringFieldTypeDef.Builder highlightedMatching() {
		return StringFieldTypeDef.newBuilder()
			.setMatching(
				StringFieldTypeDef.TextUsageConfig.newBuilder()
					.setHighlight(
						StringFieldTypeDef.TextUsageConfig.HighlightConfig.getDefaultInstance()
					)
			);
	}
}
