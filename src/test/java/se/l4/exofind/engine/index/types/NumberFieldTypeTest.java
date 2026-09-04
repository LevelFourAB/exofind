package se.l4.exofind.engine.index.types;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.lucene.index.IndexableField;
import org.apache.lucene.search.SortField;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.errors.ErrorMessage;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.IndexEncounterImpl;
import se.l4.exofind.engine.index.IndexInvalidQueryTypeException;
import se.l4.exofind.engine.index.IndexInvalidQueryValueException;
import se.l4.exofind.engine.index.locales.Locales;
import se.l4.exofind.engine.index.schema.DoubleFieldTypeDef;
import se.l4.exofind.engine.index.schema.FacetConfig;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.FloatFieldTypeDef;
import se.l4.exofind.engine.index.schema.Int32FieldTypeDef;
import se.l4.exofind.engine.index.schema.Int64FieldTypeDef;
import se.l4.exofind.engine.index.schema.ResourcesDef;
import se.l4.exofind.engine.index.schema.SortConfig;
import se.l4.exofind.engine.query.matchers.Matchers;

/**
 * Tests for which Lucene fields a number turns into, the values the types
 * accept and refuse, and the rules only a number type can judge.
 */
public class NumberFieldTypeTest {
	private IndexEncounterImpl encounter(FieldDef.Builder def) {
		var encounter = new IndexEncounterImpl(ResourcesDef.getDefaultInstance(), false);
		encounter.updateLocale(Locales.getDefault());
		encounter.updateValue("price", def.build());
		return encounter;
	}

	private Map<String, IndexableField> index(FieldDef.Builder def, Object value) {
		var type = FieldTypes.forDef(def.getType()).orElseThrow();

		var fields = new LinkedHashMap<String, IndexableField>();
		for(var field : type.createFields(encounter(def), value)) {
			fields.put(field.name(), field);
		}

		return fields;
	}

	private static FieldDef.Builder int32() {
		return FieldDef.newBuilder()
			.setType(FieldTypeDef.newBuilder().setInt32(Int32FieldTypeDef.getDefaultInstance()));
	}

