package se.l4.exofind.engine.benchmark.corpus;

import se.l4.exofind.engine.index.schema.BooleanFieldTypeDef;
import se.l4.exofind.engine.index.schema.DoubleFieldTypeDef;
import se.l4.exofind.engine.index.schema.FacetConfig;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.FloatFieldTypeDef;
import se.l4.exofind.engine.index.schema.GeoPointFieldTypeDef;
import se.l4.exofind.engine.index.schema.Int32FieldTypeDef;
import se.l4.exofind.engine.index.schema.ObjectFieldTypeDef;
import se.l4.exofind.engine.index.schema.SortConfig;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.schema.TimestampFieldTypeDef;

/**
 * Shorthands for the field definitions the corpora are built out of.
 *
 * <p>Every method returns a fresh builder, so the result may be added to
 * without disturbing another field built from the same shorthand.
 */
final class Fields {
	private Fields() {
	}

	static FieldDef.Builder string() {
		return string(StringFieldTypeDef.newBuilder());
	}

	static FieldDef.Builder string(StringFieldTypeDef.Builder type) {
		return FieldDef.newBuilder().setType(FieldTypeDef.newBuilder().setString(type));
	}

	static FieldDef.Builder int32() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setInt32(Int32FieldTypeDef.getDefaultInstance())
			);
	}

	static FieldDef.Builder float32() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setFloat(FloatFieldTypeDef.getDefaultInstance())
			);
	}

	static FieldDef.Builder float64() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setDouble(DoubleFieldTypeDef.getDefaultInstance())
			);
	}

	static FieldDef.Builder bool() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setBoolean(BooleanFieldTypeDef.getDefaultInstance())
			);
	}

	static FieldDef.Builder timestamp() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder()
					.setTimestamp(TimestampFieldTypeDef.getDefaultInstance())
			);
	}

	static FieldDef.Builder geoPoint() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder()
					.setGeoPoint(GeoPointFieldTypeDef.getDefaultInstance())
			);
	}

	static FieldDef.Builder object(ObjectFieldTypeDef.Builder type) {
		return FieldDef.newBuilder().setType(FieldTypeDef.newBuilder().setObject(type));
	}

	/**
	 * Text as it is searched in a search box - matched, highlighted and
	 * forgiving of typing mistakes.
	 */
	static StringFieldTypeDef.Builder matched(float weight) {
		return StringFieldTypeDef.newBuilder()
			.setMatching(
				StringFieldTypeDef.TextUsageConfig.newBuilder()
					.setWeight(weight)
					.setHighlight(
						StringFieldTypeDef.TextUsageConfig.HighlightConfig
							.getDefaultInstance()
					)
					.setTypoTolerance(
						StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig
							.getDefaultInstance()
					)
			);
	}

	/**
	 * Text as it is completed while it is being typed.
	 */
	static StringFieldTypeDef.Builder completed(float weight) {
		return StringFieldTypeDef.newBuilder()
			.setAutocomplete(
				StringFieldTypeDef.TextUsageConfig.newBuilder().setWeight(weight)
			);
	}

	/**
	 * Values read as paths through a tree, separated by {@code /}.
	 */
	static StringFieldTypeDef.Builder hierarchy() {
		return StringFieldTypeDef.newBuilder()
			.setHierarchy(StringFieldTypeDef.HierarchyConfig.getDefaultInstance());
	}

	static FieldDef.Builder filtered(FieldDef.Builder field) {
		return field.setFilter(FilterConfig.getDefaultInstance());
	}

	static FieldDef.Builder sorted(FieldDef.Builder field) {
		return field.setSort(SortConfig.getDefaultInstance());
	}

	static FieldDef.Builder faceted(FieldDef.Builder field) {
		return field.setFacet(FacetConfig.getDefaultInstance());
	}
}
