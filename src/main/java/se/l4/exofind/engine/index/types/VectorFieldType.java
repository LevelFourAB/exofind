package se.l4.exofind.engine.index.types;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ListIterable;

import se.l4.exofind.engine.errors.ErrorMessage;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.FieldNames;
import se.l4.exofind.engine.index.IndexEncounter;
import se.l4.exofind.engine.index.IndexInvalidQueryTypeException;
import se.l4.exofind.engine.index.IndexInvalidQueryValueException;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.ResourcesDef;
import se.l4.exofind.engine.index.schema.VectorFieldTypeDef;
import se.l4.exofind.engine.query.matchers.Matcher;

/**
 * A field holding a vector of floats, searched by similarity rather than by
 * value.
 *
 * The vectors arrive with the documents - the engine never produces them - and
 * every value has to have exactly the number of dimensions the definition
 * declares, because Lucene fixes the dimensions of a vector field at the first
 * document. Searching one is the {@code knn} clause, which is why every
 * {@link Matcher} is refused here: nearest-neighbour search needs a compiled
 * pre-filter, which a matcher has no channel for.
 */
public class VectorFieldType implements FieldType {
	/**
	 * The most dimensions a vector field may declare. Wider than what Lucene
	 * allows by default, which {@link se.l4.exofind.engine.index.IndexCodec}
	 * raises to match this.
	 */
	public static final int MAX_DIMENSIONS = 4096;

	/**
	 * The largest number of HNSW neighbours per node Lucene accepts.
	 */
	private static final int MAX_M = 512;

	/**
	 * The largest HNSW beam width Lucene accepts.
	 */
	private static final int MAX_EF_CONSTRUCTION = 3200;

	private static final ErrorType MISSING_DIMENSIONS = ErrorType
		.withCode("index:field:vector:missing_dimensions")
		.withMessage("A vector field has to declare its dimensions");

	private static final ErrorType INVALID_DIMENSIONS = ErrorType
		.withCode("index:field:vector:invalid_dimensions")
		.withArguments("max")
		.withMessage("The dimensions of a vector field have to be between 1 and {{max}}");

	private static final ErrorType INVALID_M = ErrorType
		.withCode("index:field:vector:invalid_hnsw_m")
		.withArguments("max")
		.withMessage("The HNSW neighbour count `m` has to be between 1 and {{max}}");

	private static final ErrorType INVALID_EF_CONSTRUCTION = ErrorType
		.withCode("index:field:vector:invalid_hnsw_ef_construction")
		.withArguments("max")
		.withMessage("The HNSW `ef_construction` has to be between 1 and {{max}}");

	private static final ErrorType FILTER_NOT_SUPPORTED = ErrorType
		.withCode("index:field:vector:filter_not_supported")
		.withMessage("A vector is searched by similarity, not filtered on as a value");

	private static final ErrorType MULTIPLE_NOT_SUPPORTED = ErrorType
		.withCode("index:field:vector:multiple_not_supported")
		.withMessage("A vector field holds one vector per document");

	private static final ErrorType LOCALES_NOT_SUPPORTED = ErrorType
		.withCode("index:field:vector:locales_not_supported")
		.withMessage("A vector field can not be locale specific");

	private static final ErrorType INVALID_VALUE = ErrorType
		.withCode("index:update:vector:invalid_value")
		.withArguments("name")
		.withMessage("Field `{{name}}` holds a vector, which has to be given as an array of floats");

	private static final ErrorType WRONG_DIMENSIONS = ErrorType
		.withCode("index:update:vector:wrong_dimensions")
		.withArguments("name", "expected", "actual")
		.withMessage(
			"Field `{{name}}` is defined with {{expected}} dimensions, but the value has {{actual}}"
		);

	private static final ErrorType NOT_FINITE = ErrorType
		.withCode("index:update:vector:not_finite")
		.withArguments("name")
		.withMessage("Field `{{name}}` was given a vector with a value that is not a finite number");

	private static final ErrorType ZERO_VECTOR = ErrorType
		.withCode("index:update:vector:zero_vector")
		.withArguments("name")
		.withMessage(
			"Field `{{name}}` compares vectors by cosine, which is undefined for a vector of only zeros"
		);

	@Override
	public boolean isSortingSupported() {
		return false;
	}

	@Override
	public boolean isDocValuesSupported() {
		return false;
	}

	@Override
	public ListIterable<ErrorMessage> validate(
		ObjectLocation location,
		FieldDef def,
		ResourcesDef resources
	) {
		var errors = Lists.mutable.<ErrorMessage>empty();
		var vectorType = def.getType().getVector();

		if(!vectorType.hasDimensions()) {
			errors.add(MISSING_DIMENSIONS.toMessage(location));
		} else if(vectorType.getDimensions() < 1 || vectorType.getDimensions() > MAX_DIMENSIONS) {
			errors.add(INVALID_DIMENSIONS.toMessage(location, "max", MAX_DIMENSIONS));
		}

		if(vectorType.hasHnsw()) {
			var hnsw = vectorType.getHnsw();
			if(hnsw.hasM() && (hnsw.getM() < 1 || hnsw.getM() > MAX_M)) {
				errors.add(INVALID_M.toMessage(location, "max", MAX_M));
			}
			if(hnsw.hasEfConstruction()
				&& (hnsw.getEfConstruction() < 1
					|| hnsw.getEfConstruction() > MAX_EF_CONSTRUCTION)) {
				errors.add(INVALID_EF_CONSTRUCTION.toMessage(location, "max", MAX_EF_CONSTRUCTION));
			}
		}

		if(def.hasFilter()) {
			errors.add(FILTER_NOT_SUPPORTED.toMessage(location));
		}

		if(def.getMultiple()) {
			errors.add(MULTIPLE_NOT_SUPPORTED.toMessage(location));
		}

		if(def.hasLocales()) {
			errors.add(LOCALES_NOT_SUPPORTED.toMessage(location));
		}

		return errors;
	}

