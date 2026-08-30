package se.l4.exofind.engine.index.schema;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.errors.ValidationException;

/**
 * Tests for how a definition may shape an object field - which usages the
 * fields inside may carry, and how the schema resolves the dotted paths
 * through it.
 */
public class ObjectFieldTest {
	@Test
	public void testValidObjectDefinition() {
		var schema = new IndexSchema();
		schema.setDefinition(definition(variants()).build());

		assertThat(schema.hasNestedFields(), is(true));
		assertThat(schema.getField("variants").isPresent(), is(true));
		assertThat(schema.getField("variants").get().isObject(), is(true));

		var color = schema.getNestedField("variants.color");
		assertThat(color.isPresent(), is(true));
		assertThat(color.get().path(), is("variants"));
		assertThat(color.get().field().getName(), is("variants.color"));

		// Inner fields resolve through their path, never as root fields
		assertThat(schema.getField("variants.color").isPresent(), is(false));
		assertThat(schema.getNestedField("variants").isPresent(), is(false));
	}

	@Test
	public void testSchemaWithoutObjectsHasNoNestedFields() {
		var schema = new IndexSchema();
		schema.setDefinition(
			IndexDef.newBuilder()
				.putFields("name", string().build())
				.build()
		);

		assertThat(schema.hasNestedFields(), is(false));
	}

	@Test
	public void testObjectNeedsFields() {
		assertRefused(
			definition(FieldDef.newBuilder()
				.setType(FieldTypeDef.newBuilder().setObject(ObjectFieldTypeDef.newBuilder()))
			),
			"index:field:object:no_fields"
		);
	}

	@Test
	public void testObjectCanNotFilter() {
		assertRefused(
			definition(variants().setFilter(FilterConfig.getDefaultInstance())),
			"index:field:object:usage_not_supported"
		);
	}

	@Test
	public void testObjectCanNotSort() {
		assertRefused(
			definition(variants().setSort(SortConfig.getDefaultInstance())),
			"index:field:sorting_not_supported"
		);
	}

	@Test
	public void testObjectCanNotFacet() {
		assertRefused(
			definition(variants().setFacet(FacetConfig.getDefaultInstance())),
			"index:field:faceting_not_supported"
		);
	}

	@Test
	public void testObjectCanNotBeStored() {
		assertRefused(
			definition(variants().setStored(true)),
			"index:field:object:usage_not_supported"
		);
	}

	@Test
	public void testObjectCanNotBeLocaleSpecific() {
		assertRefused(
			definition(variants().setLocales(FieldDef.LocaleConfig.getDefaultInstance())),
			"index:field:object:usage_not_supported"
		);
	}

	@Test
	public void testObjectNameCanHoldWildcard() {
		var schema = new IndexSchema();
		schema.setDefinition(
			IndexDef.newBuilder()
				.putFields(
					"spec",
					singleObject(builder -> builder.putFields("*", variants().build()))
						.build()
				)
				.build()
		);

		assertThat(schema.hasNestedFields(), is(true));

		var object = schema.getField("spec.weight");
		assertThat(object.isPresent(), is(true));
		assertThat(object.get().isNestedObject(), is(true));

		/*
		 * The path a field inside carries is the name the object matched
		 * under, which is what keeps the values of one dynamic object apart
		 * from another's.
		 */
		var color = schema.getNestedField("spec.weight.color");
		assertThat(color.isPresent(), is(true));
		assertThat(color.get().path(), is("spec.weight"));

		var other = schema.getNestedField("spec.voltage.color");
		assertThat(other.isPresent(), is(true));
		assertThat(other.get().path(), is("spec.voltage"));

		// The pattern itself is not a name a document gives values under
		assertThat(schema.getNestedField("spec.color").isPresent(), is(false));
	}

	@Test
	public void testWildcardObjectCoversItsFields() {
		var schema = new IndexSchema();
		schema.setDefinition(
			IndexDef.newBuilder()
				.putFields(
					"spec",
					singleObject(builder -> builder.putFields("*", variants().build()))
						.build()
				)
				.build()
		);

		var inside = schema.getNestedFields("spec.weight");
		assertThat(inside.collect(Field::getName).toList(), hasItem("spec.*.color"));
	}

	@Test
	public void testInnerFieldCanNotBePrimaryKey() {
		assertInnerRefused(
			string().setPrimaryKey(true),
			"index:field:object:inner_usage_not_supported"
		);
	}

