package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;

import org.eclipse.collections.api.factory.Sets;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.DoubleFieldTypeDef;
import se.l4.exofind.engine.index.schema.FacetConfig;
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
 * Tests for bringing which values of an object field matched back with each
 * hit.
 *
 * The catalogue holds a product whose variants interleave with the values of a
 * second object field, because the position of a value is counted among the
 * values of its own field - a badge sitting between two variants must not
 * shift which variant a position names.
 */
public class MatchedSearchTest extends AbstractIndexTest {
	@Test
	public void testMatchedAnswersTheValuesTheClauseAsked() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(
					Query.nested(
						"variants",
						Query.field("variants.color", Matchers.equalTo("red"))
					)
				)
				.addMatched("variants")
				.build()
		);

		/*
		 * The Trail Runner is red in its first and third variant, with badges
		 * written between them - the values that come back have to be those
		 * two variants, not whatever sits at the matched positions of the
		 * whole block.
		 */
		var matched = matchedOf(result, "1", "variants");
		assertThat(matched.totalValues(), is(2));
		assertThat(
			matched.values().collect(value -> value.get("price")),
			contains(15d, 35d)
		);

		var sneaker = matchedOf(result, "2", "variants");
		assertThat(sneaker.totalValues(), is(1));
		assertThat(sneaker.values().getFirst().get("color"), is("red"));
	}

	@Test
	public void testMatchedCountsEveryValueWhenNothingAskedOfThem() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.addMatched("variants")
				.build()
		);

		// Nothing was asked of the variants, so every variant matched
		assertThat(matchedOf(result, "1", "variants").totalValues(), is(3));
		assertThat(matchedOf(result, "2", "variants").totalValues(), is(2));

		// A document without values still answers, with none
		var sandal = matchedOf(result, "3", "variants");
		assertThat(sandal.totalValues(), is(0));
		assertThat(sandal.values().isEmpty(), is(true));
	}

	@Test
	public void testMatchedLimitCutsValuesNotTheCount() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.addMatched("variants", new SearchRequest.Matched(1))
				.build()
		);

		var matched = matchedOf(result, "1", "variants");
		assertThat(matched.values().size(), is(1));
		assertThat(matched.totalValues(), is(3));
	}

	@Test
	public void testMatchedOrdersValuesByScoreWhenTheClauseRanks() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(
					Query.nested(
						"variants",
						Query.text(
							words("waterproof canvas").withMatch(TextMatcher.Match.ANY)
						)
					)
				)
				.addMatched("variants")
				.build()
		);

		/*
		 * The Trail Runner's third variant holds both words and its first one,
		 * so the best answer comes first even though the document gave it
		 * last.
		 */
		var matched = matchedOf(result, "1", "variants");
		assertThat(matched.values().getFirst().get("price"), is(35d));
	}

	@Test
	public void testMatchedKeepsDocumentOrderWhenNothingRanks() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(
					Query.nested(
						"variants",
						Query.field("variants.price", Matchers.lessThan(50d))
					)
				)
				.addMatched("variants")
				.build()
		);

		assertThat(
			matchedOf(result, "1", "variants")
				.values()
				.collect(value -> value.get("price")),
			contains(15d, 25d, 35d)
		);
	}

	@Test
	public void testMatchedAnswersWhenFieldsLeaveThePathOut() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(
					Query.nested(
						"variants",
						Query.field("variants.color", Matchers.equalTo("red"))
					)
				)
				.withFields("name")
				.addMatched("variants")
				.build()
		);

		var hit = hitOf(result, "1");
		assertThat(hit.document().get("variants"), is(nullValue()));

		var matched = hit.matched().get("variants");
		assertThat(matched.totalValues(), is(2));
		assertThat(matched.values().getFirst(), is(notNullValue()));
	}

	@Test
	public void testMatchedOfSeveralPathsCountEachTheirOwnValues() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(
					Query.nested(
						"variants",
						Query.field("variants.color", Matchers.equalTo("red"))
					)
				)
				.addMatched("variants")
				.addMatched("badges")
				.build()
		);

		var hit = hitOf(result, "1");

		// The clause narrowed the variants, and asked nothing of the badges
		assertThat(hit.matched().get("variants").totalValues(), is(2));
		assertThat(
			hit.matched().get("badges").values().collect(value -> value.get("label")),
			contains("eco", "award")
		);
	}

	@Test
	public void testNestedClauseInsideAnOrDoesNotNarrowTheValues() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(
					Query.or(
						Query.nested(
							"variants",
							Query.field("variants.color", Matchers.equalTo("red"))
						)
					)
				)
				.addMatched("variants")
				.build()
		);

		/*
		 * Inside an `or` the clause is one of several ways to match, so it
		 * does not say which value a document was found by - every value
		 * matched, the same as for sorting and counting.
		 */
		assertThat(matchedOf(result, "1", "variants").totalValues(), is(3));
	}

	@Test
	public void testMatchedWithoutSourceAnswersOnlyTheCount() throws IOException {
		var index = productsWithoutSource();

		var result = index.search(
			SearchRequest.create()
				.withQuery(
					Query.nested(
						"variants",
						Query.field("variants.color", Matchers.equalTo("red"))
					)
				)
				.addMatched("variants")
				.build()
		);

		var matched = matchedOf(result, "1", "variants");
		assertThat(matched.values(), is(nullValue()));
		assertThat(matched.totalValues(), is(2));
	}

	@Test
	public void testMatchedOnAFieldThatIsNotAnObjectIsRefused() throws IOException {
		var index = products();

		var e = assertThrows(
			IndexException.class,
			() -> index.search(
				SearchRequest.create()
					.addMatched("name")
					.build()
			)
		);

		assertThat(e.getCode(), is("index:query:matched:not_object"));
	}

	@Test
	public void testMatchedOnAFlattenedObjectIsRefused() throws IOException {
		var index = products();

		var e = assertThrows(
			IndexException.class,
			() -> index.search(
				SearchRequest.create()
					.addMatched("specs")
					.build()
			)
		);

		assertThat(e.getCode(), is("index:query:matched:not_object"));
	}

	@Test
	public void testMatchedOnAFieldInsideAnObjectIsRefused() throws IOException {
		var index = products();

		var e = assertThrows(
			IndexException.class,
			() -> index.search(
				SearchRequest.create()
					.addMatched("variants.color")
					.build()
			)
		);

		assertThat(e.getCode(), is("index:query:matched:not_object"));
	}

	@Test
	public void testMatchedOnAnUnknownFieldIsRefused() throws IOException {
		var index = products();

		assertThrows(
			IndexFieldNotFoundException.class,
			() -> index.search(
				SearchRequest.create()
					.addMatched("colour")
					.build()
			)
		);
	}

	@Test
	public void testMatchedFieldsCutValuesToWhatWasAsked() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(
					Query.nested(
						"variants",
						Query.field("variants.color", Matchers.equalTo("red"))
					)
				)
				.addMatched(
					"variants",
					new SearchRequest.Matched(
						SearchRequest.Matched.DEFAULT_LIMIT,
						Sets.immutable.of("variants.price")
					)
				)
				.build()
		);

		var matched = matchedOf(result, "1", "variants");
		assertThat(
			matched.values().collect(value -> value.get("price")),
			contains(15d, 35d)
		);
		// The fields that were not asked for are gone, not nulled in place
		assertThat(matched.values().getFirst().get("color"), is(nullValue()));
		assertThat(matched.totalValues(), is(2));
	}

	@Test
	public void testMatchedFieldOutsideThePathIsRefused() throws IOException {
		var index = products();

		assertThrows(
			IndexFieldNotFoundException.class,
			() -> index.search(
				SearchRequest.create()
					.addMatched(
						"variants",
						new SearchRequest.Matched(
							SearchRequest.Matched.DEFAULT_LIMIT,
							Sets.immutable.of("badges.label")
						)
					)
					.build()
			)
		);
	}

	@Test
	public void testMatchedFieldUnknownInsideThePathIsRefused() throws IOException {
		var index = products();

		assertThrows(
			IndexFieldNotFoundException.class,
			() -> index.search(
				SearchRequest.create()
					.addMatched(
						"variants",
						new SearchRequest.Matched(
							SearchRequest.Matched.DEFAULT_LIMIT,
							Sets.immutable.of("variants.colour")
						)
					)
					.build()
			)
		);
	}

	@Test
	public void testMatchedFieldsWithoutSourceAreRefused() throws IOException {
		var index = productsWithoutSource();

		var e = assertThrows(
			IndexException.class,
			() -> index.search(
				SearchRequest.create()
					.addMatched(
						"variants",
						new SearchRequest.Matched(
							SearchRequest.Matched.DEFAULT_LIMIT,
							Sets.immutable.of("variants.color")
						)
					)
					.build()
			)
		);

		assertThat(e.getCode(), is("index:query:source_not_kept"));
	}

	@Test
	public void testMatchedStaysAlignedAfterAPartialUpdate() throws IOException {
		var index = products();

		/*
		 * A patch rewrites the whole block from the merged copy, reordering
		 * fields but never the values within one - which is what keeps the
		 * position found among the children naming the same value in the copy.
		 */
		index.updateDocument(
			DocumentPatch.replacing(
				org.eclipse.collections.api.factory.Sets.immutable.of("id", "name"),
				org.eclipse.collections.api.factory.Lists.immutable.of(
					new Document.Value("id", "1"),
					new Document.Value("name", "Trail Runner II")
				)
			)
		);
		index.commit();

		var result = index.search(
			SearchRequest.create()
				.withQuery(
					Query.nested(
						"variants",
						Query.field("variants.color", Matchers.equalTo("red"))
					)
				)
				.addMatched("variants")
				.build()
		);

		assertThat(
			matchedOf(result, "1", "variants")
				.values()
				.collect(value -> value.get("price")),
			contains(15d, 35d)
		);
	}

	@Test
	public void testMatchedAnswersAcrossSegments() throws IOException {
		var index = create("segments", definition());

		// A commit per document, so the walk crosses segments
		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Trail Runner"),
				new Document.Value("variants", variant("red", "leather", 15d)),
				new Document.Value("variants", variant("blue", "canvas", 25d))
			)
		);
		index.commit();

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "City Sneaker"),
				new Document.Value("variants", variant("black", "suede", 30d)),
				new Document.Value("variants", variant("red", "canvas", 10d))
			)
		);
		index.commit();

		var result = index.search(
			SearchRequest.create()
				.withQuery(
					Query.nested(
						"variants",
						Query.field("variants.color", Matchers.equalTo("red"))
					)
				)
				.addMatched("variants")
				.build()
		);

		assertThat(
			matchedOf(result, "1", "variants").values().getFirst().get("price"),
			is(15d)
		);
		assertThat(
			matchedOf(result, "2", "variants").values().getFirst().get("price"),
			is(10d)
		);
	}

	@Test
	public void testNotAskingLeavesEveryHitWithout() throws IOException {
		var index = products();

		var result = index.search(SearchRequest.all());

		assertThat(result.hits().getFirst().matched().isEmpty(), is(true));
	}

	private static TextMatcher words(String text) {
		return TextMatcher.of(text).withPrefix(TextMatcher.Prefix.OFF);
	}

	private Index products() throws IOException {
		var index = create("products", definition());
		addProducts(index);
		return index;
	}

	private Index productsWithoutSource() throws IOException {
		var index = create(
			"bare",
			definition().setSource(IndexDef.SourceMode.SOURCE_MODE_NONE)
		);
		addProducts(index);
		return index;
	}

	private static IndexDef.Builder definition() {
		return IndexDef.newBuilder()
			.putFields("id", string().setPrimaryKey(true).build())
			.putFields(
				"name",
				string(
					StringFieldTypeDef.newBuilder()
						.setMatching(StringFieldTypeDef.TextUsageConfig.getDefaultInstance())
				).build()
			)
			.putFields("variants", objectField(ObjectFieldTypeDef.Mode.MODE_NESTED))
			.putFields("badges", badgesField(ObjectFieldTypeDef.Mode.MODE_NESTED))
			.putFields("specs", badgesField(ObjectFieldTypeDef.Mode.MODE_FLATTENED));
	}

	private static FieldDef objectField(ObjectFieldTypeDef.Mode mode) {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setObject(
					ObjectFieldTypeDef.newBuilder()
						.putFields(
							"color",
							string()
								.setFilter(FilterConfig.getDefaultInstance())
								.setFacet(FacetConfig.getDefaultInstance())
								.build()
						)
						.putFields(
							"material",
							string(
								StringFieldTypeDef.newBuilder().setMatching(
									StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
								)
							).build()
						)
						.putFields(
							"price",
							FieldDef.newBuilder()
								.setType(
									FieldTypeDef.newBuilder().setDouble(
										DoubleFieldTypeDef.getDefaultInstance()
									)
								)
								.setFilter(FilterConfig.getDefaultInstance())
								.build()
						)
						.setMode(mode)
				)
			)
			.setMultiple(true)
			.build();
	}

	private static FieldDef badgesField(ObjectFieldTypeDef.Mode mode) {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setObject(
					ObjectFieldTypeDef.newBuilder()
						.putFields(
							"label",
							string().setFilter(FilterConfig.getDefaultInstance()).build()
						)
						.setMode(mode)
				)
			)
			.setMultiple(true)
			.build();
	}

	private static void addProducts(Index index) throws IOException {
		/*
		 * The badges sit between the variants, so a variant's position among
		 * the variants differs from its position in the block.
		 */
		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Trail Runner"),
				new Document.Value("variants", variant("red", "waterproof leather", 15d)),
				new Document.Value("badges", badge("eco")),
				new Document.Value("variants", variant("black", "canvas", 25d)),
				new Document.Value("badges", badge("award")),
				new Document.Value("variants", variant("red", "waterproof canvas", 35d))
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "City Sneaker"),
				new Document.Value("variants", variant("red", "canvas", 30d)),
				new Document.Value("variants", variant("blue", "suede", 10d))
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "3"),
				new Document.Value("name", "Plain Sandal")
			)
		);

		index.commit();
	}

	private static FieldDef.Builder string() {
		return string(StringFieldTypeDef.newBuilder());
	}

	private static FieldDef.Builder string(StringFieldTypeDef.Builder type) {
		return FieldDef.newBuilder().setType(FieldTypeDef.newBuilder().setString(type));
	}

	private static Document variant(String color, String material, double price) {
		return new Document(
			new Document.Value("color", color),
			new Document.Value("material", material),
			new Document.Value("price", price)
		);
	}

	private static Document badge(String label) {
		return new Document(new Document.Value("label", label));
	}

	private static SearchResult.Hit hitOf(SearchResult result, String id) {
		return result.hits().detect(hit -> id.equals(hit.id()));
	}

	private static SearchResult.Matched matchedOf(
		SearchResult result,
		String id,
		String path
	) {
		return hitOf(result, id).matched().get(path);
	}
}
