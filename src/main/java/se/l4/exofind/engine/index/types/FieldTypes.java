package se.l4.exofind.engine.index.types;

import java.util.Optional;

import se.l4.exofind.engine.index.schema.FieldTypeDef;

/**
 * Lookup of the {@link FieldType} that handles a stored field type.
 *
 * A definition can name a type that this build of the engine does not handle,
 * either because it is not implemented yet or because the definition was
 * written by a newer version. That is reported as an absent result rather than
 * an exception, so callers can turn it into an error that says which field is
 * at fault.
 */
public final class FieldTypes {
	private static final StringFieldType STRING = new StringFieldType();
	private static final BooleanFieldType BOOLEAN = new BooleanFieldType();
	private static final VectorFieldType VECTOR = new VectorFieldType();
	private static final Int32FieldType INT32 = new Int32FieldType();
	private static final Int64FieldType INT64 = new Int64FieldType();
	private static final FloatFieldType FLOAT = new FloatFieldType();
	private static final DoubleFieldType DOUBLE = new DoubleFieldType();
	private static final TimestampFieldType TIMESTAMP = new TimestampFieldType();
	private static final GeoPointFieldType GEO_POINT = new GeoPointFieldType();
	private static final ObjectFieldType OBJECT = new ObjectFieldType();

	private FieldTypes() {
	}

	/**
	 * Get the type that handles the given definition.
	 *
	 * @param def
	 * @return
	 *   the type, or empty if this version of the engine can not index it
	 */
	public static Optional<FieldType> forDef(FieldTypeDef def) {
		return switch(def.getTypeCase()) {
			case STRING -> Optional.of(STRING);
			case BOOLEAN -> Optional.of(BOOLEAN);
			case VECTOR -> Optional.of(VECTOR);
			case INT32 -> Optional.of(INT32);
			case INT64 -> Optional.of(INT64);
			case FLOAT -> Optional.of(FLOAT);
			case DOUBLE -> Optional.of(DOUBLE);
			case TIMESTAMP -> Optional.of(TIMESTAMP);
			case GEO_POINT -> Optional.of(GEO_POINT);
			case OBJECT -> Optional.of(OBJECT);
			default -> Optional.empty();
		};
	}
}
