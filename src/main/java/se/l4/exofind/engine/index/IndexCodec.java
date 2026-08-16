package se.l4.exofind.engine.index;

import java.io.IOException;

import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.codecs.lucene104.Lucene104Codec;
import org.apache.lucene.codecs.lucene104.Lucene104HnswScalarQuantizedVectorsFormat;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.util.quantization.QuantizedByteVectorValues.ScalarEncoding;

import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexSchema;
import se.l4.exofind.engine.index.schema.VectorFieldTypeDef;
import se.l4.exofind.engine.index.types.VectorFieldType;

/**
 * The codec an index writes with, which is the stock one except for how vector
 * fields are written.
 *
 * A vector field carries its HNSW parameters and quantization in the
 * definition, and this is where they reach Lucene - the format of a field is
 * chosen per field when a segment is flushed. The codec keeps the stock name,
 * and the formats it hands out are the SPI-registered ones of lucene-core, so a
 * segment written through here is read back without this class; only writing
 * needs it.
 *
 * The schema is the live one of the index, mutated in place when the
 * definition changes, so changed parameters apply to segments flushed from
 * then on - the same way an analysis change behaves.
 */
public class IndexCodec extends Lucene104Codec {
	/**
	 * The HNSW neighbour count used when the definition does not give one,
	 * which is Lucene's own default.
	 */
	private static final int DEFAULT_M = 16;

	/**
	 * The HNSW beam width used when the definition does not give one, which is
	 * Lucene's own default.
	 */
	private static final int DEFAULT_EF_CONSTRUCTION = 100;

	private final IndexSchema schema;

	public IndexCodec(IndexSchema schema) {
		this.schema = schema;
	}

	@Override
	public KnnVectorsFormat getKnnVectorsFormatForField(String field) {
		var parsed = FieldNames.parse(field);
		if(parsed == null || !FieldNames.VECTOR.equals(parsed.suffix())) {
			return super.getKnnVectorsFormatForField(field);
		}

		var schemaField = schema.getField(parsed.field());
		if(schemaField.isEmpty()
			|| schemaField.get().getDef().getType().getTypeCase() != FieldTypeDef.TypeCase.VECTOR) {
			return super.getKnnVectorsFormatForField(field);
		}

		var def = schemaField.get().getDef().getType().getVector();
		return new WideDimensionsFormat(format(def));
	}

	private static KnnVectorsFormat format(VectorFieldTypeDef def) {
		var m = def.getHnsw().hasM() ? def.getHnsw().getM() : DEFAULT_M;
		var efConstruction = def.getHnsw().hasEfConstruction()
			? def.getHnsw().getEfConstruction()
			: DEFAULT_EF_CONSTRUCTION;

		return switch(def.getQuantization()) {
			case QUANTIZATION_INT8 -> new Lucene104HnswScalarQuantizedVectorsFormat(
				ScalarEncoding.SEVEN_BIT, m, efConstruction
			);
			case QUANTIZATION_INT4 -> new Lucene104HnswScalarQuantizedVectorsFormat(
				ScalarEncoding.PACKED_NIBBLE, m, efConstruction
			);
			default -> new Lucene99HnswVectorsFormat(m, efConstruction);
		};
	}

	/**
	 * A format that widens how many dimensions a field may have to what
	 * validation allows, which is more than Lucene's stock ceiling. Delegation
	 * because the stock formats are final; the delegate's name is kept, as the
	 * name is what segment metadata records and looks the format up by when
	 * the segment is read.
	 */
	private static final class WideDimensionsFormat extends KnnVectorsFormat {
		private final KnnVectorsFormat delegate;

		private WideDimensionsFormat(KnnVectorsFormat delegate) {
			super(delegate.getName());
			this.delegate = delegate;
		}

		@Override
		public KnnVectorsWriter fieldsWriter(SegmentWriteState state) throws IOException {
			return delegate.fieldsWriter(state);
		}

		@Override
		public KnnVectorsReader fieldsReader(SegmentReadState state) throws IOException {
			return delegate.fieldsReader(state);
		}

		@Override
		public int getMaxDimensions(String fieldName) {
			return VectorFieldType.MAX_DIMENSIONS;
		}
	}
}