	@Override
	public Iterable<? extends IndexableField> createFields(
		IndexEncounter encounter,
		Object value0
	) {
		var location = ObjectLocation.root().forField(encounter.getFieldName());

		if(!(value0 instanceof float[] value)) {
			throw new ValidationException(
				INVALID_VALUE.toMessage(location, "name", encounter.getFieldName())
			);
		}

		var vectorType = encounter.getFieldType().getVector();
		var dimensions = vectorType.getDimensions();
		if(value.length != dimensions) {
			throw new ValidationException(
				WRONG_DIMENSIONS.toMessage(
					location,
					"name", encounter.getFieldName(),
					"expected", dimensions,
					"actual", value.length
				)
			);
		}

		var zero = true;
		for(var component : value) {
			if(!Float.isFinite(component)) {
				throw new ValidationException(
					NOT_FINITE.toMessage(location, "name", encounter.getFieldName())
				);
			}
			if(component != 0f) {
				zero = false;
			}
		}

		if(zero && similarity(vectorType) == VectorSimilarityFunction.COSINE) {
			throw new ValidationException(
				ZERO_VECTOR.toMessage(location, "name", encounter.getFieldName())
			);
		}

		var results = Lists.mutable.<IndexableField>empty();

		results.add(
			new KnnFloatVectorField(
				encounter.name(FieldNames.VECTOR),
				value,
				similarity(vectorType)
			)
		);

		if(encounter.isStored()) {
			results.add(
				new StoredField(encounter.name(FieldNames.STORED), pack(value))
			);
		}

		return results;
	}

	@Override
	public Object readStored(IndexEncounter encounter, IndexableField field) {
		return unpack(field.binaryValue().bytes, field.binaryValue().offset, field.binaryValue().length);
	}

	@Override
	public Query createQuery(IndexEncounter encounter, Matcher matcher) {
		throw new IndexInvalidQueryTypeException("vector", matcher.id());
	}

	@Override
	public Query createKnnQuery(
		IndexEncounter encounter,
		float[] vector,
		int k,
		Query filter
	) {
		if(k < 1) {
			throw new IndexInvalidQueryValueException(encounter.getFieldName(), "positive k");
		}

		var vectorType = encounter.getFieldType().getVector();
		if(vector == null || vector.length != vectorType.getDimensions()) {
			throw new IndexInvalidQueryValueException(
				encounter.getFieldName(),
				"vector of " + vectorType.getDimensions() + " dimensions"
			);
		}

		for(var component : vector) {
			if(!Float.isFinite(component)) {
				throw new IndexInvalidQueryValueException(
					encounter.getFieldName(),
					"vector of finite numbers"
				);
			}
		}

		var name = encounter.name(FieldNames.VECTOR);
		return filter == null
			? new KnnFloatVectorQuery(name, vector, k)
			: new KnnFloatVectorQuery(name, vector, k, filter);
	}

	/**
	 * Get the similarity function the definition asks for, which defaults to
	 * cosine - the metric the common embedding models are trained for.
	 *
	 * @param def
	 * @return
	 */
	public static VectorSimilarityFunction similarity(VectorFieldTypeDef def) {
		if(!def.hasSimilarity()) {
			return VectorSimilarityFunction.COSINE;
		}

		return switch(def.getSimilarity()) {
			case SIMILARITY_METRIC_DOT_PRODUCT -> VectorSimilarityFunction.DOT_PRODUCT;
			case SIMILARITY_METRIC_EUCLIDEAN -> VectorSimilarityFunction.EUCLIDEAN;
			default -> VectorSimilarityFunction.COSINE;
		};
	}

	/**
	 * Get a vector as the bytes it is stored as, four little-endian bytes per
	 * component. Stored explicitly so that a field can be retrieved on its own
	 * even when the index keeps no source.
	 *
	 * @param value
	 * @return
	 */
	private static byte[] pack(float[] value) {
		var buffer = ByteBuffer.allocate(value.length * Float.BYTES)
			.order(ByteOrder.LITTLE_ENDIAN);
		for(var component : value) {
			buffer.putFloat(component);
		}
		return buffer.array();
	}

	private static float[] unpack(byte[] bytes, int offset, int length) {
		var buffer = ByteBuffer.wrap(bytes, offset, length)
			.order(ByteOrder.LITTLE_ENDIAN);
		var value = new float[length / Float.BYTES];
		for(var i = 0; i < value.length; i++) {
			value[i] = buffer.getFloat();
		}
		return value;
	}
}