	@Test
	public void testInnerFieldCanSortFacetAndMatch() {
		var schema = new IndexSchema();

		schema.setDefinition(
			definition(
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder().setObject(
							ObjectFieldTypeDef.newBuilder()
								.putFields(
									"price",
									string()
										.setSort(SortConfig.getDefaultInstance())
										.setFacet(FacetConfig.getDefaultInstance())
										.build()
								)
								.putFields("material", matching().build())
								.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
						)
					)
					.setMultiple(true)
			).build()
		);

		var price = schema.getNestedField("variants.price").orElseThrow().field();
		assertThat(price.isSorted(), is(true));
		assertThat(price.isFaceted(), is(true));
	}

	@Test
	public void testInnerFieldUsagesBeyondFilteringRequireTheFeature() {
		var filtering = IndexFeatures.requiredBy(definition(variants()).build());
		assertThat(
			filtering.toList(),
			not(hasItem(IndexFeatures.TYPE_OBJECT_USAGES))
		);

		var counting = IndexFeatures.requiredBy(
			definition(
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder().setObject(
							ObjectFieldTypeDef.newBuilder()
								.putFields(
									"color",
									string().setFacet(FacetConfig.getDefaultInstance()).build()
								)
								.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
						)
					)
					.setMultiple(true)
			).build()
		);

		assertThat(counting.toList(), hasItem(IndexFeatures.TYPE_OBJECT_USAGES));
	}

	@Test
	public void testInnerFieldOfAFlattenedObjectCanBeHighlighted() {
		var schema = new IndexSchema();
		schema.setDefinition(
			definition(singleObject(builder -> builder.putFields(
				"note",
				highlightable().build()
			))).build()
		);

		assertThat(schema.getField("variants.note").isPresent(), is(true));

		var features = IndexFeatures.requiredBy(
			definition(singleObject(builder -> builder.putFields(
				"note",
				highlightable().build()
			))).build()
		);
		assertThat(features.toList(), hasItem(IndexFeatures.TYPE_OBJECT_HIGHLIGHT));
	}

	@Test
	public void testInnerFieldOfAFlattenedListCanBeHighlighted() {
		var schema = new IndexSchema();
		schema.setDefinition(
			definition(
				FieldDef.newBuilder()
					.setMultiple(true)
					.setType(
						FieldTypeDef.newBuilder().setObject(
							ObjectFieldTypeDef.newBuilder()
								.setMode(ObjectFieldTypeDef.Mode.MODE_FLATTENED)
								.putFields("note", highlightable().build())
						)
					)
			).build()
		);

		assertThat(schema.getField("variants.note").isPresent(), is(true));
	}

	@Test
	public void testInnerFieldOfANestedListCanBeHighlighted() {
		var nested = definition(
			FieldDef.newBuilder()
				.setMultiple(true)
				.setType(
					FieldTypeDef.newBuilder().setObject(
						ObjectFieldTypeDef.newBuilder()
							.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
							.putFields("note", highlightable().build())
					)
				)
		).build();

		var schema = new IndexSchema();
		schema.setDefinition(nested);
		assertThat(schema.getNestedField("variants.note").isPresent(), is(true));

		assertThat(
			IndexFeatures.requiredBy(nested).toList(),
			hasItem(IndexFeatures.TYPE_OBJECT_HIGHLIGHT_NESTED)
		);

		/*
		 * The same usage folded into the document asks for nothing beyond the
		 * highlight feature itself, so the name stays off definitions an
		 * older node serves correctly.
		 */
		var single = definition(singleObject(builder -> builder.putFields(
			"note",
			highlightable().build()
		))).build();

		assertThat(
			IndexFeatures.requiredBy(single).toList(),
			not(hasItem(IndexFeatures.TYPE_OBJECT_HIGHLIGHT_NESTED))
		);
	}

	@Test
	public void testInnerFieldOfASingleObjectCanBeStored() {
		var schema = new IndexSchema();
		schema.setDefinition(
			definition(
				singleObject(builder -> builder.putFields(
					"color",
					string().setStored(true).build()
				))
			).build()
		);

		assertThat(schema.getField("variants.color").orElseThrow().isStored(), is(true));
	}

	@Test
	public void testStoredRequiresItsOwnFeatureInsideAnObject() {
		var features = IndexFeatures.requiredBy(
			definition(
				singleObject(builder -> builder.putFields(
					"color",
					string().setStored(true).build()
				))
			).build()
		);

		assertThat(features.toList(), hasItem(IndexFeatures.TYPE_OBJECT_STORED));

		var without = IndexFeatures.requiredBy(definition(variants()).build());
		assertThat(without.toList(), not(hasItem(IndexFeatures.TYPE_OBJECT_STORED)));
	}

	@Test
	public void testInnerFieldOfAFlattenedListCanNotBeStored() {
		var flattened = FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setObject(
					ObjectFieldTypeDef.newBuilder()
						.putFields("color", string().setStored(true).build())
						.setMode(ObjectFieldTypeDef.Mode.MODE_FLATTENED)
				)
			)
			.setMultiple(true);

		assertRefused(definition(flattened), "index:field:object:flattened_stored");
	}

	@Test
	public void testStoredRefusedBelowAFlattenedListThroughAnObject() {
		assertRefused(
			definition(
				FieldDef.newBuilder()
					.setMultiple(true)
					.setType(
						FieldTypeDef.newBuilder().setObject(
							ObjectFieldTypeDef.newBuilder()
								.setMode(ObjectFieldTypeDef.Mode.MODE_FLATTENED)
								.putFields(
									"dims",
									singleObject(inner -> inner.putFields(
										"width",
										string().setStored(true).build()
									)).build()
								)
						)
					)
			),
			"index:field:object:flattened_stored"
		);
	}

	@Test
	public void testInnerFieldOfANestedListCanBeStored() {
		var schema = new IndexSchema();
		schema.setDefinition(
			definition(
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder().setObject(
							ObjectFieldTypeDef.newBuilder()
								.putFields("color", string().setStored(true).build())
								.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
						)
					)
					.setMultiple(true)
			).build()
		);

		var color = schema.getNestedField("variants.color").orElseThrow().field();
		assertThat(color.isStored(), is(true));
		assertThat(schema.hasNestedStoredFields(), is(true));
	}

	@Test
	public void testStoredBelowANestedListRequiresItsOwnFeature() {
		var nested = definition(
			FieldDef.newBuilder()
				.setType(
					FieldTypeDef.newBuilder().setObject(
						ObjectFieldTypeDef.newBuilder()
							.putFields("color", string().setStored(true).build())
							.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
					)
				)
				.setMultiple(true)
		).build();

		assertThat(
			IndexFeatures.requiredBy(nested).toList(),
			hasItem(IndexFeatures.TYPE_OBJECT_STORED_NESTED)
		);

		/*
		 * The same setting folded into the document asks for nothing beyond
		 * the stored feature itself, so the name stays off definitions an
		 * older node reads correctly.
		 */
		var single = definition(
			singleObject(builder -> builder.putFields(
				"color",
				string().setStored(true).build()
			))
		).build();

		assertThat(
			IndexFeatures.requiredBy(single).toList(),
			not(hasItem(IndexFeatures.TYPE_OBJECT_STORED_NESTED))
		);
	}

	/**
	 * A flattened list inside a nested value mixes its values in the value's
	 * document the way one at the root mixes them in the index's, so stored
	 * stays refused below it.
	 */
	@Test
	public void testStoredRefusedBelowAFlattenedListInsideANestedValue() {
		assertRefused(
			definition(
				FieldDef.newBuilder()
					.setMultiple(true)
					.setType(
						FieldTypeDef.newBuilder().setObject(
							ObjectFieldTypeDef.newBuilder()
								.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
								.putFields(
									"tags",
									FieldDef.newBuilder()
										.setMultiple(true)
										.setType(
											FieldTypeDef.newBuilder().setObject(
												ObjectFieldTypeDef.newBuilder()
													.setMode(ObjectFieldTypeDef.Mode.MODE_FLATTENED)
													.putFields(
														"label",
														string().setStored(true).build()
													)
											)
										)
										.build()
								)
						)
					)
			),
			"index:field:object:flattened_stored"
		);
	}

	/**
	 * A nested list below a flattened list keeps documents per value, but
	 * which flattened value each belongs to is what nothing says - the walk
	 * that judges stored treats the flattened list above the same as one
	 * inside.
	 */
	@Test
	public void testStoredRefusedBelowANestedListUnderAFlattenedList() {
		assertRefused(
			definition(
				FieldDef.newBuilder()
					.setMultiple(true)
					.setType(
						FieldTypeDef.newBuilder().setObject(
							ObjectFieldTypeDef.newBuilder()
								.setMode(ObjectFieldTypeDef.Mode.MODE_FLATTENED)
								.putFields(
									"reviews",
									FieldDef.newBuilder()
										.setMultiple(true)
										.setType(
											FieldTypeDef.newBuilder().setObject(
												ObjectFieldTypeDef.newBuilder()
													.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
													.putFields(
														"author",
														string().setStored(true).build()
													)
											)
										)
										.build()
								)
						)
					)
			),
			"index:field:object:flattened_stored"
		);
	}

	@Test
	public void testInnerFieldCanBeLocaleSpecific() {
		var schema = new IndexSchema();
		schema.setDefinition(
			definition(
				singleObject(builder -> builder.putFields(
					"color",
					string()
						.setLocales(FieldDef.LocaleConfig.newBuilder().addLocales("sv"))
						.build()
				))
			).build()
		);

		var color = schema.getField("variants.color").orElseThrow();
		assertThat(color.isLocaleSpecific(), is(true));
	}

	@Test
	public void testLocalesRequireTheirOwnFeatureInsideAnObject() {
		var features = IndexFeatures.requiredBy(
			definition(
				singleObject(builder -> builder.putFields(
					"color",
					string()
						.setLocales(FieldDef.LocaleConfig.newBuilder().addLocales("sv"))
						.build()
				))
			).build()
		);

		assertThat(features.toList(), hasItem(IndexFeatures.TYPE_OBJECT_LOCALES));
		assertThat(features.toList(), hasItem(IndexFeatures.FIELD_LOCALES));

		var without = IndexFeatures.requiredBy(definition(variants()).build());
		assertThat(without.toList(), not(hasItem(IndexFeatures.TYPE_OBJECT_LOCALES)));
	}

	@Test
	public void testInnerFieldOfANestedListCanBeLocaleSpecific() {
		var schema = new IndexSchema();
		schema.setDefinition(
			definition(
				FieldDef.newBuilder()
					.setMultiple(true)
					.setType(
						FieldTypeDef.newBuilder().setObject(
							ObjectFieldTypeDef.newBuilder()
								.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
								.putFields(
									"description",
									string()
										.setLocales(
											FieldDef.LocaleConfig.newBuilder().addLocales("sv")
										)
										.build()
								)
						)
					)
			).build()
		);

		var description = schema.getNestedField("variants.description").orElseThrow();
		assertThat(description.field().isLocaleSpecific(), is(true));
	}

	@Test
	public void testInnerFieldCanBeAnObject() {
		var schema = new IndexSchema();
		schema.setDefinition(
			IndexDef.newBuilder()
				.putFields(
					"product",
					singleObject(builder -> builder.putFields(
						"dims",
						singleObject(inner -> inner.putFields(
							"width",
							string().setFilter(FilterConfig.getDefaultInstance()).build()
						)).build()
					)).build()
				)
				.build()
		);

		var width = schema.getField("product.dims.width");
		assertThat(width.isPresent(), is(true));
		assertThat(width.get().isFiltered(), is(true));

		// A document gives the value at the root of the chain
		assertThat(
			schema.getFlattenedObjectOf("product.dims.width").orElseThrow(),
			is("product")
		);
		assertThat(
			schema.getFlattenedObjectOf("product.dims").orElseThrow(),
			is("product")
		);
	}

	@Test
	public void testNestedListCanSitBelowAnObject() {
		var schema = new IndexSchema();
		schema.setDefinition(
			IndexDef.newBuilder()
				.putFields(
					"product",
					singleObject(builder -> builder.putFields(
						"variants",
						variants().build()
					)).build()
				)
				.build()
		);

		assertThat(schema.hasNestedFields(), is(true));
		assertThat(
			schema.getField("product.variants").orElseThrow().isNestedObject(),
			is(true)
		);

		// The block a field belongs to is the nested list, not the chain root
		var color = schema.getNestedField("product.variants.color");
		assertThat(color.isPresent(), is(true));
		assertThat(color.get().path(), is("product.variants"));
	}

	@Test
	public void testNestedListCanHoldAnObject() {
		var schema = new IndexSchema();
		schema.setDefinition(
			definition(
				FieldDef.newBuilder()
					.setMultiple(true)
					.setType(
						FieldTypeDef.newBuilder().setObject(
							ObjectFieldTypeDef.newBuilder()
								.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
								.putFields(
									"dims",
									singleObject(inner -> inner.putFields(
										"width",
										string()
											.setFilter(FilterConfig.getDefaultInstance())
											.build()
									)).build()
								)
						)
					)
			).build()
		);

		// The object folds into the value's document, so its block is the list
		var width = schema.getNestedField("variants.dims.width");
		assertThat(width.isPresent(), is(true));
		assertThat(width.get().path(), is("variants"));

		var inside = schema.getNestedFields("variants");
		assertThat(inside.collect(Field::getName).toList(), hasItem("variants.dims.width"));
	}

	@Test
	public void testNestedListInsideANestedListIsRefused() {
		assertRefused(
			definition(
				FieldDef.newBuilder()
					.setMultiple(true)
					.setType(
						FieldTypeDef.newBuilder().setObject(
							ObjectFieldTypeDef.newBuilder()
								.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
								.putFields("sizes", variants().build())
						)
					)
			),
			"index:field:object:nested_in_nested"
		);
	}

	@Test
	public void testNestedListBelowANestedListIsRefused() {
		// The rule follows the path through objects in between
		assertRefused(
			definition(
				FieldDef.newBuilder()
					.setMultiple(true)
					.setType(
						FieldTypeDef.newBuilder().setObject(
							ObjectFieldTypeDef.newBuilder()
								.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
								.putFields(
									"dims",
									singleObject(inner -> inner.putFields(
										"sizes",
										variants().build()
									)).build()
								)
						)
					)
			),
			"index:field:object:nested_in_nested"
		);
	}

	@Test
	public void testSortRefusedBelowAFlattenedListThroughAnObject() {
		assertRefused(
			definition(
				FieldDef.newBuilder()
					.setMultiple(true)
					.setType(
						FieldTypeDef.newBuilder().setObject(
							ObjectFieldTypeDef.newBuilder()
								.setMode(ObjectFieldTypeDef.Mode.MODE_FLATTENED)
								.putFields(
									"dims",
									singleObject(inner -> inner.putFields(
										"width",
										string()
											.setSort(SortConfig.getDefaultInstance())
											.build()
									)).build()
								)
						)
					)
			),
			"index:field:object:flattened_sort"
		);
	}

	@Test
	public void testInnerNameCanNotHoldDot() {
		// `b.c` would spell the path of an object `b` holding `c` without declaring it
		assertInnerRefusedNamed(
			"b.c",
			string(),
			"index:field:invalid_name"
		);
	}

	@Test
	public void testInnerObjectRequiresTheFeature() {
		var features = IndexFeatures.requiredBy(
			IndexDef.newBuilder()
				.putFields(
					"product",
					singleObject(builder -> builder.putFields(
						"dims",
						dimensions().build()
					)).build()
				)
				.build()
		);

		assertThat(features.toList(), hasItem(IndexFeatures.TYPE_OBJECT_NESTING));

		var flat = IndexFeatures.requiredBy(definition(variants()).build());
		assertThat(flat.toList(), not(hasItem(IndexFeatures.TYPE_OBJECT_NESTING)));
	}

	@Test
	public void testInnerNameCanHoldWildcard() {
		var schema = new IndexSchema();
		schema.setDefinition(
			definition(
				FieldDef.newBuilder()
					.setMultiple(true)
					.setType(
						FieldTypeDef.newBuilder().setObject(
							ObjectFieldTypeDef.newBuilder()
								.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
								.putFields("sku", string().build())
								.putFields(
									"attr",
									singleObject(attr -> attr.putFields(
										"*",
										string()
											.setFilter(FilterConfig.getDefaultInstance())
											.build()
									)).build()
								)
						)
					)
			).build()
		);

		var color = schema.getNestedField("variants.attr.color");
		assertThat(color.isPresent(), is(true));
		assertThat(color.get().path(), is("variants"));
		assertThat(color.get().field().getName(), is("variants.attr.*"));

		// A pattern never crosses a dot, so it stands for one segment only
		assertThat(schema.getNestedField("variants.attr.a.b").isPresent(), is(false));
	}

	/**
	 * A settled name wins over a pattern, and among patterns the one
	 * {@link IndexSchema} orders first does - the same rule root names resolve
	 * by.
	 */
	@Test
	public void testInnerNameResolvesExactBeforePattern() {
		var schema = new IndexSchema();
		schema.setDefinition(
			definition(
				FieldDef.newBuilder()
					.setMultiple(true)
					.setType(
						FieldTypeDef.newBuilder().setObject(
							ObjectFieldTypeDef.newBuilder()
								.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
								.putFields(
									"attr",
									singleObject(attr -> attr
										.putFields(
											"color",
											string()
												.setFilter(FilterConfig.getDefaultInstance())
												.build()
										)
										.putFields(
											"*",
											string()
												.setFilter(FilterConfig.getDefaultInstance())
												.build()
										)
									).build()
								)
								.putFields(
									"*",
									string().setFilter(FilterConfig.getDefaultInstance()).build()
								)
						)
					)
			).build()
		);

		assertThat(
			schema.getNestedField("variants.attr.color").get().field().getName(),
			is("variants.attr.color")
		);
		assertThat(
			schema.getNestedField("variants.attr.size").get().field().getName(),
			is("variants.attr.*")
		);
		assertThat(
			schema.getNestedField("variants.sku").get().field().getName(),
			is("variants.*")
		);
	}

	@Test
	public void testWildcardInsideObjectRequiresTheFeature() {
		var features = IndexFeatures.requiredBy(
			definition(
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder().setObject(
							ObjectFieldTypeDef.newBuilder().putFields("*", string().build())
						)
					)
			).build()
		);

		assertThat(features.toList(), hasItem(IndexFeatures.TYPE_OBJECT_WILDCARD));
	}

	@Test
	public void testWildcardObjectNameRequiresTheFeature() {
		var features = IndexFeatures.requiredBy(
			IndexDef.newBuilder()
				.putFields("*", variants().build())
				.build()
		);

		assertThat(features.toList(), hasItem(IndexFeatures.TYPE_OBJECT_WILDCARD));
	}

	@Test
	public void testObjectWithoutWildcardsDoesNotNeedTheFeature() {
		var features = IndexFeatures.requiredBy(definition(variants()).build());

		assertThat(features.toList(), not(hasItem(IndexFeatures.TYPE_OBJECT_WILDCARD)));
	}

	@Test
	public void testWildcardInsideObjectCanNotBeTheKey() {
		assertRefused(
			definition(
				FieldDef.newBuilder()
					.setMultiple(true)
					.setType(
						FieldTypeDef.newBuilder().setObject(
							ObjectFieldTypeDef.newBuilder()
								.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
								.putFields("*", string().setRequired(true).build())
								.setKey("*")
						)
					)
			),
			"index:field:object:key_not_valid"
		);
	}

	@Test
	public void testWildcardInsideObjectCanNotBeRequired() {
		assertInnerRefusedNamed(
			"*",
			string().setRequired(true),
			"index:field:invalid_required"
		);
	}

	@Test
	public void testWildcardInsideObjectCanNotBePrimaryKey() {
		assertInnerRefusedNamed(
			"*",
			string().setPrimaryKey(true),
			"index:field:object:inner_usage_not_supported"
		);
	}

	@Test
	public void testRootNameCanNotHoldDot() {
		var builder = definition(variants());
		builder.putFields("variants.color", string().build());

		assertRefused(builder, "index:field:invalid_name");
	}

	@Test
	public void testObjectDefinitionRequiresTheFeature() {
		var features = IndexFeatures.requiredBy(definition(variants()).build());

		assertThat(features.toList(), hasItem(IndexFeatures.TYPE_OBJECT));
		// The fields inside carry their own names besides the object's
		assertThat(features.toList(), hasItem(IndexFeatures.TYPE_STRING));
		assertThat(features.toList(), hasItem(IndexFeatures.FIELD_FILTER));
	}

	@Test
	public void testListOfObjectsNeedsAMode() {
		var withoutMode = FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setObject(
					ObjectFieldTypeDef.newBuilder()
						.putFields("color", string().build())
				)
			)
			.setMultiple(true);

		assertRefused(definition(withoutMode), "index:field:object:mode_required");
	}

	@Test
	public void testSingleObjectRefusesAMode() {
		var withMode = FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setObject(
					ObjectFieldTypeDef.newBuilder()
						.putFields("width", string().build())
						.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
				)
			);

		assertRefused(definition(withMode), "index:field:object:mode_without_multiple");
	}

	@Test
	public void testKeyNamesAFieldInsideTheValue() {
		var schema = new IndexSchema();
		schema.setDefinition(definition(keyed()).build());

		assertThat(schema.getField("variants").get().getObjectKey(), is("sku"));
	}

	@Test
	public void testFieldWithoutAKeyHasNone() {
		var schema = new IndexSchema();
		schema.setDefinition(definition(variants()).build());

		assertThat(schema.getField("variants").get().getObjectKey(), is(nullValue()));
	}

	@Test
	public void testSingleObjectRefusesAKey() {
		var single = FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setObject(
					ObjectFieldTypeDef.newBuilder()
						.putFields("sku", string().setRequired(true).build())
						.setKey("sku")
				)
			);

		assertRefused(definition(single), "index:field:object:key_without_multiple");
	}

	@Test
	public void testKeyNamingNoFieldOfTheObjectIsRefused() {
		assertRefused(
			definition(keyed(builder -> builder.setKey("nothing"))),
			"index:field:object:key_not_found"
		);
	}

	/**
	 * A value arriving without a key would have no name to go by, so the key
	 * field carries the requirement rather than every write checking for it.
	 */
	@Test
	public void testKeyNamingAFieldThatIsNotRequiredIsRefused() {
		assertRefused(
			definition(keyed(builder -> builder.putFields(
				"sku",
				string().setRequired(false).build()
			))),
			"index:field:object:key_not_valid"
		);
	}

	@Test
	public void testKeyNamingAMultipleFieldIsRefused() {
		assertRefused(
			definition(keyed(builder -> builder.putFields(
				"sku",
				string().setRequired(true).setMultiple(true).build()
			))),
			"index:field:object:key_not_valid"
		);
	}

	/**
	 * A key is compared as the text it was written as, and a double does not
	 * read back as one - {@code 1} comes back {@code 1.0} and answers to
	 * neither spelling.
	 */
	@Test
	public void testKeyNamingATypeThatDoesNotReadBackAsTextIsRefused() {
		assertRefused(
			definition(keyed(builder -> builder.putFields(
				"sku",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder()
							.setDouble(DoubleFieldTypeDef.getDefaultInstance())
					)
					.setRequired(true)
					.build()
			))),
			"index:field:object:key_not_valid"
		);
	}

	@Test
	public void testKeyMayNameAnIntegerField() {
		var schema = new IndexSchema();
		schema.setDefinition(
			definition(keyed(builder -> builder.putFields(
				"sku",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder()
							.setInt64(Int64FieldTypeDef.getDefaultInstance())
					)
					.setRequired(true)
					.build()
			))).build()
		);

		assertThat(schema.getField("variants").get().getObjectKey(), is("sku"));
	}

	@Test
	public void testFlattenedListRefusesInnerSort() {
		var flattened = FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setObject(
					ObjectFieldTypeDef.newBuilder()
						.putFields(
							"price",
							string().setSort(SortConfig.getDefaultInstance()).build()
						)
						.setMode(ObjectFieldTypeDef.Mode.MODE_FLATTENED)
				)
			)
			.setMultiple(true);

		assertRefused(definition(flattened), "index:field:object:flattened_sort");
	}

	@Test
	public void testSingleObjectFlattensIntoRootFields() {
		var schema = new IndexSchema();
		schema.setDefinition(
			IndexDef.newBuilder()
				.putFields("dimensions", dimensions().build())
				.build()
		);

		assertThat(schema.hasNestedFields(), is(false));
		assertThat(schema.getNestedField("dimensions.width").isPresent(), is(false));

		var width = schema.getField("dimensions.width");
		assertThat(width.isPresent(), is(true));
		assertThat(width.get().isFiltered(), is(true));

		assertThat(
			schema.getFlattenedObjectOf("dimensions.width").orElseThrow(),
			is("dimensions")
		);
		assertThat(schema.getFlattenedObjectOf("dimensions").isPresent(), is(false));
	}

	@Test
	public void testSingleObjectCanSortInside() {
		var schema = new IndexSchema();
		schema.setDefinition(
			definition(
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder().setObject(
							ObjectFieldTypeDef.newBuilder()
								.putFields(
									"price",
									string().setSort(SortConfig.getDefaultInstance()).build()
								)
						)
					)
			).build()
		);

		assertThat(schema.getField("variants.price").orElseThrow().isSorted(), is(true));
	}

	@Test
	public void testFlattenedListOfObjectsFlattensIntoRootFields() {
		var schema = new IndexSchema();
		schema.setDefinition(
			definition(
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder().setObject(
							ObjectFieldTypeDef.newBuilder()
								.putFields(
									"color",
									string().setFilter(FilterConfig.getDefaultInstance()).build()
								)
								.setMode(ObjectFieldTypeDef.Mode.MODE_FLATTENED)
						)
					)
					.setMultiple(true)
			).build()
		);

		assertThat(schema.hasNestedFields(), is(false));
		assertThat(schema.getField("variants.color").isPresent(), is(true));
		assertThat(
			schema.getFlattenedObjectOf("variants.color").orElseThrow(),
			is("variants")
		);
	}

	@Test
	public void testFlattenedObjectRequiresItsOwnFeature() {
		var features = IndexFeatures.requiredBy(
			IndexDef.newBuilder()
				.putFields("dimensions", dimensions().build())
				.build()
		);

		assertThat(features.toList(), hasItem(IndexFeatures.TYPE_OBJECT_FLATTENED));
		assertThat(features.toList(), not(hasItem(IndexFeatures.TYPE_OBJECT)));
		assertThat(features.toList(), not(hasItem(IndexFeatures.TYPE_OBJECT_USAGES)));
	}

	private static void assertInnerRefused(FieldDef.Builder inner, String code) {
		assertInnerRefusedNamed("color", inner, code);
	}

	private static void assertInnerRefusedNamed(
		String name,
		FieldDef.Builder inner,
		String code
	) {
		assertRefused(
			definition(
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder().setObject(
							ObjectFieldTypeDef.newBuilder().putFields(name, inner.build())
						)
					)
			),
			code
		);
	}

	private static void assertRefused(IndexDef.Builder builder, String code) {
		var schema = new IndexSchema();

		var e = assertThrows(
			ValidationException.class,
			() -> schema.setDefinition(builder.build())
		);

		assertThat(
			e.getErrors().collect(error -> error.getCode()).toList(),
			hasItem(code)
		);
	}

	private static IndexDef.Builder definition(FieldDef.Builder variants) {
		return IndexDef.newBuilder()
			.putFields("variants", variants.build());
	}

	private static FieldDef.Builder variants() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setObject(
					ObjectFieldTypeDef.newBuilder()
						.putFields(
							"color",
							string().setFilter(FilterConfig.getDefaultInstance()).build()
						)
						.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
				)
			)
			.setMultiple(true);
	}

	/**
	 * A list of objects naming {@code sku} as what tells its values apart.
	 */
	private static FieldDef.Builder keyed() {
		return keyed(builder -> builder);
	}

	/**
	 * The same, with the object part changed - to break the key, or to give
	 * the key field another type.
	 */
	private static FieldDef.Builder keyed(
		UnaryOperator<ObjectFieldTypeDef.Builder> change
	) {
		var object = ObjectFieldTypeDef.newBuilder()
			.putFields("sku", string().setRequired(true).build())
			.putFields("color", string().setFilter(FilterConfig.getDefaultInstance()).build())
			.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
			.setKey("sku");

		return FieldDef.newBuilder()
			.setType(FieldTypeDef.newBuilder().setObject(change.apply(object)))
			.setMultiple(true);
	}

	/**
	 * A single object holding whatever fields the change puts in it.
	 */
	private static FieldDef.Builder singleObject(
		UnaryOperator<ObjectFieldTypeDef.Builder> fields
	) {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder()
					.setObject(fields.apply(ObjectFieldTypeDef.newBuilder()))
			);
	}

	private static FieldDef.Builder dimensions() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setObject(
					ObjectFieldTypeDef.newBuilder()
						.putFields(
							"width",
							string().setFilter(FilterConfig.getDefaultInstance()).build()
						)
				)
			);
	}

	private static FieldDef.Builder string() {
		return FieldDef.newBuilder()
			.setType(FieldTypeDef.newBuilder().setString(StringFieldTypeDef.getDefaultInstance()));
	}

	private static FieldDef.Builder highlightable() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setString(
					StringFieldTypeDef.newBuilder().setMatching(
						StringFieldTypeDef.TextUsageConfig.newBuilder()
							.setHighlight(
								StringFieldTypeDef.TextUsageConfig.HighlightConfig
									.getDefaultInstance()
							)
					)
				)
			);
	}

	private static FieldDef.Builder matching() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setString(
					StringFieldTypeDef.newBuilder().setMatching(
						StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
					)
				)
			);
	}
}
