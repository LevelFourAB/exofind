package se.l4.exofind.engine.index.types;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.errors.ErrorMessage;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.IndexEncounterImpl;
import se.l4.exofind.engine.index.IndexInvalidQueryTypeException;
import se.l4.exofind.engine.index.IndexInvalidQueryValueException;
import se.l4.exofind.engine.index.locales.Locales;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.ResourcesDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.schema.VectorFieldTypeDef;
import se.l4.exofind.engine.query.matchers.Matchers;

/**
 * Tests for which Lucene fields a vector turns into, the rules only this type
 * can judge, and the values it refuses.
 */
public class VectorFieldTypeTest {
	private final VectorFieldType type = new VectorFieldType();

	private IndexEncounterImpl encounter(FieldDef.Builder def) {
		var encounter = new IndexEncounterImpl(ResourcesDef.getDefaultInstance(), false);
		encounter.updateLocale(Locales.getDefault());
		encounter.updateValue("embedding", def.build());
		return encounter;
	}

	private Map<String, IndexableField> index(FieldDef.Builder def, Object value) {
		var fields = new LinkedHashMap<String, IndexableField>();
		for(var field : type.createFields(encounter(def), value)) {
			fields.put(field.name(), field);
		}

		return fields;
	}

	private static FieldDef.Builder vector(VectorFieldTypeDef.Builder def) {
		return FieldDef.newBuilder()
			.setType(FieldTypeDef.newBuilder().setVector(def));
	}

	private static FieldDef.Builder vector(int dimensions) {
		return vector(VectorFieldTypeDef.newBuilder().setDimensions(dimensions));
	}

	private Iterable<ErrorMessage> validate(FieldDef.Builder def) {
		return type.validate(
			ObjectLocation.root().forField("embedding"),
			def.build(),
			ResourcesDef.getDefaultInstance()
		);
	}

	private static Iterable<String> codes(Iterable<ErrorMessage> errors) {
		var codes = new java.util.ArrayList<String>();
		for(var error : errors) {
			codes.add(error.getCode());
		}
		return codes;
	}

	@Test
	public void testVectorFieldIsAlwaysWritten() {
		var fields = index(vector(3), new float[] { 1f, 2f, 3f });

		assertThat(fields, hasKey("embedding:_:vector"));

		var field = (KnnFloatVectorField) fields.get("embedding:_:vector");
		assertThat(field.vectorValue(), is(new float[] { 1f, 2f, 3f }));
	}

	@Test
	public void testSimilarityDefaultsToCosine() {
		var fields = index(vector(3), new float[] { 1f, 2f, 3f });

		var field = fields.get("embedding:_:vector");
		assertThat(
			field.fieldType().vectorSimilarityFunction(),
			is(VectorSimilarityFunction.COSINE)
		);
	}

	@Test
	public void testSimilarityFollowsTheDefinition() {
		assertThat(
			VectorFieldType.similarity(
				VectorFieldTypeDef.newBuilder()
					.setSimilarity(
						VectorFieldTypeDef.SimilarityMetric.SIMILARITY_METRIC_DOT_PRODUCT
					)
					.build()
			),
			is(VectorSimilarityFunction.DOT_PRODUCT)
		);
		assertThat(
			VectorFieldType.similarity(
				VectorFieldTypeDef.newBuilder()
					.setSimilarity(
						VectorFieldTypeDef.SimilarityMetric.SIMILARITY_METRIC_EUCLIDEAN
					)
					.build()
			),
			is(VectorSimilarityFunction.EUCLIDEAN)
		);
		assertThat(
			VectorFieldType.similarity(VectorFieldTypeDef.getDefaultInstance()),
			is(VectorSimilarityFunction.COSINE)
		);
	}

	@Test
	public void testStoredValueRoundTrips() {
		var fields = index(vector(3).setStored(true), new float[] { 1.5f, -2f, 0.25f });

		assertThat(fields, hasKey("embedding:_:stored"));

		var read = type.readStored(
			encounter(vector(3).setStored(true)),
			fields.get("embedding:_:stored")
		);
		assertThat(read, is(new float[] { 1.5f, -2f, 0.25f }));
	}

	@Test
	public void testNothingIsStoredWithoutAskingForIt() {
		var fields = index(vector(3), new float[] { 1f, 2f, 3f });

		assertThat(fields, not(hasKey("embedding:_:stored")));
	}

	@Test
	public void testValidDefinitionPasses() {
		assertThat(
			validate(
				vector(
					VectorFieldTypeDef.newBuilder()
						.setDimensions(1536)
						.setSimilarity(
							VectorFieldTypeDef.SimilarityMetric.SIMILARITY_METRIC_EUCLIDEAN
						)
						.setHnsw(
							VectorFieldTypeDef.HNSWConfig.newBuilder()
								.setM(32)
								.setEfConstruction(200)
						)
						.setQuantization(VectorFieldTypeDef.Quantization.QUANTIZATION_INT8)
				)
			),
			is(emptyIterable())
		);
	}

	@Test
	public void testDimensionsAreRequired() {
		assertThat(
			codes(validate(vector(VectorFieldTypeDef.newBuilder()))),
			contains("index:field:vector:missing_dimensions")
		);
	}

	@Test
	public void testDimensionsHaveToBeInRange() {
		assertThat(
			codes(validate(vector(0))),
			contains("index:field:vector:invalid_dimensions")
		);
		assertThat(
			codes(validate(vector(4097))),
			contains("index:field:vector:invalid_dimensions")
		);
		assertThat(validate(vector(4096)), is(emptyIterable()));
	}

