package se.l4.exofind.engine.logging;

import org.jboss.logmanager.ExtLogRecord;

import io.quarkus.logging.json.runtime.JsonFormatter.JsonLogGenerator;
import io.quarkus.logging.json.runtime.JsonProvider;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Writes the fields a {@link Log} put on a record as fields of the JSON object
 * that record is formatted into.
 *
 * <p>Called once per record by the JSON formatter, for records from every
 * logger in the application. A record carrying no fields is left alone, so
 * logging from a library that knows nothing about {@link Log} is unaffected.
 *
 * <p>Integral numbers are written as JSON numbers. Everything else is written
 * as a string, booleans included - the generator of the formatter has no
 * boolean type, so {@code true} is written as {@code "true"}.
 */
@ApplicationScoped
public class LogFieldJsonProvider implements JsonProvider {
	@Override
	public void writeTo(JsonLogGenerator generator, ExtLogRecord record)
		throws Exception {
		var parameters = record.getParameters();
		if(parameters == null) {
			return;
		}

		for(var parameter : parameters) {
			if(parameter instanceof LogField field) {
				write(generator, field);
			}
		}
	}

	private static void write(JsonLogGenerator generator, LogField field)
		throws Exception {
		switch(field.value()) {
			case Boolean value -> generator.add(field.key(), (boolean) value);
			case Long value -> generator.add(field.key(), (long) value);
			case Integer value -> generator.add(field.key(), (int) value);
			case Short value -> generator.add(field.key(), value.intValue());
			case Byte value -> generator.add(field.key(), value.intValue());
			case null, default -> generator.add(field.key(), String.valueOf(field.value()));
		}
	}
}
