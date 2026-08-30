package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.schema.DoubleFieldTypeDef;
import se.l4.exofind.engine.index.schema.FacetConfig;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.ObjectFieldTypeDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.matchers.Matchers;

/**
 * Tests for wildcards in the names an object field is made of - a name inside
 * an object, and the name of the object itself.
 *
 * <p>What is being checked is that a dynamic name behaves as a declared one
 * does everywhere it is used: the values of a nested object are still told
 * apart one from another, a flattened object still folds into the document,
 * and both still refuse a name that matches nothing.
 */
public class WildcardObjectSearchTest extends AbstractIndexTest {
	/**
	 * The point of a pattern inside a nested object: attributes nobody
	 * declared, holding together within a single variant.
	 */
	@Test
	public void testDynamicAttributesMatchInsideOneValue() throws IOException {
		var index = products();

		var result = search(
			index,
			Query.nested(
				"variants",
				Query.field("variants.attr.color", Matchers.equalTo("red")),
				Query.field("variants.attr.size", Matchers.equalTo("L"))
			)
		);

		assertThat(ids(result), contains("1"));
	}

	@Test
	public void testDynamicAttributesAcrossValuesDoNotMatch() throws IOException {
		var index = products();

		/*
		 * Product 2 holds a red variant in S and a blue one in L, so no single
		 * variant is both blue and S however well the document as a whole
		 * holds all four.
		 */
		var result = search(
			index,
			Query.nested(
				"variants",
				Query.field("variants.attr.color", Matchers.equalTo("blue")),
				Query.field("variants.attr.size", Matchers.equalTo("S"))
			)
		);

		assertThat(ids(result), is(empty()));
	}

