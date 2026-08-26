package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.schema.DoubleFieldTypeDef;
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
 * Tests for object fields - documents whose values are documents of their own,
 * matched one value at a time through the {@code nested} clause.
 */
public class NestedSearchTest extends AbstractIndexTest {
	@Test
	public void testConditionsHoldInsideTheSameValue() throws IOException {
		var index = products();

		var result = search(
			index,
			Query.nested(
				"variants",
				Query.field("variants.color", Matchers.equalTo("red")),
				Query.field("variants.price", Matchers.lessThan(20d))
			)
		);

		assertThat(ids(result), contains("1"));
	}

	@Test
	public void testConditionsAcrossValuesDoNotMatch() throws IOException {
		var index = products();

		/*
		 * Product 2 has a red variant at 30 and a blue one at 10, so no single
		 * variant is both red and under 20.
		 */
		var result = search(
			index,
			Query.nested(
				"variants",
				Query.field("variants.color", Matchers.equalTo("blue")),
				Query.field("variants.price", Matchers.between(20d, 40d))
			)
		);

		assertThat(ids(result), is(empty()));
	}

	@Test
	public void testListingReturnsOnlyDocuments() throws IOException {
		var index = products();

		var result = index.search(SearchRequest.all());

		assertThat(result.total().count(), is(3L));
		assertThat(ids(result), containsInAnyOrder("1", "2", "3"));
	}

	@Test
	public void testExclusionReturnsOnlyDocuments() throws IOException {
		var index = products();

		var result = search(
			index,
			Query.not(Query.field("category", Matchers.equalTo("shoes")))
		);

		assertThat(ids(result), containsInAnyOrder("2", "3"));
	}

	@Test
	public void testTypedExclusionAloneReturnsOnlyDocuments() throws IOException {
		var index = products();

		var result = search(
			index,
			Query.text(TextMatcher.of("-sneaker").withMatch(TextMatcher.Match.USER))
		);

		assertThat(ids(result), containsInAnyOrder("1", "3"));
	}

	@Test
	public void testConditionBesideANestedClauseReturnsOnlyDocuments() throws IOException {
		var index = products();

		var result = search(
			index,
			Query.field("category", Matchers.equalTo("shoes")),
			Query.nested("variants", Query.field("variants.color", Matchers.equalTo("red")))
		);

		assertThat(ids(result), contains("1"));
	}

	@Test
	public void testEmptyNestedMatchesDocumentsWithAValue() throws IOException {
		var index = products();

		var result = search(index, Query.nested("variants"));

		assertThat(ids(result), containsInAnyOrder("1", "2"));
	}

	@Test
	public void testOrInsideNested() throws IOException {
		var index = products();

		var result = search(
			index,
			Query.nested(
				"variants",
				Query.or(
					Query.field("variants.color", Matchers.equalTo("green")),
					Query.field("variants.color", Matchers.equalTo("blue"))
				),
				Query.field("variants.price", Matchers.lessThan(20d))
			)
		);

		assertThat(ids(result), contains("2"));
	}

	@Test
	public void testExclusionAloneInsideNested() throws IOException {
		var index = products();

		/*
		 * An exclusion matches by what it does not name, so the values it can
		 * match have to be pinned to the path some other way - products 1 and 2
		 * each hold a variant that is not red, product 3 holds no variants.
		 */
		var result = search(
			index,
			Query.nested(
				"variants",
				Query.not(Query.field("variants.color", Matchers.equalTo("red")))
			)
		);

		assertThat(ids(result), containsInAnyOrder("1", "2"));
	}

	@Test
	public void testExclusionBesideAConditionInsideNested() throws IOException {
		var index = products();

		/*
		 * Product 1's red variant comes in S and M, product 2's red variant
		 * names no size at all - both are red variants that are not XL.
		 */
		var result = search(
			index,
			Query.nested(
				"variants",
				Query.field("variants.color", Matchers.equalTo("red")),
				Query.not(Query.field("variants.sizes", Matchers.equalTo("XL")))
			)
		);

		assertThat(ids(result), containsInAnyOrder("1", "2"));
	}

	@Test
	public void testSeveralValuesOfAnInnerField() throws IOException {
		var index = products();

		/*
		 * Product 1 has the red variant in S and M and the black one in XL, so
		 * red in XL matches nothing even though both terms are in the product.
		 */
		var noCrossMatch = search(
			index,
			Query.nested(
				"variants",
				Query.field("variants.color", Matchers.equalTo("red")),
				Query.field("variants.sizes", Matchers.equalTo("XL"))
			)
		);
		assertThat(ids(noCrossMatch), is(empty()));

		var sameVariant = search(
			index,
			Query.nested(
				"variants",
				Query.field("variants.color", Matchers.equalTo("black")),
				Query.field("variants.sizes", Matchers.equalTo("XL"))
			)
		);
		assertThat(ids(sameVariant), contains("1"));
	}

