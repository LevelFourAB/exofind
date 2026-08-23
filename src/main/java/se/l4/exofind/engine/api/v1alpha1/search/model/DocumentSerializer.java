package se.l4.exofind.engine.api.v1alpha1.search.model;

import java.io.IOException;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import se.l4.exofind.engine.index.Document;

/**
 * Writes a {@link Document} as JSON the way it was given: a scalar when a
 * field holds one value, an array when it holds several, an object keyed by
 * locale tag when it is locale specific and a JSON object when the value is a
 * document of its own.
 *
 * <p>Fields keep the order they were given in, keyed by first occurrence.
 */
public class DocumentSerializer extends JsonSerializer<Document> {
	@Override
	public void serialize(
		Document document,
		JsonGenerator gen,
		SerializerProvider serializers
	) throws IOException {
		/*
		 * Values of one field can sit apart in the array, but JSON writes
		 * every key once - so each name is written at its first occurrence,
		 * with the values gathered by scanning ahead and occurrences after
		 * the first recognized by scanning back. The scans are quadratic in
		 * the number of fields, which stays cheaper than building a map per
		 * hit for the handful of fields a search asks back.
		 */
		var fields = document.fields();

		gen.writeStartObject();

		outer:
		for(var i = 0; i < fields.length; i++) {
			var field = fields[i];

			for(var j = 0; j < i; j++) {
				if(fields[j].name().equals(field.name())) {
					continue outer;
				}
			}

			gen.writeFieldName(field.name());

			if(field.locale() == null) {
				writeValues(fields, i, field.name(), null, gen, serializers);
			} else {
				gen.writeStartObject();

				locales:
				for(var j = i; j < fields.length; j++) {
					var candidate = fields[j];
					if(!candidate.name().equals(field.name())) {
						continue;
					}

					for(var k = i; k < j; k++) {
						if(fields[k].name().equals(field.name())
							&& Objects.equals(fields[k].locale(), candidate.locale()))
						{
							continue locales;
						}
					}

					gen.writeFieldName(candidate.locale());
					writeValues(fields, j, field.name(), candidate.locale(), gen, serializers);
				}

				gen.writeEndObject();
			}
		}

		gen.writeEndObject();
	}

	/**
	 * Write everything the field holds under one key from {@code from} on: the
	 * value itself when there is one, an array of them when there are several.
	 */
	private void writeValues(
		Document.Value[] fields,
		int from,
		String name,
		String locale,
		JsonGenerator gen,
		SerializerProvider serializers
	) throws IOException {
		var count = 0;
		for(var j = from; j < fields.length; j++) {
			if(fields[j].name().equals(name) && Objects.equals(fields[j].locale(), locale)) {
				count++;
			}
		}

		if(count == 1) {
			writeValue(fields[from].value(), gen, serializers);
			return;
		}

		gen.writeStartArray();
		for(var j = from; j < fields.length; j++) {
			if(fields[j].name().equals(name) && Objects.equals(fields[j].locale(), locale)) {
				writeValue(fields[j].value(), gen, serializers);
			}
		}
		gen.writeEndArray();
	}

	private void writeValue(
		Object value,
		JsonGenerator gen,
		SerializerProvider serializers
	) throws IOException {
		switch(value) {
			case null -> gen.writeNull();
			case Document nested -> serialize(nested, gen, serializers);
			case String s -> gen.writeString(s);
			case Boolean b -> gen.writeBoolean(b);
			case Integer i -> gen.writeNumber(i);
			case Long l -> gen.writeNumber(l);
			case Float f -> gen.writeNumber(f);
			case Double d -> gen.writeNumber(d);
			default -> serializers.defaultSerializeValue(value, gen);
		}
	}
}