	@Test
	public void testDynamicAttributeCanBeCounted() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.addFacet(Facet.of("variants.attr.color"))
				.build()
		);

		var facet = result.facets().get("variants.attr.color");
		assertThat(
			facet.values(),
			containsInAnyOrder(
				new SearchResult.Facet.Value("red", 2),
				new SearchResult.Facet.Value("blue", 1)
			)
		);
	}

	@Test
	public void testDeclaredFieldInsideAnObjectWinsOverThePattern() throws IOException {
		var index = products();

		/*
		 * `sku` is declared, so it keeps its own settings however well the
		 * pattern beside it would have matched the name.
		 */
		var result = search(
			index,
			Query.nested("variants", Query.field("variants.sku", Matchers.equalTo("1-red")))
		);

		assertThat(ids(result), contains("1"));
	}

	@Test
	public void testNameMatchingNoPatternInsideAnObjectIsRefused() throws IOException {
		var index = products();

		/*
		 * The only pattern sits inside `attr`, so a name under no namespace at
		 * all belongs to no field of the object.
		 */
		var e = assertThrows(
			ValidationException.class,
			() -> index.addDocument(
				new Document(
					new Document.Value("id", "9"),
					new Document.Value(
						"variants",
						new Document(
							new Document.Value("sku", "9-red"),
							new Document.Value("colour", "red")
						)
					)
				)
			)
		);

		assertThat(
			e.getErrors().collect(error -> error.getCode()).toList(),
			hasItem("index:update:field_not_found")
		);
	}

	@Test
	public void testPatternInsideAnObjectDoesNotCrossADot() throws IOException {
		var index = products();

		assertThrows(
			ValidationException.class,
			() -> index.addDocument(
				new Document(
					new Document.Value("id", "9"),
					new Document.Value(
						"variants",
						new Document(
							new Document.Value(
								"attr",
								new Document(new Document.Value("a.b", "red"))
							)
						)
					)
				)
			)
		);
	}

	/**
	 * An object whose own name is a pattern keeps the values of each name it
	 * matched apart, which is what a {@code nested} clause on one of them
	 * relies on.
	 */
	@Test
	public void testWildcardObjectsDoNotShareTheirValues() throws IOException {
		var index = specs();

		var matching = search(
			index,
			Query.nested(
				"spec.weight",
				Query.field("spec.weight.value", Matchers.equalTo("180")),
				Query.field("spec.weight.unit", Matchers.equalTo("g"))
			)
		);

		assertThat(ids(matching), contains("1"));

		/*
		 * The document holds `180` under weight and `g` under nothing else, so
		 * a clause asking both of the voltage entry matches no value of it.
		 */
		var crossed = search(
			index,
			Query.nested(
				"spec.voltage",
				Query.field("spec.voltage.value", Matchers.equalTo("180")),
				Query.field("spec.voltage.unit", Matchers.equalTo("g"))
			)
		);

		assertThat(ids(crossed), is(empty()));
	}

	@Test
	public void testWildcardObjectIsHandedBackAsItWasGiven() throws IOException {
		var index = specs();

		var doc = index.getDocument("1");
		var spec = (Document) doc.get("spec");
		var weight = (Document) spec.get("weight");

		assertThat(weight.get("value"), is("180"));
		assertThat(weight.get("unit"), is("g"));
	}

	/**
	 * A flattened object folds into the document, so a dynamic name inside it
	 * answers as a field of the index under its dotted path.
	 */
	@Test
	public void testDynamicAttributeInAFlattenedObjectIsAFieldOfTheIndex() throws IOException {
		var index = products();

		var result = search(
			index,
			Query.field("dimensions.extra.finish", Matchers.equalTo("matte"))
		);

		assertThat(ids(result), contains("1"));
	}

	/**
	 * Values of a flattened object are given inside it whichever name they
	 * resolve by, so that there is one way to write a thing.
	 */
	@Test
	public void testFlattenedPathCanNotBeWrittenAtTheRoot() throws IOException {
		var index = products();

		var e = assertThrows(
			ValidationException.class,
			() -> index.addDocument(
				new Document(
					new Document.Value("id", "9"),
					new Document.Value("dimensions.extra.finish", "matte")
				)
			)
		);

		assertThat(
			e.getErrors().collect(error -> error.getCode()).toList(),
			contains("index:update:field_inside_object")
		);
	}

	/**
	 * Products whose variants carry attributes the definition never names, and
	 * whose dimensions fold into the document with a namespace of their own.
	 */
	private Index products() throws IOException {
		var index = create(
			"products",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"variants",
					FieldDef.newBuilder()
						.setMultiple(true)
						.setType(
							FieldTypeDef.newBuilder().setObject(
								ObjectFieldTypeDef.newBuilder()
									.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
									.setKey("sku")
									.putFields(
										"sku",
										string()
											.setRequired(true)
											.setFilter(FilterConfig.getDefaultInstance())
											.build()
									)
									.putFields(
										"attr",
										FieldDef.newBuilder()
											.setType(
												FieldTypeDef.newBuilder().setObject(
													ObjectFieldTypeDef.newBuilder().putFields(
														"*",
														string()
															.setFilter(FilterConfig.getDefaultInstance())
															.setFacet(FacetConfig.getDefaultInstance())
															.build()
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
									.putFields(
										"extra",
										FieldDef.newBuilder()
											.setType(
												FieldTypeDef.newBuilder().setObject(
													ObjectFieldTypeDef.newBuilder().putFields(
														"*",
														string()
															.setFilter(FilterConfig.getDefaultInstance())
															.build()
													)
												)
											)
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
					"variants",
					new Document(
						new Document.Value("sku", "1-red"),
						new Document.Value(
							"attr",
							new Document(
								new Document.Value("color", "red"),
								new Document.Value("size", "L")
							)
						)
					)
				),
				new Document.Value(
					"dimensions",
					new Document(
						new Document.Value("width", 12d),
						new Document.Value(
							"extra",
							new Document(new Document.Value("finish", "matte"))
						)
					)
				)
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value(
					"variants",
					new Document(
						new Document.Value("sku", "2-red"),
						new Document.Value(
							"attr",
							new Document(
								new Document.Value("color", "red"),
								new Document.Value("size", "S")
							)
						)
					)
				),
				new Document.Value(
					"variants",
					new Document(
						new Document.Value("sku", "2-blue"),
						new Document.Value(
							"attr",
							new Document(
								new Document.Value("color", "blue"),
								new Document.Value("size", "L")
							)
						)
					)
				)
			)
		);

		index.commit();
		return index;
	}

	/**
	 * Specifications whose group name is dynamic - the object itself is named
	 * by a pattern, and each name it matches holds a value and a unit that
	 * belong together.
	 */
	private Index specs() throws IOException {
		var index = create(
			"specs",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"spec",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder().setObject(
								ObjectFieldTypeDef.newBuilder().putFields(
									"*",
									FieldDef.newBuilder()
										.setType(
											FieldTypeDef.newBuilder().setObject(
												ObjectFieldTypeDef.newBuilder()
													.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
													.putFields(
														"value",
														string()
															.setFilter(FilterConfig.getDefaultInstance())
															.build()
													)
													.putFields(
														"unit",
														string()
															.setFilter(FilterConfig.getDefaultInstance())
															.build()
													)
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
					"spec",
					new Document(
						new Document.Value(
							"weight",
							new Document(
								new Document.Value("value", "180"),
								new Document.Value("unit", "g")
							)
						),
						new Document.Value(
							"voltage",
							new Document(
								new Document.Value("value", "230"),
								new Document.Value("unit", "V")
							)
						)
					)
				)
			)
		);

		index.commit();
		return index;
	}

	private static FieldDef.Builder string() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setString(StringFieldTypeDef.getDefaultInstance())
			);
	}

	private static SearchResult search(Index index, Query... query) throws IOException {
		return index.search(SearchRequest.create().withQuery(query).build());
	}

	private static List<Object> ids(SearchResult result) {
		return result.hits().collect(SearchResult.Hit::id).toList();
	}
}