	@Test
	public void testUpdateReplacesEveryValue() throws IOException {
		var index = products();

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("category", "shirts"),
				new Document.Value("variants", variant("yellow", 5d))
			)
		);
		index.commit();

		/*
		 * The red variant belonged to the replaced version of product 1, so it
		 * must no longer answer for it - only product 2 still has one.
		 */
		var red = search(
			index,
			Query.nested("variants", Query.field("variants.color", Matchers.equalTo("red")))
		);
		assertThat(ids(red), contains("2"));

		var yellow = search(
			index,
			Query.nested("variants", Query.field("variants.color", Matchers.equalTo("yellow")))
		);
		assertThat(ids(yellow), contains("1"));

		assertThat(index.search(SearchRequest.all()).total().count(), is(3L));
	}

	@Test
	public void testDocumentComesBackWithItsValues() throws IOException {
		var index = products();

		var document = index.getDocument("1");

		var variants = document.getAll("variants");
		assertThat(variants.size(), is(2));
		assertThat(variants.get(0), instanceOf(Document.class));
		assertThat(((Document) variants.get(0)).get("color"), is("red"));
		assertThat(((Document) variants.get(0)).get("price"), is(15d));
		assertThat(((Document) variants.get(1)).get("color"), is("black"));
	}

	@Test
	public void testHitsCarryTheirValues() throws IOException {
		var index = products();

		var result = search(
			index,
			Query.nested("variants", Query.field("variants.color", Matchers.equalTo("black")))
		);

		assertThat(result.hits().size(), is(1));
		var variants = result.hits().get(0).document().getAll("variants");
		assertThat(variants.size(), is(2));
		assertThat(((Document) variants.get(0)).get("color"), is("red"));
	}

	@Test
	public void testOnlyTheFieldsAskedForInsideAValueAreReturned() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.field("category", Matchers.equalTo("shoes")))
				.withFields("variants.price")
				.build()
		);

		var hit = result.hits().get(0);
		assertThat(hit.id(), is("1"));
		assertThat(hit.document().get("name"), is(nullValue()));

		// Every value is still there, cut down to what was asked for inside it
		var variants = hit.document().getAll("variants");
		assertThat(variants.size(), is(2));
		assertThat(((Document) variants.get(0)).get("price"), is(15d));
		assertThat(((Document) variants.get(0)).get("color"), is(nullValue()));
		assertThat(((Document) variants.get(1)).get("price"), is(25d));
	}

	@Test
	public void testAskingForTheObjectItselfReturnsEverythingInsideIt() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.field("category", Matchers.equalTo("shoes")))
				.withFields("variants.price", "variants")
				.build()
		);

		var variants = result.hits().get(0).document().getAll("variants");
		assertThat(((Document) variants.get(0)).get("color"), is("red"));
		assertThat(((Document) variants.get(0)).get("price"), is(15d));
	}

	@Test
	public void testTextSearchStillFindsDocuments() throws IOException {
		var index = products();

		var result = search(index, Query.text("runner"));

		assertThat(ids(result), contains("1"));
	}

	@Test
	public void testInnerFieldOutsideNestedIsRefused() throws IOException {
		var index = products();

		var e = assertThrows(
			IndexException.class,
			() -> search(index, Query.field("variants.color", Matchers.equalTo("red")))
		);

		assertThat(e.getCode(), is("index:query:nested:outside"));
	}

	@Test
	public void testRootFieldInsideNestedIsRefused() throws IOException {
		var index = products();

		var e = assertThrows(
			IndexException.class,
			() -> search(
				index,
				Query.nested("variants", Query.field("category", Matchers.equalTo("shoes")))
			)
		);

		assertThat(e.getCode(), is("index:query:nested:not_in_path"));
	}

	@Test
	public void testNestedOnNonObjectFieldIsRefused() throws IOException {
		var index = products();

		assertThrows(
			IndexInvalidQueryTypeException.class,
			() -> search(
				index,
				Query.nested("category", Query.field("variants.color", Matchers.equalTo("red")))
			)
		);
	}

	@Test
	public void testClauseThatCannotRunAgainstAValueIsRefused() throws IOException {
		var index = products();

		var e = assertThrows(
			IndexException.class,
			() -> search(
				index,
				Query.nested("variants", Query.knn("variants.color", new float[] { 1f }, 3))
			)
		);

		assertThat(e.getCode(), is("index:query:nested:unsupported_clause"));
	}

	@Test
	public void testValueThatIsNotADocumentIsRefused() throws IOException {
		var index = products();

		var e = assertThrows(
			ValidationException.class,
			() -> index.addDocument(
				new Document(
					new Document.Value("id", "4"),
					new Document.Value("category", "shirts"),
					new Document.Value("variants", "red")
				)
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:update:not_a_document"));
	}

	@Test
	public void testDocumentValueForOtherFieldIsRefused() throws IOException {
		var index = products();

		var e = assertThrows(
			ValidationException.class,
			() -> index.addDocument(
				new Document(
					new Document.Value("id", "4"),
					new Document.Value("category", variant("red", 1d))
				)
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:update:unexpected_document"));
	}

	@Test
	public void testRequiredInnerFieldMissing() throws IOException {
		var index = products();

		var e = assertThrows(
			ValidationException.class,
			() -> index.addDocument(
				new Document(
					new Document.Value("id", "4"),
					new Document.Value("category", "shirts"),
					new Document.Value(
						"variants",
						new Document(new Document.Value("price", 10d))
					)
				)
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:update:required_field_missing"));
	}

	@Test
	public void testUnknownInnerFieldIsRefused() throws IOException {
		var index = products();

		var e = assertThrows(
			ValidationException.class,
			() -> index.addDocument(
				new Document(
					new Document.Value("id", "4"),
					new Document.Value("category", "shirts"),
					new Document.Value(
						"variants",
						new Document(
							new Document.Value("color", "red"),
							new Document.Value("weight", "heavy")
						)
					)
				)
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:update:field_not_found"));
	}

	@Test
	public void testSingleObjectFieldRefusesSeveralValues() throws IOException {
		var index = products();

		var e = assertThrows(
			ValidationException.class,
			() -> index.addDocument(
				new Document(
					new Document.Value("id", "4"),
					new Document.Value("category", "shirts"),
					new Document.Value(
						"dimensions",
						new Document(new Document.Value("width", 10d))
					),
					new Document.Value(
						"dimensions",
						new Document(new Document.Value("width", 20d))
					)
				)
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:update:not_multiple"));
	}

	private Document variant(String color, double price, String... sizes) {
		var values = new java.util.ArrayList<Document.Value>();
		values.add(new Document.Value("color", color));
		values.add(new Document.Value("price", price));
		for(var size : sizes) {
			values.add(new Document.Value("sizes", size));
		}

		return new Document(values.toArray(new Document.Value[0]));
	}

	private Index products() throws IOException {
		var index = create(
			"products",
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
				.putFields(
					"name",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder().setString(
								StringFieldTypeDef.newBuilder()
									.setMatching(
										StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
									)
							)
						)
						.build()
				)
				.putFields(
					"category",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setString(StringFieldTypeDef.getDefaultInstance())
						)
						.setFilter(FilterConfig.getDefaultInstance())
						.build()
				)
				.putFields(
					"variants",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder().setObject(
								ObjectFieldTypeDef.newBuilder()
									.putFields(
										"color",
										FieldDef.newBuilder()
											.setType(
												FieldTypeDef.newBuilder().setString(
													StringFieldTypeDef.getDefaultInstance()
												)
											)
											.setFilter(FilterConfig.getDefaultInstance())
											.setRequired(true)
											.build()
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
									.putFields(
										"sizes",
										FieldDef.newBuilder()
											.setType(
												FieldTypeDef.newBuilder().setString(
													StringFieldTypeDef.getDefaultInstance()
												)
											)
											.setFilter(FilterConfig.getDefaultInstance())
											.setMultiple(true)
											.build()
									)
									.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
							)
						)
						.setMultiple(true)
						.build()
				)
				.putFields(
					"dimensions",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder().setObject(
								ObjectFieldTypeDef.newBuilder()
									.putFields(
										"width",
										FieldDef.newBuilder()
											.setType(
												FieldTypeDef.newBuilder().setDouble(
													DoubleFieldTypeDef.getDefaultInstance()
												)
											)
											.setFilter(FilterConfig.getDefaultInstance())
											.build()
									)
							)
						)
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Trail Runner"),
				new Document.Value("category", "shoes"),
				new Document.Value(
					"variants",
					new Document(
						new Document.Value("color", "red"),
						new Document.Value("price", 15d),
						new Document.Value("sizes", "S"),
						new Document.Value("sizes", "M")
					)
				),
				new Document.Value(
					"variants",
					new Document(
						new Document.Value("color", "black"),
						new Document.Value("price", 25d),
						new Document.Value("sizes", "XL")
					)
				)
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "City Sneaker"),
				new Document.Value("category", "sneakers"),
				new Document.Value("variants", variant("red", 30d)),
				new Document.Value("variants", variant("blue", 10d))
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "3"),
				new Document.Value("name", "Plain Sandal"),
				new Document.Value("category", "sandals")
			)
		);

		index.commit();
		return index;
	}

	private static SearchResult search(Index index, Query... query) throws IOException {
		return index.search(SearchRequest.create().withQuery(query).build());
	}

	private static List<Object> ids(SearchResult result) {
		return result.hits().collect(SearchResult.Hit::id).toList();
	}
}