	@Test
	public void testHnswParametersHaveToBeInRange() {
		assertThat(
			codes(
				validate(
					vector(
						VectorFieldTypeDef.newBuilder()
							.setDimensions(3)
							.setHnsw(VectorFieldTypeDef.HNSWConfig.newBuilder().setM(0))
					)
				)
			),
			contains("index:field:vector:invalid_hnsw_m")
		);
		assertThat(
			codes(
				validate(
					vector(
						VectorFieldTypeDef.newBuilder()
							.setDimensions(3)
							.setHnsw(VectorFieldTypeDef.HNSWConfig.newBuilder().setM(513))
					)
				)
			),
			contains("index:field:vector:invalid_hnsw_m")
		);
		assertThat(
			codes(
				validate(
					vector(
						VectorFieldTypeDef.newBuilder()
							.setDimensions(3)
							.setHnsw(
								VectorFieldTypeDef.HNSWConfig.newBuilder()
									.setEfConstruction(3201)
							)
					)
				)
			),
			contains("index:field:vector:invalid_hnsw_ef_construction")
		);
	}

	@Test
	public void testFilterIsRefused() {
		assertThat(
			codes(validate(vector(3).setFilter(FilterConfig.getDefaultInstance()))),
			contains("index:field:vector:filter_not_supported")
		);
	}

	@Test
	public void testMultipleIsRefused() {
		assertThat(
			codes(validate(vector(3).setMultiple(true))),
			contains("index:field:vector:multiple_not_supported")
		);
	}

	@Test
	public void testLocalesAreRefused() {
		assertThat(
			codes(
				validate(
					vector(3).setLocales(
						FieldDef.LocaleConfig.newBuilder().setDefaultLocale("en")
					)
				)
			),
			contains("index:field:vector:locales_not_supported")
		);
	}

	@Test
	public void testValueThatIsNotAVectorIsRefused() {
		var e = assertThrows(
			ValidationException.class,
			() -> index(vector(3), "not a vector")
		);

		assertThat(e.getErrors().get(0).getCode(), is("index:update:vector:invalid_value"));
	}

	@Test
	public void testValueWithWrongDimensionsIsRefused() {
		var e = assertThrows(
			ValidationException.class,
			() -> index(vector(3), new float[] { 1f, 2f })
		);

		assertThat(e.getErrors().get(0).getCode(), is("index:update:vector:wrong_dimensions"));
	}

	@Test
	public void testValueThatIsNotFiniteIsRefused() {
		var e = assertThrows(
			ValidationException.class,
			() -> index(vector(3), new float[] { 1f, Float.NaN, 3f })
		);

		assertThat(e.getErrors().get(0).getCode(), is("index:update:vector:not_finite"));
	}

	@Test
	public void testZeroVectorIsRefusedForCosine() {
		var e = assertThrows(
			ValidationException.class,
			() -> index(vector(3), new float[] { 0f, 0f, 0f })
		);

		assertThat(e.getErrors().get(0).getCode(), is("index:update:vector:zero_vector"));
	}

	@Test
	public void testZeroVectorIsAllowedForEuclidean() {
		var fields = index(
			vector(
				VectorFieldTypeDef.newBuilder()
					.setDimensions(3)
					.setSimilarity(
						VectorFieldTypeDef.SimilarityMetric.SIMILARITY_METRIC_EUCLIDEAN
					)
			),
			new float[] { 0f, 0f, 0f }
		);

		assertThat(fields, hasKey("embedding:_:vector"));
	}

	/**
	 * A vector has no terms, so every matcher is refused - searching one is the
	 * knn clause.
	 */
	@Test
	public void testMatchersAreRefused() {
		assertThrows(
			IndexInvalidQueryTypeException.class,
			() -> type.createQuery(encounter(vector(3)), Matchers.equalTo("x"))
		);
	}

	@Test
	public void testKnnQueryIsBuilt() {
		var query = type.createKnnQuery(encounter(vector(3)), new float[] { 1f, 2f, 3f }, 5, null);

		assertThat(query, is(notNullValue()));
	}

	@Test
	public void testKnnQueryRefusesWrongDimensions() {
		assertThrows(
			IndexInvalidQueryValueException.class,
			() -> type.createKnnQuery(encounter(vector(3)), new float[] { 1f, 2f }, 5, null)
		);
	}

	@Test
	public void testKnnQueryRefusesNonPositiveK() {
		assertThrows(
			IndexInvalidQueryValueException.class,
			() -> type.createKnnQuery(encounter(vector(3)), new float[] { 1f, 2f, 3f }, 0, null)
		);
	}

	/**
	 * Every other type refuses knn the same way a matcher that means nothing
	 * for it is refused.
	 */
	@Test
	public void testOtherTypesRefuseKnn() {
		var encounter = new IndexEncounterImpl(ResourcesDef.getDefaultInstance(), false);
		encounter.updateLocale(Locales.getDefault());
		encounter.updateValue(
			"title",
			FieldDef.newBuilder()
				.setType(
					FieldTypeDef.newBuilder()
						.setString(StringFieldTypeDef.getDefaultInstance())
				)
				.build()
		);

		assertThrows(
			IndexInvalidQueryTypeException.class,
			() -> new StringFieldType().createKnnQuery(encounter, new float[] { 1f }, 5, null)
		);
	}
}
