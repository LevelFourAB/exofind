package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.matchers.TextMatcher;

/**
 * Tests for the term states cache - that a word searched again is not looked
 * up in every segment again, that the indexes of a node share one cache, and
 * that an index closing takes its entries with it.
 */
public class TermStatesCacheTest extends AbstractIndexTest {
	@Test
	public void testAWordSearchedAgainIsNotLookedUpAgain() throws IOException {
		var caches = caches();
		var index = library("books", caches);

		var request = SearchRequest.create()
			.withQuery(whole("silent"))
			.build();

		index.search(request);
		var misses = caches.termStates().stats().missCount();
		assertThat(misses, greaterThan(0L));

		index.search(request);

		assertThat(caches.termStates().stats().hitCount(), greaterThan(0L));
		assertThat(caches.termStates().stats().missCount(), is(misses));
	}

	@Test
	public void testACommitReplacesTheReaderAndWhatWasKeptForIt() throws IOException {
		var caches = caches();
		var index = library("books", caches);

		var request = SearchRequest.create()
			.withQuery(whole("silent"))
			.build();

		index.search(request);
		var kept = caches.termStates().entries();
		assertThat(kept, greaterThan(0L));

		index.addDocument(
			new Document(
				new Document.Value("id", "3"),
				new Document.Value("name", "The Silent Sea")
			)
		);
		index.commit();

		// The reader the entries were built for is closed, so they are gone
		assertThat(caches.termStates().entries(), is(0L));

		index.search(request);
		assertThat(caches.termStates().entries(), is(kept));
	}

	@Test
	public void testTheIndexesOfANodeShareOneCache() throws IOException {
		var caches = caches();
		var first = library("first", caches);
		var second = library("second", caches);

		var request = SearchRequest.create()
			.withQuery(whole("silent"))
			.build();

		first.search(request);
		var one = caches.termStates().entries();

		second.search(request);
		var both = caches.termStates().entries();
		assertThat(both, is(2 * one));

		close(first);

		assertThat(caches.termStates().entries(), is(one));
	}

	@Test
	public void testClosingTheIndexDropsItsEntries() throws IOException {
		var caches = caches();
		var index = library("books", caches);

		index.search(SearchRequest.create().withQuery(whole("silent")).build());
		assertThat(caches.termStates().entries(), greaterThan(0L));

		close(index);

		assertThat(caches.termStates().entries(), is(0L));
	}

	@Test
	public void testTheBoundHoldsAcrossIndexes() throws IOException {
		// Room for a handful of terms, not for what two indexes ask for
		var caches = new SearchCaches(1000, 1 << 20, 600, 16, 16, 1 << 20);
		var first = library("first", caches);
		var second = library("second", caches);

		var request = SearchRequest.create()
			.withQuery(whole("silent patient spring cleaning"))
			.build();

		first.search(request);
		second.search(request);

		assertThat(caches.termStates().stats().evictionCount(), greaterThan(0L));
		assertThat(caches.termStates().entries(), lessThan(8L));
	}

	@Test
	public void testANegativeSizeIsRefused() {
		assertThrows(IllegalArgumentException.class, () -> TermStatesCache.sized(-1));
	}

	private static SearchCaches caches() {
		return new SearchCaches(1000, 1 << 20, 1 << 20, 16, 16, 1 << 20);
	}

	/**
	 * A text search whose every word is complete. A word still being typed
	 * expands to the terms it starts and reaches no plain term lookup.
	 */
	private static Query whole(String text) {
		return Query.text(TextMatcher.of(text).withPrefix(TextMatcher.Prefix.OFF));
	}

	/**
	 * An index of two books searched by name, through the given caches.
	 */
	private Index library(String name, SearchCaches caches) throws IOException {
		var index = create(name, caches);

		index.updateDefinition(
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(StringFieldTypeDef.TextUsageConfig.getDefaultInstance())
					).setStored(true).build()
				)
				.build()
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "The Silent Patient")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "Spring Cleaning")
			)
		);

		index.commit();
		return index;
	}

	private static FieldDef.Builder string() {
		return string(StringFieldTypeDef.newBuilder());
	}

	private static FieldDef.Builder string(StringFieldTypeDef.Builder type) {
		return FieldDef.newBuilder()
			.setType(FieldTypeDef.newBuilder().setString(type));
	}
}
