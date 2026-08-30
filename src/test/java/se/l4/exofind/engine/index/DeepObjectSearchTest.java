package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
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
import se.l4.exofind.engine.index.schema.SortConfig;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.SortBy;
import se.l4.exofind.engine.query.matchers.Matchers;

/**
 * Tests for objects declared inside objects - a chain of single objects and
 * flattened lists folding into the document, and a nested list sitting below
 * or holding such a chain.
 */
public class DeepObjectSearchTest extends AbstractIndexTest {
	@Test
	public void testFilterOnAFieldBelowTwoObjects() throws IOException {
		var index = catalog();

		var result = search(
			index,
			Query.field("product.dims.width", Matchers.lessThan(15d))
		);

		assertThat(ids(result), contains("1"));
	}

	@Test
	public void testSortByAFieldBelowTwoObjects() throws IOException {
		var index = catalog();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.field("product.dims.width", Matchers.greaterThan(0d)))
				.withSort(SortBy.field("product.dims.width", SortBy.Order.DESCENDING))
				.build()
		);

		assertThat(ids(result), contains("2", "1"));
	}

	@Test
	public void testFlattenedListBelowAnObjectMatchesIndependently() throws IOException {
		var index = catalog();

		var result = search(
			index,
			Query.field("product.tags.label", Matchers.equalTo("summer"))
		);

		assertThat(ids(result), contains("1"));
	}

	@Test
	public void testNestedClauseOnAListBelowAnObject() throws IOException {
		var index = catalog();

		/*
		 * Both products have a red variant, but only product 1's red variant
		 * is wide enough - the conditions hold inside one value, reached
		 * through the object above the list and the object inside it.
		 */
		var result = search(
			index,
			Query.nested(
				"product.variants",
				Query.field("product.variants.color", Matchers.equalTo("red")),
				Query.field("product.variants.size.eu", Matchers.greaterThan(41d))
			)
		);

		assertThat(ids(result), contains("1"));
	}

	@Test
	public void testDocumentComesBackWithItsShape() throws IOException {
		var index = catalog();

		var document = index.getDocument("1");
		var product = (Document) document.get("product");
		var dims = (Document) product.get("dims");
		assertThat(dims.get("width"), is(10d));

		var variants = product.getAll("variants");
		assertThat(variants.size(), is(2));
		var size = (Document) ((Document) variants.get(0)).get("size");
		assertThat(size.get("eu"), is(42d));
	}

	@Test
	public void testOnlyTheFieldsAskedForComeBack() throws IOException {
		var index = catalog();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.field("product.dims.width", Matchers.lessThan(15d)))
				.withFields("product.dims.width")
				.build()
		);

		var hit = result.hits().get(0);
		var product = (Document) hit.document().get("product");
		var dims = (Document) product.get("dims");
		assertThat(dims.get("width"), is(10d));
		assertThat(dims.get("note"), is(nullValue()));
		assertThat(product.get("variants"), is(nullValue()));
		assertThat(product.get("tags"), is(nullValue()));
	}

	@Test
	public void testAskingForAFieldInsideANestedValueBelowAnObject() throws IOException {
		var index = catalog();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.field("product.dims.width", Matchers.lessThan(15d)))
				.withFields("product.variants.color")
				.build()
		);

		var product = (Document) result.hits().get(0).document().get("product");
		var variants = product.getAll("variants");
		assertThat(variants.size(), is(2));
		assertThat(((Document) variants.get(0)).get("color"), is("red"));
		assertThat(((Document) variants.get(0)).get("sku"), is(nullValue()));
	}

	@Test
	public void testMatchedValuesOnAListBelowAnObject() throws IOException {
		var index = catalog();

		var result = index.search(
			SearchRequest.create()
				.withQuery(
					Query.nested(
						"product.variants",
						Query.field("product.variants.color", Matchers.equalTo("red"))
					)
				)
				.addMatched("product.variants")
				.build()
		);

		var hit = result.hits().detect(h -> "1".equals(h.id()));
		var matched = hit.matched().get("product.variants");
		assertThat(matched.totalValues(), is(1));
		assertThat(matched.values().getFirst().get("sku"), is("A"));
	}

	@Test
	public void testValueHitsOnAListBelowAnObject() throws IOException {
		var index = catalog();

		var result = index.search(
			SearchRequest.create()
				.withQuery(
					Query.nested(
						"product.variants",
						Query.field("product.variants.color", Matchers.equalTo("red"))
					)
				)
				.withHits("product.variants")
				.build()
		);

		assertThat(result.hits().size(), is(2));

		var first = result.hits().detect(h -> "1".equals(h.id()));
		assertThat(first.index(), is(0));
		assertThat(first.valueKey(), is("A"));
		assertThat(first.value().get("color"), is("red"));

		var size = (Document) first.value().get("size");
		assertThat(size.get("eu"), is(42d));
	}

	@Test
	public void testKeyIsUniquePerListHoweverDeepItSits() throws IOException {
		var index = catalog();

		var e = assertThrows(
			ValidationException.class,
			() -> index.addDocument(
				new Document(
					new Document.Value("id", "4"),
					new Document.Value(
						"product",
						new Document(
							variant("X", "green", 41d),
							variant("X", "black", 43d)
						)
					)
				)
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:update:object:key_duplicate"));
	}

	@Test
	public void testRequiredHoldsInEveryValueHoweverDeep() throws IOException {
		var index = catalog();

		var e = assertThrows(
			ValidationException.class,
			() -> index.addDocument(
				new Document(
					new Document.Value("id", "4"),
					new Document.Value(
						"product",
						new Document(
							new Document.Value(
								"variants",
								new Document(new Document.Value("color", "green"))
							)
						)
					)
				)
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:update:required_field_missing"));
	}

	@Test
	public void testDeepPathCanNotBeWrittenAtTheRoot() throws IOException {
		var index = catalog();

		var e = assertThrows(
			ValidationException.class,
			() -> index.addDocument(
				new Document(
					new Document.Value("id", "4"),
					new Document.Value("product.dims.width", 12d)
				)
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:update:field_inside_object"));
	}

	@Test
	public void testFieldOfADeeperObjectIsNotAFieldOfTheOuter() throws IOException {
		var index = catalog();

		// `dims.width` reaches past the object the value was given to
		var e = assertThrows(
			ValidationException.class,
			() -> index.addDocument(
				new Document(
					new Document.Value("id", "4"),
					new Document.Value(
						"product",
						new Document(new Document.Value("dims.width", 12d))
					)
				)
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:update:field_not_found"));
	}

	@Test
	public void testScalarValueForAnObjectFieldIsRefused() throws IOException {
		var index = catalog();

		var e = assertThrows(
			ValidationException.class,
			() -> index.addDocument(
				new Document(
					new Document.Value("id", "4"),
					new Document.Value(
						"product",
						new Document(new Document.Value("dims", "wide"))
					)
				)
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:update:not_a_document"));
	}

	/**
	 * Two products under a single {@code product} object: {@code dims} is a
	 * single object below it, {@code tags} a flattened list, and
	 * {@code variants} a nested list whose values hold a {@code size} object
	 * of their own.
	 */
	private Index catalog() throws IOException {
		var index = create(
			"catalog",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"product",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder().setObject(
								ObjectFieldTypeDef.newBuilder()
									.putFields(
										"dims",
										FieldDef.newBuilder()
											.setType(
												FieldTypeDef.newBuilder().setObject(
													ObjectFieldTypeDef.newBuilder()
														.putFields(
															"width",
															doubleField()
																.setFilter(FilterConfig.getDefaultInstance())
																.setSort(SortConfig.getDefaultInstance())
																.build()
														)
														.putFields(
															"note",
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
									.putFields(
										"tags",
										FieldDef.newBuilder()
											.setType(
												FieldTypeDef.newBuilder().setObject(
													ObjectFieldTypeDef.newBuilder()
														.putFields(
															"label",
															string()
																.setFilter(FilterConfig.getDefaultInstance())
																.setRequired(true)
																.build()
														)
														.setMode(ObjectFieldTypeDef.Mode.MODE_FLATTENED)
												)
											)
											.setMultiple(true)
											.build()
									)
									.putFields(
										"variants",
										FieldDef.newBuilder()
											.setType(
												FieldTypeDef.newBuilder().setObject(
													ObjectFieldTypeDef.newBuilder()
														.putFields(
															"sku",
															string().setRequired(true).build()
														)
														.putFields(
															"color",
															string()
																.setFilter(FilterConfig.getDefaultInstance())
																.build()
														)
														.putFields(
															"size",
															FieldDef.newBuilder()
																.setType(
																	FieldTypeDef.newBuilder().setObject(
																		ObjectFieldTypeDef.newBuilder()
																			.putFields(
																				"eu",
																				doubleField()
																					.setFilter(FilterConfig.getDefaultInstance())
																					.build()
																			)
																	)
																)
																.build()
														)
														.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
														.setKey("sku")
												)
											)
											.setMultiple(true)
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
				new Document.Value(
					"product",
					new Document(
						new Document.Value(
							"dims",
							new Document(
								new Document.Value("width", 10d),
								new Document.Value("note", "fits most hiking feet")
							)
						),
						new Document.Value(
							"tags",
							new Document(new Document.Value("label", "red"))
						),
						new Document.Value(
							"tags",
							new Document(new Document.Value("label", "summer"))
						),
						variant("A", "red", 42d),
						variant("B", "blue", 44d)
					)
				)
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value(
					"product",
					new Document(
						new Document.Value(
							"dims",
							new Document(new Document.Value("width", 20d))
						),
						variant("C", "red", 40d)
					)
				)
			)
		);

		index.addDocument(
			new Document(new Document.Value("id", "3"))
		);

		index.commit();
		return index;
	}

	private static Document.Value variant(String sku, String color, double eu) {
		return new Document.Value(
			"variants",
			new Document(
				new Document.Value("sku", sku),
				new Document.Value("color", color),
				new Document.Value(
					"size",
					new Document(new Document.Value("eu", eu))
				)
			)
		);
	}

	private static FieldDef.Builder string() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setString(StringFieldTypeDef.getDefaultInstance())
			);
	}

	private static FieldDef.Builder string(StringFieldTypeDef.Builder type) {
		return FieldDef.newBuilder()
			.setType(FieldTypeDef.newBuilder().setString(type));
	}

	private static FieldDef.Builder doubleField() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setDouble(DoubleFieldTypeDef.getDefaultInstance())
			);
	}

	private static SearchResult search(Index index, Query... query) throws IOException {
		return index.search(SearchRequest.create().withQuery(query).build());
	}

	private static List<Object> ids(SearchResult result) {
		return result.hits().collect(SearchResult.Hit::id).toList();
	}
}
