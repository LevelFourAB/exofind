package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.ObjectFieldTypeDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.matchers.Matchers;
import se.l4.exofind.engine.query.matchers.TextMatcher;

/**
 * Tests for highlighting - that the fragments of a hit show what the text of
 * the search matched, wrapped the way the search asked, and that fields not
 * defined for it are refused.
 */
public class HighlightSearchTest extends AbstractIndexTest {
	@Test
	public void testMatchedWordIsWrapped() throws IOException {
		var index = library();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("silent"))
				.addHighlight("name")
				.build()
		);

		assertThat(highlightsOf(result, "1", "name"), contains("The <em>Silent</em> Patient"));
	}

	@Test
	public void testHalfTypedWordIsHighlightedWhole() throws IOException {
		var index = library();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("spr"))
				.addHighlight("name")
				.build()
		);

		assertThat(highlightsOf(result, "2", "name"), contains("<em>Spring</em> Cleaning"));
	}

	@Test
	public void testMisspelledWordHighlightsTheWordMeant() throws IOException {
		var index = typos();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text(TextMatcher.of("sprnig").withPrefix(TextMatcher.Prefix.OFF)))
				.addHighlight("name")
				.build()
		);

		assertThat(highlightsOf(result, "1", "name"), contains("<em>Spring</em> Cleaning"));
	}

	@Test
	public void testMisspelledHalfTypedWordHighlightsTheWordMeant() throws IOException {
		var index = typos();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("sprnig cl"))
				.addHighlight("name")
				.build()
		);

		assertThat(
			highlightsOf(result, "1", "name"),
			contains("<em>Spring</em> <em>Cleaning</em>")
		);
	}

	@Test
	public void testMarkersAreWhatTheSearchAsksFor() throws IOException {
		var index = library();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("silent"))
				.addHighlight("name", new SearchRequest.Highlight(3, 150, "[", "]"))
				.build()
		);

		assertThat(highlightsOf(result, "1", "name"), contains("The [Silent] Patient"));
	}

	@Test
	public void testFragmentsAreCappedAtWhatTheSearchAsksFor() throws IOException {
		var index = library();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("stars"))
				.addHighlight("description", new SearchRequest.Highlight(1, 40, "<em>", "</em>"))
				.build()
		);

		assertThat(highlightsOf(result, "3", "description"), hasSize(1));
	}

	@Test
	public void testEverySentenceThatMatchesGetsAFragment() throws IOException {
		var index = library();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("stars"))
				.addHighlight("description", new SearchRequest.Highlight(3, 40, "<em>", "</em>"))
				.build()
		);

		assertThat(highlightsOf(result, "3", "description"), hasSize(2));
	}

	@Test
	public void testEveryHitOfThePageIsHighlighted() throws IOException {
		var index = create(
			"winter",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields("name", string(highlightedMatching()).build())
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Winter Journal")
			)
		);

		// Sits between the matches, so the shared postings skip past it
		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "Summer House")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "3"),
				new Document.Value("name", "Winter Light")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "4"),
				new Document.Value("name", "Winter Notes")
			)
		);

		index.commit();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("winter"))
				.addHighlight("name")
				.build()
		);

		assertThat(result.hits().size(), is(3));
		assertThat(highlightsOf(result, "1", "name"), contains("<em>Winter</em> Journal"));
		assertThat(highlightsOf(result, "3", "name"), contains("<em>Winter</em> Light"));
		assertThat(highlightsOf(result, "4", "name"), contains("<em>Winter</em> Notes"));
	}

	@Test
	public void testTextShorterThanTheLengthIsOneWholeFragment() throws IOException {
		var index = library();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("patient"))
				.addHighlight("name", new SearchRequest.Highlight(3, 1000, "<em>", "</em>"))
				.build()
		);

		assertThat(highlightsOf(result, "1", "name"), contains("The Silent <em>Patient</em>"));
	}

	@Test
	public void testFiltersAreNotHighlighted() throws IOException {
		var index = library();

		var result = index.search(
			SearchRequest.create()
				.withQuery(
					Query.text("silent"),
					Query.field("description", Matchers.text("thriller"))
				)
				.addHighlight("name")
				.addHighlight("description")
				.build()
		);

		var hit = hit(result, "1");
		assertThat(hit.highlights().get("name"), contains("The <em>Silent</em> Patient"));
		// Matched on, but only as a filter - not part of why the hit ranks
		assertThat(hit.highlights().get("description"), is(nullValue()));
	}

	@Test
	public void testSearchOfOnlyFiltersHighlightsNothing() throws IOException {
		var index = library();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.field("category", Matchers.equalTo("thrillers")))
				.addHighlight("name")
				.build()
		);

		assertThat(result.hits().size(), is(1));
		assertThat(result.hits().get(0).highlights().isEmpty(), is(true));
	}

	@Test
	public void testBoostedTextIsHighlighted() throws IOException {
		var index = library();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.boost(2f, Query.field("name", Matchers.text("silent"))))
				.addHighlight("name")
				.build()
		);

		assertThat(highlightsOf(result, "1", "name"), contains("The <em>Silent</em> Patient"));
	}

	@Test
	public void testAutocompleteFieldHighlightsTheWholeWord() throws IOException {
		var index = library();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("spr").withField("completion"))
				.addHighlight("completion")
				.build()
		);

		assertThat(highlightsOf(result, "2", "completion"), contains("<em>Spring</em> Cleaning"));
	}

	@Test
	public void testEveryValueOfAFieldCanCarryAMatch() throws IOException {
		var index = library();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("tidying").withField("tags"))
				.addHighlight("tags")
				.build()
		);

		assertThat(highlightsOf(result, "2", "tags"), contains("<em>tidying</em>"));
	}

	@Test
	public void testFieldWithoutAMatchHasNoEntry() throws IOException {
		var index = library();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("silent"))
				.addHighlight("name")
				.addHighlight("description")
				.build()
		);

		var hit = hit(result, "1");
		assertThat(hit.highlights().get("name"), contains("The <em>Silent</em> Patient"));
		assertThat(hit.highlights().get("description"), is(nullValue()));
	}

	@Test
	public void testHighlightsDoNotDependOnTheFieldsBroughtBack() throws IOException {
		var index = library();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("silent"))
				.withFields("id")
				.addHighlight("name")
				.build()
		);

		assertThat(highlightsOf(result, "1", "name"), contains("The <em>Silent</em> Patient"));
	}

	@Test
	public void testContinuingFromAHitStillHighlights() throws IOException {
		var index = library();

		var first = index.search(
			SearchRequest.create()
				.withQuery(Query.text("cleaning stars").withMatcher(
					TextMatcher.of("cleaning stars").withMatch(TextMatcher.Match.ANY)
				))
				.withLimit(1)
				.addHighlight("name")
				.build()
		);
		assertThat(first.hits().size(), is(1));

		var second = index.search(
			SearchRequest.create()
				.withQuery(Query.text("cleaning stars").withMatcher(
					TextMatcher.of("cleaning stars").withMatch(TextMatcher.Match.ANY)
				))
				.withLimit(1)
				.withAfter(first.hits().get(0).key())
				.addHighlight("name")
				.build()
		);

		assertThat(second.hits().size(), is(1));
		assertThat(second.hits().get(0).highlights().isEmpty(), is(false));
	}

	@Test
	public void testUnknownFieldIsRefused() throws IOException {
		var index = library();

		assertThrows(
			IndexFieldNotFoundException.class,
			() -> index.search(
				SearchRequest.create()
					.withQuery(Query.text("silent"))
					.addHighlight("missing")
					.build()
			)
		);
	}

	@Test
	public void testFieldNotDefinedForHighlightingIsRefused() throws IOException {
		var index = library();

		assertThrows(
			IndexFieldUsageException.class,
			() -> index.search(
				SearchRequest.create()
					.withQuery(Query.text("silent"))
					.addHighlight("plain")
					.build()
			)
		);
	}

	@Test
	public void testFieldMatchedOnMatchingIsRefusedWhenOnlyAutocompleteHighlights()
		throws IOException {
		var index = library();

		/*
		 * Text searches the field through matching, which was not declared
		 * highlightable - the declaration on autocomplete reads vectors no
		 * query term lands in, so it is refused rather than answered with
		 * nothing.
		 */
		assertThrows(
			IndexFieldUsageException.class,
			() -> index.search(
				SearchRequest.create()
					.withQuery(Query.text("silent"))
					.addHighlight("mixed")
					.build()
			)
		);
	}

	@Test
	public void testRefusedEvenWhenOnlyCounting() throws IOException {
		var index = library();

		assertThrows(
			IndexFieldUsageException.class,
			() -> index.search(
				SearchRequest.create()
					.withQuery(Query.text("silent"))
					.withLimit(0)
					.addHighlight("plain")
					.build()
			)
		);
	}

	@Test
	public void testLocaleFieldHighlightsTheVariantTheSearchReads() throws IOException {
		var index = localized();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("löparskor"))
				.withLocale("sv")
				.addHighlight("name")
				.build()
		);

		assertThat(highlightsOf(result, "1", "name"), contains("röda <em>löparskor</em>"));
	}

	@Test
	public void testLocaleTheFieldNeverHeldFallsBackToItsDefault() throws IOException {
		var index = localized();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("running"))
				.withLocale("fr")
				.addHighlight("name")
				.build()
		);

		assertThat(highlightsOf(result, "1", "name"), contains("red <em>running</em> shoes"));
	}

	@Test
	public void testQuotedWordsOfATypedTextAreHighlighted() throws IOException {
		var index = library();

		var result = index.search(
			SearchRequest.create()
				.withQuery(
					Query.text(TextMatcher.of("\"silent patient\"").withMatch(TextMatcher.Match.USER))
				)
				.addHighlight("name")
				.build()
		);

		assertThat(
			highlightsOf(result, "1", "name"),
			contains("The <em>Silent</em> <em>Patient</em>")
		);
	}

	/**
	 * A hit is shown what it matched, and it matched none of what was left
	 * out - a word excluded from one field can still sit in another the search
	 * never asked about.
	 */
	@Test
	public void testWordsLeftOutOfATypedTextAreNotHighlighted() throws IOException {
		var index = library();

		var result = index.search(
			SearchRequest.create()
				.withQuery(
					Query.text(TextMatcher.of("silent -gripping").withMatch(TextMatcher.Match.USER))
						.withField("name")
				)
				.addHighlight("name")
				.addHighlight("description")
				.build()
		);

		var hit = hit(result, "1");
		assertThat(hit.highlights().get("name"), contains("The <em>Silent</em> Patient"));
		assertThat(hit.highlights().get("description"), is(nullValue()));
	}

	/**
	 * An index of a few books, with one field per way highlighting can be
	 * declared - matching, autocomplete-only, both, and not at all.
	 */
	/**
	 * An index whose definition pins the offsets to term vectors - the layout
	 * of every index created before offsets moved into postings - highlights
	 * from them, matched words and half typed ones alike.
	 */
	@Test
	public void testTermVectorLayoutStillHighlights() throws IOException {
		var index = create(
			"vectors",
			IndexDef.newBuilder()
				.setHighlightLayout(IndexDef.HighlightLayout.HIGHLIGHT_LAYOUT_TERM_VECTORS)
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields("name", string(highlightedMatching()).build())
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "The Silent Patient")
			)
		);
		index.commit();

		var matched = index.search(
			SearchRequest.create()
				.withQuery(Query.text("silent"))
				.addHighlight("name")
				.build()
		);
		assertThat(
			highlightsOf(matched, "1", "name"),
			contains("The <em>Silent</em> Patient")
		);

		var halfTyped = index.search(
			SearchRequest.create()
				.withQuery(Query.text("sil"))
				.addHighlight("name")
				.build()
		);
		assertThat(
			highlightsOf(halfTyped, "1", "name"),
			contains("The <em>Silent</em> Patient")
		);
	}

	/**
	 * The layout is decided when an index is created and carried through
	 * every update, so a definition sent without one never moves an index
	 * off the layout its documents are written in.
	 */
	@Test
	public void testHighlightLayoutSticksAcrossDefinitionUpdates() throws IOException {
		var definition = IndexDef.newBuilder()
			.putFields("id", string().setPrimaryKey(true).build())
			.putFields("name", string(highlightedMatching()).build());

		var index = create("layout", definition);
		assertThat(
			index.getDefinition().getHighlightLayout(),
			is(IndexDef.HighlightLayout.HIGHLIGHT_LAYOUT_POSTINGS)
		);

		index.updateDefinition(
			definition
				.putFields("extra", string().build())
				.build()
		);
		assertThat(
			index.getDefinition().getHighlightLayout(),
			is(IndexDef.HighlightLayout.HIGHLIGHT_LAYOUT_POSTINGS)
		);
	}

	private Index library() throws IOException {
		var index = create(
			"library",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields("name", string(highlightedMatching()).build())
				.putFields("description", string(highlightedMatching()).build())
				.putFields("tags", string(highlightedMatching()).setMultiple(true).build())
				.putFields(
					"completion",
					string(
						StringFieldTypeDef.newBuilder()
							.setAutocomplete(highlighted())
					).build()
				)
				.putFields(
					"plain",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(StringFieldTypeDef.TextUsageConfig.getDefaultInstance())
					).build()
				)
				.putFields(
					"mixed",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(StringFieldTypeDef.TextUsageConfig.getDefaultInstance())
							.setAutocomplete(highlighted())
					).build()
				)
				.putFields(
					"category",
					string().setFilter(FilterConfig.getDefaultInstance()).build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "The Silent Patient"),
				new Document.Value("description", "A gripping thriller about a woman who stops speaking"),
				new Document.Value("plain", "The Silent Patient"),
				new Document.Value("mixed", "The Silent Patient"),
				new Document.Value("category", "thrillers")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "Spring Cleaning"),
				new Document.Value("description", "Order for every home"),
				new Document.Value("completion", "Spring Cleaning"),
				new Document.Value("tags", "household"),
				new Document.Value("tags", "tidying"),
				new Document.Value("category", "lifestyle")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "3"),
				new Document.Value("name", "The Stars Above"),
				new Document.Value(
					"description",
					"Stars have guided travellers for millennia. "
						+ "A field guide to reading the stars at night. "
						+ "With charts for every season."
				),
				new Document.Value("category", "science")
			)
		);

		index.commit();
		return index;
	}

	/**
	 * An index of a name that can be misspelled, forgiving enough mistakes
	 * for the tests to make them.
	 */
	private Index typos() throws IOException {
		var index = create(
			"typos",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(
								StringFieldTypeDef.TextUsageConfig.newBuilder()
									.setHighlight(
										StringFieldTypeDef.TextUsageConfig.HighlightConfig
											.getDefaultInstance()
									)
									.setTypoTolerance(
										StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig
											.getDefaultInstance()
									)
							)
					).build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Spring Cleaning")
			)
		);

		index.commit();
		return index;
	}

	/**
	 * An index whose `name` holds values in English and Swedish, with English
	 * assumed for values that carry no locale.
	 */
	private Index localized() throws IOException {
		var index = create(
			"localized",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"name",
					string(highlightedMatching())
						.setLocales(
							FieldDef.LocaleConfig.newBuilder()
								.setDefaultLocale("en")
								.addLocales("sv")
						)
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "red running shoes", "en"),
				new Document.Value("name", "röda löparskor", "sv")
			)
		);

		index.commit();
		return index;
	}

	/**
	 * A half typed last word answered from the autocomplete usage of the field
	 * still lights up whole: the query highlighting reads is compiled for the
	 * field whose term vectors hold the offsets, not the shape the search ran.
	 */
	@Test
	public void testWordAnsweredFromAutocompleteIsStillHighlighted() throws IOException {
		var index = create(
			"matched-and-completed",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"name",
					string(
						highlightedMatching()
							.setAutocomplete(
								StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
							)
					).build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Spring Cleaning")
			)
		);
		index.commit();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("cleaning spr"))
				.addHighlight("name")
				.build()
		);

		assertThat(
			highlightsOf(result, "1", "name"),
			contains("<em>Spring</em> <em>Cleaning</em>")
		);
	}

	@Test
	public void testFieldInsideAnObjectIsHighlighted() throws IOException {
		var index = shop();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("leather"))
				.addHighlight("product.note")
				.build()
		);

		assertThat(
			highlightsOf(result, "1", "product.note"),
			contains("full grain <em>leather</em> upper")
		);
	}

	@Test
	public void testFieldBelowTwoObjectsIsHighlighted() throws IOException {
		var index = shop();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("resoled"))
				.addHighlight("product.care.advice")
				.build()
		);

		assertThat(
			highlightsOf(result, "1", "product.care.advice"),
			contains("can be <em>resoled</em>")
		);
	}

	/**
	 * The values of a flattened list store as values of one field, so a
	 * fragment says where a word matched but not which value it came from -
	 * the same answer a multiple field of the index gives.
	 */
	@Test
	public void testFieldInsideAFlattenedListIsHighlighted() throws IOException {
		var index = shop();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("waterproof"))
				.addHighlight("tags.label")
				.build()
		);

		assertThat(
			highlightsOf(result, "1", "tags.label"),
			contains("<em>waterproof</em> shell")
		);
	}

	@Test
	public void testHighlightOnAFieldInsideANestedListIsRefused() throws IOException {
		var index = shop();

		var e = assertThrows(
			IndexException.class,
			() -> index.search(
				SearchRequest.create()
					.withQuery(Query.text("leather"))
					.addHighlight("variants.color")
					.build()
			)
		);

		assertThat(e.getCode(), is("index:query:nested:outside"));
	}

	/**
	 * A shop whose highlightable text sits inside objects: a single
	 * {@code product} holding a note and a {@code care} object of its own, a
	 * flattened {@code tags} list, and a nested {@code variants} list whose
	 * fields never highlight.
	 */
	private Index shop() throws IOException {
		var index = create(
			"shop",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"product",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder().setObject(
								ObjectFieldTypeDef.newBuilder()
									.putFields("note", string(highlightedMatching()).build())
									.putFields(
										"care",
										FieldDef.newBuilder()
											.setType(
												FieldTypeDef.newBuilder().setObject(
													ObjectFieldTypeDef.newBuilder().putFields(
														"advice",
														string(highlightedMatching()).build()
													)
												)
											)
											.build()
									)
							)
						)
						.build()
				)
				.putFields(
					"tags",
					FieldDef.newBuilder()
						.setMultiple(true)
						.setType(
							FieldTypeDef.newBuilder().setObject(
								ObjectFieldTypeDef.newBuilder()
									.setMode(ObjectFieldTypeDef.Mode.MODE_FLATTENED)
									.putFields("label", string(highlightedMatching()).build())
							)
						)
						.build()
				)
				.putFields(
					"variants",
					FieldDef.newBuilder()
						.setMultiple(true)
						.setType(
							FieldTypeDef.newBuilder().setObject(
								ObjectFieldTypeDef.newBuilder()
									.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
									.putFields(
										"color",
										string(
											StringFieldTypeDef.newBuilder().setMatching(
												StringFieldTypeDef.TextUsageConfig
													.getDefaultInstance()
											)
										).build()
									)
							)
						)
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value(
					"product",
					new Document(
						new Document.Value("note", "full grain leather upper"),
						new Document.Value(
							"care",
							new Document(new Document.Value("advice", "can be resoled"))
						)
					)
				),
				new Document.Value(
					"tags",
					new Document(new Document.Value("label", "waterproof shell"))
				),
				new Document.Value(
					"tags",
					new Document(new Document.Value("label", "lined"))
				),
				new Document.Value(
					"variants",
					new Document(new Document.Value("color", "brown leather"))
				)
			)
		);

		index.commit();
		return index;
	}

	private static StringFieldTypeDef.Builder highlightedMatching() {
		return StringFieldTypeDef.newBuilder().setMatching(highlighted());
	}

	private static StringFieldTypeDef.TextUsageConfig.Builder highlighted() {
		return StringFieldTypeDef.TextUsageConfig.newBuilder()
			.setHighlight(
				StringFieldTypeDef.TextUsageConfig.HighlightConfig.getDefaultInstance()
			);
	}

	private static FieldDef.Builder string() {
		return string(StringFieldTypeDef.newBuilder());
	}

	private static FieldDef.Builder string(StringFieldTypeDef.Builder type) {
		return FieldDef.newBuilder()
			.setType(FieldTypeDef.newBuilder().setString(type));
	}

	private static SearchResult.Hit hit(SearchResult result, Object id) {
		return result.hits().detect(h -> id.equals(h.id()));
	}

	private static java.util.List<String> highlightsOf(SearchResult result, Object id, String field) {
		var fragments = hit(result, id).highlights().get(field);
		return fragments == null ? null : fragments.castToList();
	}
}
