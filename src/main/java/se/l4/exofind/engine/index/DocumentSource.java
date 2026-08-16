package se.l4.exofind.engine.index;

import org.apache.lucene.util.BytesRef;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.index.source.FloatVector;
import se.l4.exofind.engine.index.source.GeoPointValue;
import se.l4.exofind.engine.index.source.SourceDocument;
import se.l4.exofind.engine.index.source.SourceField;

/**
 * Turns a document into the bytes kept alongside the index, and back again.
 *
 * A value on its way into the index is spread over several Lucene fields, one
 * per way it can be used, and every one of them holds the value shaped for what
 * it is for rather than as it arrived. The source is the copy that is not
 * shaped for anything: it is what came in, so reading it back needs no
 * definition and gives the same values, of the same types, in the same order.
 *
 * That is also what lets a document be indexed again from the index itself
 * after its definition changes, when the values are no longer available from
 * wherever they first came from.
 *
 * Whether an index keeps one is {@link IndexSchema#isSourceStored()}.
 */
public final class DocumentSource {
	private static final ErrorType UNREADABLE =
		ErrorType.withCode("index:source:unreadable")
			.withMessage("The stored copy of the document could not be read");

	private static final ErrorType UNWRITABLE =
		ErrorType.withCode("index:source:unwritable")
			.withArguments("name", "type")
			.withMessage(
				"Field `{{name}}` holds a `{{type}}`, which can not be kept as it was given"
			);

	private DocumentSource() {
	}

	/**
	 * Get a document as the bytes it is kept as.
	 *
	 * @param doc
	 *   the document, whose fields are expected to have been checked against
	 *   the definition already
	 * @return
	 */
	public static byte[] encode(Document doc) {
		return encodeDocument(doc).build().toByteArray();
	}

	private static SourceDocument.Builder encodeDocument(Document doc) {
		var builder = SourceDocument.newBuilder();

		for(var value : doc.fields()) {
			builder.addFields(encode(value));
		}

		return builder;
	}

	private static SourceField encode(Document.Value value) {
		var builder = SourceField.newBuilder()
			.setName(value.name());

		if(value.locale() != null) {
			builder.setLocale(value.locale());
		}

		/*
		 * Every type the engine can index has a case here. A value of any other
		 * type does not get this far, as the definition naming that type is
		 * refused when the index is opened.
		 */
		switch(value.value()) {
			case String v -> builder.setString(v);
			case Boolean v -> builder.setBoolean(v);
			case Integer v -> builder.setInt32(v);
			case Long v -> builder.setInt64(v);
			case Float v -> builder.setFloat(v);
			case Double v -> builder.setDouble(v);
			case GeoPoint v -> builder.setGeoPoint(
				GeoPointValue.newBuilder()
					.setLatitude(v.latitude())
					.setLongitude(v.longitude())
			);
			case float[] v -> {
				var vector = FloatVector.newBuilder();
				for(var component : v) {
					vector.addValues(component);
				}
				builder.setVector(vector);
			}
			case Document v -> builder.setDocument(encodeDocument(v));
			default -> throw new IndexException(
				UNWRITABLE,
				"name", value.name(),
				"type", value.value() == null
					? "null"
					: value.value().getClass().getSimpleName()
			);
		}

		return builder.build();
	}

	/**
	 * Get the document the given bytes were written from.
	 *
	 * @param bytes
	 * @return
	 */
	public static Document decode(BytesRef bytes) {
		SourceDocument source;
		try {
			source = SourceDocument.parseFrom(
				ByteString.copyFrom(bytes.bytes, bytes.offset, bytes.length)
			);
		} catch(InvalidProtocolBufferException e) {
			throw new IndexException(UNREADABLE, Maps.immutable.<String, Object>empty(), e);
		}

		return decodeDocument(source);
	}

	private static Document decodeDocument(SourceDocument source) {
		var values = Lists.mutable.<Document.Value>empty();
		for(var field : source.getFieldsList()) {
			var value = decode(field);
			if(value == null) {
				/*
				 * The value was written by a version that has a type this one
				 * does not. Nothing here can say what it was, so it is left out
				 * rather than guessed at.
				 */
				continue;
			}

			values.add(
				new Document.Value(
					field.getName(),
					value,
					field.hasLocale() ? field.getLocale() : null
				)
			);
		}

		return new Document(values.toArray(new Document.Value[0]));
	}

	private static Object decode(SourceField field) {
		return switch(field.getValueCase()) {
			case STRING -> field.getString();
			case BOOLEAN -> field.getBoolean();
			case INT32 -> field.getInt32();
			case INT64 -> field.getInt64();
			case FLOAT -> field.getFloat();
			case DOUBLE -> field.getDouble();
			case GEO_POINT -> new GeoPoint(
				field.getGeoPoint().getLatitude(),
				field.getGeoPoint().getLongitude()
			);
			case VECTOR -> {
				var values = field.getVector();
				var vector = new float[values.getValuesCount()];
				for(var i = 0; i < vector.length; i++) {
					vector[i] = values.getValues(i);
				}
				yield vector;
			}
			case DOCUMENT -> decodeDocument(field.getDocument());
			default -> null;
		};
	}
}
