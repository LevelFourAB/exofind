package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.FacetConfig;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.matchers.Matchers;
import se.l4.exofind.engine.query.matchers.TextMatcher;

/**
 * Tests for a search that would come back empty letting go of words until it
 * finds something - which words go, which are never touched, and what the
 * result says about it.
 */
public class RelaxSearchTest extends AbstractIndexTest {
	@Test
	public void testWordNothingHoldsIsLetGo() throws IOException {
		var index = catalogue();

		var result = search(index, Query.text("mens waterproof shoes"));

		assertThat(ids(result), contains("1"));
		assertThat(dropped(result), contains("waterproof"));
		assertThat(
			result.relaxed().dropped().get(0).reason(),
			is(SearchResult.Relaxed.Reason.UNMATCHED)
		);
		assertThat(result.relaxed().text(), is("mens shoes"));
	}

	@Test
	public void testSearchThatFindsSomethingIsAnsweredAsAsked() throws IOException {
		var index = catalogue();

		var result = search(index, Query.text("mens shoes"));

		assertThat(ids(result), contains("1"));
		assertThat(result.relaxed(), is(nullValue()));
	}

	@Test
	public void testNothingIsLetGoWhenTheSearchSaysSo() throws IOException {
		var index = catalogue();

		var result = search(
			index,
			Query.text(
				TextMatcher.of("mens waterproof shoes").withRelax(TextMatcher.Relax.OFF)
			)
		);

		assertThat(ids(result), is(empty()));
		assertThat(result.relaxed(), is(nullValue()));
	}

	@Test
	public void testWordsThatAreHeldStayUnlessAskedToGo() throws IOException {
		var index = catalogue();

		// Every word is held, just never all of them by one document
		var result = search(index, Query.text("mens running boots"));

		assertThat(ids(result), is(empty()));
		assertThat(result.relaxed(), is(nullValue()));
	}

	@Test
	public void testCommonWordsGoOneAtATime() throws IOException {
		var index = catalogue();

		var result = search(
			index,
			Query.text(
				TextMatcher.of("mens running boots").withRelax(TextMatcher.Relax.WORDS)
			)
		);

		assertThat(ids(result), containsInAnyOrder("3", "4"));
		assertThat(dropped(result), contains("mens", "running"));
		assertThat(
			result.relaxed().dropped().get(0).reason(),
			is(SearchResult.Relaxed.Reason.COMMON)
		);
		assertThat(result.relaxed().text(), is("boots"));
	}

	@Test
	public void testDocumentHoldingAWordThatWentRanksFirst() throws IOException {
		var index = catalogue();

		var result = search(
			index,
			Query.text(
				TextMatcher.of("mens running boots").withRelax(TextMatcher.Relax.WORDS)
			)
		);

		/*
		 * Both are boots of the same length, so what separates them is that one
		 * of them is still the mens pair that was asked for.
		 */
		assertThat(ids(result), contains("3", "4"));
	}

	@Test
	public void testFilterThatEmptiesThePageIsNotRelaxedAround() throws IOException {
		var index = catalogue();

		var result = search(
			index,
			Query.text(
				TextMatcher.of("mens waterproof shoes").withRelax(TextMatcher.Relax.WORDS)
			),
			Query.field("category", Matchers.equalTo("hats"))
		);

		assertThat(ids(result), is(empty()));
		assertThat(result.relaxed(), is(nullValue()));
	}

	@Test
	public void testQuotedPhraseIsNeverLetGo() throws IOException {
		var index = catalogue();

		var result = search(
			index,
			Query.text(
				TextMatcher.of("\"leather boots\" waterproof")
					.withMatch(TextMatcher.Match.USER)
					.withRelax(TextMatcher.Relax.WORDS)
			)
		);

		// The loose word went; the phrase is what is left, and it is what matched
		assertThat(ids(result), containsInAnyOrder("3", "4"));
		assertThat(dropped(result), contains("waterproof"));
		assertThat(result.relaxed().text(), is("\"leather boots\""));
	}

	@Test
	public void testPhraseNothingHoldsEmptiesThePage() throws IOException {
		var index = catalogue();

		var result = search(
			index,
			Query.text(
				TextMatcher.of("\"leather shoes\" mens")
					.withMatch(TextMatcher.Match.USER)
					.withRelax(TextMatcher.Relax.WORDS)
			)
		);

		/*
		 * Dropping the loose word leaves the phrase, which nothing holds - and
		 * the phrase itself is never what goes.
		 */
		assertThat(ids(result), is(empty()));
		assertThat(result.relaxed(), is(nullValue()));
	}

	@Test
	public void testRelaxingStopsBeforeAskingForNothing() throws IOException {
		var index = catalogue();

		var result = search(
			index,
			Query.text(
				TextMatcher.of("mens shoes -running")
					.withMatch(TextMatcher.Match.USER)
					.withRelax(TextMatcher.Relax.WORDS)
			)
		);

		/*
		 * Letting both words go would leave the exclusion on its own, which
		 * matches nearly everything and answers a question nobody asked.
		 */
		assertThat(ids(result), is(empty()));
		assertThat(result.relaxed(), is(nullValue()));
	}

	@Test
	public void testCountingOnlyStillRelaxes() throws IOException {
		var index = catalogue();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("mens waterproof shoes"))
				.withLimit(0)
				.build()
		);

		assertThat(result.total().count(), is(1L));
		assertThat(dropped(result), contains("waterproof"));
	}

	@Test
	public void testFacetsAreCountedFromWhatWasFound() throws IOException {
		var index = catalogue();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("mens waterproof shoes"))
				.withFacets(Facet.of("category"))
				.build()
		);

		assertThat(ids(result), contains("1"));
		assertThat(dropped(result), contains("waterproof"));

		var category = result.facets().get("category");
		assertThat(category.values().collect(SearchResult.Facet.Value::value).toList(),
			contains("shoes"));
		assertThat(category.values().get(0).count(), is(1L));
	}

	/**
	 * A handful of products whose words are shared in known amounts: {@code
	 * mens}, {@code running} and {@code boots} are each held by two of them and
	 * never all three by one, and nothing is waterproof.
	 */
	private Index catalogue() throws IOException {
		var index = create(
			"catalogue",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(
								StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
							)
					).setStored(true).build()
				)
				.putFields(
					"category",
					string()
						.setStored(true)
						.setFilter(FilterConfig.getDefaultInstance())
						.setFacet(FacetConfig.getDefaultInstance())
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Mens Running Shoes"),
				new Document.Value("category", "shoes")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "Womens Running Shoes"),
				new Document.Value("category", "shoes")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "3"),
				new Document.Value("name", "Mens Leather Boots"),
				new Document.Value("category", "boots")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "4"),
				new Document.Value("name", "Kids Leather Boots"),
				new Document.Value("category", "boots")
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

	private static SearchResult search(Index index, Query... query) throws IOException {
		return index.search(SearchRequest.create().withQuery(query).build());
	}

	private static List<Object> ids(SearchResult result) {
		return result.hits().collect(SearchResult.Hit::id).toList();
	}

	private static List<String> dropped(SearchResult result) {
		return result.relaxed()
			.dropped()
			.collect(SearchResult.Relaxed.Dropped::word)
			.toList();
	}
}
