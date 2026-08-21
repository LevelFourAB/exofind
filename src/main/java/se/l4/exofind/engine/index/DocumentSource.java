package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.Predicate;

import org.apache.lucene.util.BytesRef;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.WireFormat;

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
		return decode(bytes, null);
	}

	/**
	 * Get the fields of the document the given bytes were written from that
	 * are wanted, in the order they were given in.
	 *
	 * A field that is not wanted is stepped over rather than read, so this
	 * costs what the fields that were wanted hold rather than what the whole
	 * document does.
	 *
	 * @param bytes
	 * @param wanted
	 *   answers for the name of a field, which is read before the value it
	 *   carries is - {@code null} to want every field
	 * @return
	 */
	public static Document decode(BytesRef bytes, Predicate<String> wanted) {
		try {
			var in = CodedInputStream.newInstance(bytes.bytes, bytes.offset, bytes.length);
			return decodeDocument(in, wanted, 0);
		} catch(IOException e) {
			throw new IndexException(UNREADABLE, Maps.immutable.<String, Object>empty(), e);
		}
	}

	/*
	 * Decoding reads the wire format itself rather than parsing into the
	 * generated messages. A search decodes one source per hit, and the message
	 * tree - a SourceField object, its strings and its lists for every value -
	 * costs several times the document in allocations only to be copied into
	 * Document.Value and dropped. The tags below are spelled from the same
	 * generated constants the encoder writes, so the two sides read and write
	 * one format.
	 *
	 * A tag is the field number shifted past the three bits that carry the
	 * wire type.
	 */

	private static final int FIELDS_TAG =
		SourceDocument.FIELDS_FIELD_NUMBER << 3 | WireFormat.WIRETYPE_LENGTH_DELIMITED;

	private static final int NAME_TAG =
		SourceField.NAME_FIELD_NUMBER << 3 | WireFormat.WIRETYPE_LENGTH_DELIMITED;
	private static final int LOCALE_TAG =
		SourceField.LOCALE_FIELD_NUMBER << 3 | WireFormat.WIRETYPE_LENGTH_DELIMITED;
	private static final int STRING_TAG =
		SourceField.STRING_FIELD_NUMBER << 3 | WireFormat.WIRETYPE_LENGTH_DELIMITED;
	private static final int BOOLEAN_TAG =
		SourceField.BOOLEAN_FIELD_NUMBER << 3 | WireFormat.WIRETYPE_VARINT;
	private static final int VECTOR_TAG =
		SourceField.VECTOR_FIELD_NUMBER << 3 | WireFormat.WIRETYPE_LENGTH_DELIMITED;
	private static final int INT32_TAG =
		SourceField.INT32_FIELD_NUMBER << 3 | WireFormat.WIRETYPE_VARINT;
	private static final int INT64_TAG =
		SourceField.INT64_FIELD_NUMBER << 3 | WireFormat.WIRETYPE_VARINT;
	private static final int FLOAT_TAG =
		SourceField.FLOAT_FIELD_NUMBER << 3 | WireFormat.WIRETYPE_FIXED32;
	private static final int DOUBLE_TAG =
		SourceField.DOUBLE_FIELD_NUMBER << 3 | WireFormat.WIRETYPE_FIXED64;
	private static final int GEO_POINT_TAG =
		SourceField.GEO_POINT_FIELD_NUMBER << 3 | WireFormat.WIRETYPE_LENGTH_DELIMITED;
	private static final int DOCUMENT_TAG =
		SourceField.DOCUMENT_FIELD_NUMBER << 3 | WireFormat.WIRETYPE_LENGTH_DELIMITED;

	private static final int LATITUDE_TAG =
		GeoPointValue.LATITUDE_FIELD_NUMBER << 3 | WireFormat.WIRETYPE_FIXED64;
	private static final int LONGITUDE_TAG =
		GeoPointValue.LONGITUDE_FIELD_NUMBER << 3 | WireFormat.WIRETYPE_FIXED64;

	private static final int VECTOR_VALUES_PACKED_TAG =
		FloatVector.VALUES_FIELD_NUMBER << 3 | WireFormat.WIRETYPE_LENGTH_DELIMITED;
	private static final int VECTOR_VALUES_TAG =
		FloatVector.VALUES_FIELD_NUMBER << 3 | WireFormat.WIRETYPE_FIXED32;

	/**
	 * How deep documents inside documents may nest before the bytes are judged
	 * corrupt rather than followed, matching what the generated parser allows.
	 */
	private static final int MAX_DEPTH = 100;

	private static final float[] EMPTY_VECTOR = new float[0];

	private static Document decodeDocument(
		CodedInputStream in,
		Predicate<String> wanted,
		int depth
	) throws IOException {
		if(depth >= MAX_DEPTH) {
			throw new InvalidProtocolBufferException(
				"Documents nest deeper than " + MAX_DEPTH + " levels"
			);
		}

		var values = Lists.mutable.<Document.Value>empty();

		while(true) {
			var tag = in.readTag();
			if(tag == 0) {
				break;
			}

			if(tag != FIELDS_TAG) {
				/*
				 * Written by a version that keeps something else about a
				 * document. Stepping over it is what leaves the fields
				 * readable.
				 */
				in.skipField(tag);
				continue;
			}

			var limit = in.pushLimit(in.readRawVarint32());
			var value = decodeField(in, wanted, depth);
			requireConsumed(in);
			in.popLimit(limit);

			if(value != null) {
				values.add(value);
			}
		}

		return new Document(values.toArray(new Document.Value[0]));
	}

	/**
	 * Read one field of a document into the value the document holds for it.
	 * {@code null} when the field is not wanted, or when it was written by a
	 * version that has a name or a type this one does not - nothing here can
	 * say what such a value was, so it is left out rather than guessed at.
	 */
	private static Document.Value decodeField(
		CodedInputStream in,
		Predicate<String> wanted,
		int depth
	) throws IOException {
		String name = null;
		String locale = null;
		Object value = null;

		while(true) {
			var tag = in.readTag();
			if(tag == 0) {
				break;
			}

			switch(tag) {
				case NAME_TAG -> {
					name = in.readStringRequireUtf8();
					if(wanted != null && !wanted.test(name)) {
						/*
						 * What is left of the field - its value, as the name
						 * is written first - is stepped over rather than read.
						 */
						in.skipRawBytes(in.getBytesUntilLimit());
						return null;
					}
				}
				case LOCALE_TAG -> locale = in.readStringRequireUtf8();
				case STRING_TAG -> value = in.readStringRequireUtf8();
				case BOOLEAN_TAG -> value = in.readBool();
				case INT32_TAG -> value = in.readInt32();
				case INT64_TAG -> value = in.readInt64();
				case FLOAT_TAG -> value = in.readFloat();
				case DOUBLE_TAG -> value = in.readDouble();
				case GEO_POINT_TAG -> {
					var limit = in.pushLimit(in.readRawVarint32());
					value = decodeGeoPoint(in);
					requireConsumed(in);
					in.popLimit(limit);
				}
				case VECTOR_TAG -> {
					var limit = in.pushLimit(in.readRawVarint32());
					value = decodeVector(in);
					requireConsumed(in);
					in.popLimit(limit);
				}
				case DOCUMENT_TAG -> {
					/*
					 * The names inside an object are not what `wanted` answers
					 * for, so a wanted object comes back whole - cutting it
					 * down to the paths that were asked for is its caller's
					 * job.
					 */
					var limit = in.pushLimit(in.readRawVarint32());
					value = decodeDocument(in, null, depth + 1);
					requireConsumed(in);
					in.popLimit(limit);
				}
				default -> in.skipField(tag);
			}
		}

		if(name == null || value == null) {
			return null;
		}

		return new Document.Value(name, value, locale);
	}

	private static GeoPoint decodeGeoPoint(CodedInputStream in) throws IOException {
		var latitude = 0d;
		var longitude = 0d;

		while(true) {
			var tag = in.readTag();
			if(tag == 0) {
				break;
			}

			switch(tag) {
				case LATITUDE_TAG -> latitude = in.readDouble();
				case LONGITUDE_TAG -> longitude = in.readDouble();
				default -> in.skipField(tag);
			}
		}

		return new GeoPoint(latitude, longitude);
	}

	private static float[] decodeVector(CodedInputStream in) throws IOException {
		var values = EMPTY_VECTOR;
		var count = 0;

		while(true) {
			var tag = in.readTag();
			if(tag == 0) {
				break;
			}

			switch(tag) {
				case VECTOR_VALUES_PACKED_TAG -> {
					/*
					 * A packed run says how many bytes it holds, which is what
					 * sizes the array once instead of growing it per value.
					 */
					var length = in.readRawVarint32();
					var limit = in.pushLimit(length);
					if(count + length / 4 > values.length) {
						values = Arrays.copyOf(values, count + length / 4);
					}
					while(in.getBytesUntilLimit() > 0) {
						if(count == values.length) {
							values = Arrays.copyOf(values, count + 1);
						}
						values[count++] = in.readFloat();
					}
					in.popLimit(limit);
				}
				case VECTOR_VALUES_TAG -> {
					if(count == values.length) {
						values = Arrays.copyOf(values, Math.max(4, count * 2));
					}
					values[count++] = in.readFloat();
				}
				default -> in.skipField(tag);
			}
		}

		return count == values.length ? values : Arrays.copyOf(values, count);
	}

	/**
	 * Require that a message was read to its end. Reading stops at a zero tag,
	 * and inside a message whose length says there is more, a zero is
	 * corruption rather than the end.
	 */
	private static void requireConsumed(CodedInputStream in) throws InvalidProtocolBufferException {
		if(in.getBytesUntilLimit() != 0) {
			throw new InvalidProtocolBufferException(
				"The message ended before its declared length"
			);
		}
	}
}
