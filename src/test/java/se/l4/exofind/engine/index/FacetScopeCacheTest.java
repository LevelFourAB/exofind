package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.FacetConfig;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.SearchRequest;

/**
 * Tests for the facet scope cache - that a facet asked for again over the
 * same scope is answered from what was kept, that the cache answers alike
 * whether it keeps anything or not, and that an index closing takes its
 * entries with it.
 */
public class FacetScopeCacheTest extends AbstractIndexTest {
	@Test
	public void testAFacetAskedAgainIsAnsweredFromWhatWasKept() throws IOException {
		var caches = caches(1 << 20);
		var index = products("products", caches);

		var request = SearchRequest.create()
			.addFacet(Facet.of("category"))
			.build();

		index.search(request);
		assertThat(caches.facetScopes().entries(), greaterThan(0L));

		var before = FacetCacheStats.current().hits();
		var result = index.search(request);

		assertThat(FacetCacheStats.current().hits() - before, greaterThan(0L));
		assertThat(result.facets().get("category").values().size(), is(2));
	}

	@Test
	public void testACacheWithNoRoomAnswersLikeOneWithRoom() throws IOException {
		var kept = products("kept", caches(1 << 20));
		var counted = products("counted", caches(0));

		var request = SearchRequest.create()
			.addFacet(Facet.of("category"))
			.build();

		kept.search(request);
		counted.search(request);

		var fromCache = kept.search(request).facets().get("category");
		var fromCounting = counted.search(request).facets().get("category");

		assertThat(fromCounting, is(fromCache));
		assertThat(fromCache.values().size(), is(2));
	}

	@Test
	public void testClosingTheIndexDropsItsEntries() throws IOException {
		var caches = caches(1 << 20);
		var index = products("products", caches);

		index.search(SearchRequest.create().addFacet(Facet.of("category")).build());
		assertThat(caches.facetScopes().entries(), greaterThan(0L));

		close(index);

		assertThat(caches.facetScopes().entries(), is(0L));
	}

	@Test
	public void testANegativeSizeIsRefused() {
		assertThrows(IllegalArgumentException.class, () -> FacetScopeCache.sized(-1));
	}

	private static SearchCaches caches(long facetCacheBytes) {
		return new SearchCaches(1000, 1 << 20, 1 << 20, 16, 16, facetCacheBytes);
	}

	/**
	 * An index of four products in two categories, through the given caches.
	 */
	private Index products(String name, SearchCaches caches) throws IOException {
		var index = create(name, caches);

		index.updateDefinition(
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"category",
					string()
						.setFilter(FilterConfig.getDefaultInstance())
						.setFacet(FacetConfig.getDefaultInstance())
						.build()
				)
				.build()
		);

		index.addDocument(product("1", "shoes"));
		index.addDocument(product("2", "shoes"));
		index.addDocument(product("3", "clothes"));
		index.addDocument(product("4", "clothes"));
		index.commit();

		return index;
	}

	private static Document product(String id, String category) {
		return new Document(
			new Document.Value("id", id),
			new Document.Value("category", category)
		);
	}

	private static FieldDef.Builder string() {
		return FieldDef.newBuilder()
			.setType(FieldTypeDef.newBuilder().setString(StringFieldTypeDef.newBuilder()));
	}
}
