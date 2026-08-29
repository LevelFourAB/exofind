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
	public void testObjectNameCanNotHoldWildcard() {
		var builder = IndexDef.newBuilder()
			.putFields("variants*", variants().build());

		assertRefused(builder, "index:field:object:wildcard_name");
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
	public void testInnerFieldCanNotBeHighlighted() {
		assertInnerRefused(
			FieldDef.newBuilder()
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
				),
			"index:field:object:inner_usage_not_supported"
		);
	}

	@Test
	public void testInnerFieldCanNotBeStored() {
		assertInnerRefused(
			string().setStored(true),
			"index:field:object:inner_usage_not_supported"
		);
	}

	@Test
	public void testInnerFieldCanNotBeLocaleSpecific() {
		assertInnerRefused(
			string().setLocales(FieldDef.LocaleConfig.getDefaultInstance()),
			"index:field:object:inner_usage_not_supported"
		);
	}

	@Test
	public void testInnerFieldCanNotBeAnObject() {
		assertInnerRefused(
			FieldDef.newBuilder()
				.setType(
					FieldTypeDef.newBuilder().setObject(
						ObjectFieldTypeDef.newBuilder()
							.putFields("deep", string().build())
					)
				),
			"index:field:object:inner_object"
		);
	}

	@Test
	public void testInnerNameCanNotHoldWildcard() {
		assertRefused(
			definition(
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder().setObject(
							ObjectFieldTypeDef.newBuilder()
								.putFields("color*", string().build())
						)
					)
			),
			"index:field:object:inner_wildcard"
		);
	}

	@Test
	public void testRootFieldCanNotTakeAnObjectPath() {
		var builder = definition(variants());
		builder.putFields("variants.color", string().build());

		assertRefused(builder, "index:schema:object_path_taken");
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
		assertRefused(
			definition(
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder().setObject(
							ObjectFieldTypeDef.newBuilder().putFields("color", inner.build())
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