	private static FieldDef.Builder int32(Int32FieldTypeDef.ValidationConfig.Builder validation) {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder()
					.setInt32(Int32FieldTypeDef.newBuilder().setValidation(validation))
			);
	}

	private static FieldDef.Builder int64() {
		return FieldDef.newBuilder()
			.setType(FieldTypeDef.newBuilder().setInt64(Int64FieldTypeDef.getDefaultInstance()));
	}

	private static FieldDef.Builder floatField() {
		return FieldDef.newBuilder()
			.setType(FieldTypeDef.newBuilder().setFloat(FloatFieldTypeDef.getDefaultInstance()));
	}

	private static FieldDef.Builder doubleField() {
		return FieldDef.newBuilder()
			.setType(FieldTypeDef.newBuilder().setDouble(DoubleFieldTypeDef.getDefaultInstance()));
	}

	private Iterable<ErrorMessage> validate(FieldDef.Builder def) {
		var type = FieldTypes.forDef(def.getType()).orElseThrow();
		return type.validate(
			ObjectLocation.root().forField("price"),
			def.build(),
			ResourcesDef.getDefaultInstance()
		);
	}

	private static Iterable<String> codes(Iterable<ErrorMessage> errors) {
		var codes = new ArrayList<String>();
		for(var error : errors) {
			codes.add(error.getCode());
		}
		return codes;
	}

	@Test
	public void testFilteredFieldWritesAPoint() {
		var fields = index(int32().setFilter(FilterConfig.getDefaultInstance()), 10);

		assertThat(fields, hasKey("price:_:filter"));
	}

	@Test
	public void testStoredValueKeepsItsType() {
		var fields = index(int32().setStored(true), 10);

		assertThat(fields.get("price:_:stored").numericValue(), is(10));
	}

	@Test
	public void testSortedAndFacetedFieldsWriteDocValues() {
		var fields = index(
			int32()
				.setSort(SortConfig.getDefaultInstance())
				.setFacet(FacetConfig.getDefaultInstance()),
			10
		);

		assertThat(fields, hasKey("price:_:sort"));
		assertThat(fields, hasKey("price:_:values"));
	}

	@Test
	public void testInt32TakesALongThatFits() {
		var fields = index(int32().setStored(true), 10L);

		assertThat(fields.get("price:_:stored").numericValue(), is(10));
	}

	@Test
	public void testInt32RefusesALongThatDoesNotFit() {
		var e = assertThrows(
			ValidationException.class,
			() -> index(int32().setStored(true), Long.MAX_VALUE)
		);

		assertThat(codes(e.getErrors()), hasItem("index:update:number:invalid_value"));
	}

	@Test
	public void testInt32RefusesAFraction() {
		var e = assertThrows(
			ValidationException.class,
			() -> index(int32().setStored(true), 10.5)
		);

		assertThat(codes(e.getErrors()), hasItem("index:update:number:invalid_value"));
	}

	@Test
	public void testInt64TakesAnInteger() {
		var fields = index(int64().setStored(true), 10);

		assertThat(fields.get("price:_:stored").numericValue(), is(10L));
	}

	@Test
	public void testFloatNarrowsADouble() {
		var fields = index(floatField().setStored(true), 10.5d);

		assertThat(fields.get("price:_:stored").numericValue(), is(10.5f));
	}

	@Test
	public void testFloatRefusesADoubleItCanNotHold() {
		var e = assertThrows(
			ValidationException.class,
			() -> index(floatField().setStored(true), Double.MAX_VALUE)
		);

		assertThat(codes(e.getErrors()), hasItem("index:update:number:invalid_value"));
	}

	@Test
	public void testDoubleRefusesNaN() {
		var e = assertThrows(
			ValidationException.class,
			() -> index(doubleField().setStored(true), Double.NaN)
		);

		assertThat(codes(e.getErrors()), hasItem("index:update:number:invalid_value"));
	}

	@Test
	public void testDoubleTakesAFloat() {
		var fields = index(doubleField().setStored(true), 1.5f);

		assertThat(fields.get("price:_:stored").numericValue(), is(1.5d));
	}

	@Test
	public void testValueBelowMinIsRefused() {
		var e = assertThrows(
			ValidationException.class,
			() -> index(
				int32(Int32FieldTypeDef.ValidationConfig.newBuilder().setMin(0)).setStored(true),
				-1
			)
		);

		assertThat(codes(e.getErrors()), hasItem("index:update:number:out_of_bounds"));
	}

	@Test
	public void testValueAboveMaxIsRefused() {
		var e = assertThrows(
			ValidationException.class,
			() -> index(
				int32(Int32FieldTypeDef.ValidationConfig.newBuilder().setMax(100)).setStored(true),
				101
			)
		);

		assertThat(codes(e.getErrors()), hasItem("index:update:number:out_of_bounds"));
	}

	@Test
	public void testValueOnTheBoundsIsAccepted() {
		var fields = index(
			int32(Int32FieldTypeDef.ValidationConfig.newBuilder().setMin(0).setMax(100))
				.setStored(true),
			100
		);

		assertThat(fields.get("price:_:stored").numericValue(), is(100));
	}

	@Test
	public void testMinAboveMaxIsRefused() {
		var errors = validate(
			int32(Int32FieldTypeDef.ValidationConfig.newBuilder().setMin(10).setMax(5))
		);

		assertThat(codes(errors), contains("index:field:number:invalid_bounds"));
	}

	@Test
	public void testBlankUnitIsRefused() {
		var errors = validate(
			FieldDef.newBuilder()
				.setType(
					FieldTypeDef.newBuilder()
						.setDouble(DoubleFieldTypeDef.newBuilder().setUnit(" "))
				)
		);

		assertThat(codes(errors), contains("index:field:number:invalid_unit"));
	}

	@Test
	public void testUnitIsReadBackTrimmed() {
		var type = FieldTypeDef.newBuilder()
			.setDouble(DoubleFieldTypeDef.newBuilder().setUnit(" SEK "))
			.build();

		var doubleType = (NumberFieldType) FieldTypes.forDef(type).orElseThrow();
		assertThat(doubleType.getUnit(type), is("SEK"));
		assertThat(doubleType.getUnit(doubleField().getType()), is(nullValue()));
	}

	@Test
	public void testNonFiniteBoundIsRefused() {
		var errors = validate(
			FieldDef.newBuilder()
				.setType(
					FieldTypeDef.newBuilder()
						.setDouble(
							DoubleFieldTypeDef.newBuilder()
								.setValidation(
									DoubleFieldTypeDef.ValidationConfig.newBuilder()
										.setMin(Double.NaN)
								)
						)
				)
		);

		assertThat(codes(errors), contains("index:field:number:invalid_bound"));
	}

	@Test
	public void testSortCollationIsRefused() {
		var errors = validate(
			int32().setSort(
				SortConfig.newBuilder().setCollation(SortConfig.Collation.COLLATION_LOCALE)
			)
		);

		assertThat(codes(errors), contains("index:field:sort:collation_not_supported"));
	}

	@Test
	public void testValidDefinitionHasNoErrors() {
		var errors = validate(
			int32(Int32FieldTypeDef.ValidationConfig.newBuilder().setMin(0).setMax(100))
				.setFilter(FilterConfig.getDefaultInstance())
				.setSort(SortConfig.getDefaultInstance())
				.setFacet(FacetConfig.getDefaultInstance())
		);

		assertThat(errors, is(emptyIterable()));
	}

	@Test
	public void testTextMatcherIsRefused() {
		var def = int32().setFilter(FilterConfig.getDefaultInstance());
		var type = FieldTypes.forDef(def.getType()).orElseThrow();

		assertThrows(
			IndexInvalidQueryTypeException.class,
			() -> type.createQuery(encounter(def), Matchers.text("ten"))
		);
	}

	@Test
	public void testQueryWithAStringValueIsRefused() {
		var def = int32().setFilter(FilterConfig.getDefaultInstance());
		var type = FieldTypes.forDef(def.getType()).orElseThrow();

		assertThrows(
			IndexInvalidQueryValueException.class,
			() -> type.createQuery(encounter(def), Matchers.equalTo("ten"))
		);
	}

	@Test
	public void testIntegersCanBePrimaryKeys() {
		assertThat(new Int32FieldType().isPrimaryKeySupported(), is(true));
		assertThat(new Int64FieldType().isPrimaryKeySupported(), is(true));
		assertThat(new FloatFieldType().isPrimaryKeySupported(), is(false));
		assertThat(new DoubleFieldType().isPrimaryKeySupported(), is(false));
	}

	@Test
	public void testSortFieldReadsMissingValuesAsTheEndsOfTheType() {
		var def = int32().setSort(
			SortConfig.newBuilder().setMissing(SortConfig.Missing.MISSING_FIRST)
		);
		var type = FieldTypes.forDef(def.getType()).orElseThrow();

		var field = type.createSortField(encounter(def), true);
		assertThat(field.getType(), is(SortField.Type.INT));
		assertThat(field.getMissingValue(), is(Integer.MIN_VALUE));
		assertThat(field.getReverse(), is(false));

		var last = int32().setSort(SortConfig.getDefaultInstance());
		var lastField = FieldTypes.forDef(last.getType()).orElseThrow()
			.createSortField(encounter(last), true);
		assertThat(lastField.getMissingValue(), is(Integer.MAX_VALUE));
	}

	@Test
	public void testUnfilteredFieldRefusesFiltering() {
		var def = int32().setStored(true);
		var type = FieldTypes.forDef(def.getType()).orElseThrow();

		var fields = index(def, 10);
		assertThat(fields, not(hasKey("price:_:filter")));

		assertThrows(
			se.l4.exofind.engine.index.IndexFieldUsageException.class,
			() -> type.createQuery(encounter(def), Matchers.equalTo(10))
		);
	}
}
