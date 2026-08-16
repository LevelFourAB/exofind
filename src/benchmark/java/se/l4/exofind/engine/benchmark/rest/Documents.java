package se.l4.exofind.engine.benchmark.rest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import se.l4.exofind.engine.index.Document;
import se.l4.exofind.engine.index.GeoPoint;

/**
 * Writes generated documents and definitions the way a caller of the API sends
 * them.
 *
 * <p>A field holding one value is written as that value and a field holding
 * several as an array, a value of an object field as an object, and a geo point
 * as {@code lat} and {@code lon} - which is what the API reads them back as.
 */
public final class Documents {
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private Documents() {
	}

	/**
	 * Write documents as newline delimited JSON, which is what the documents
	 * endpoint takes a batch as.
	 */
	public static String ndjson(List<Document> documents) {
		var builder = new StringBuilder(documents.size() * 512);

		for(var document : documents) {
			builder.append(json(document)).append('\n');
		}

		return builder.toString();
	}

	/**
	 * Write one document as a JSON object.
	 */
	public static String json(Document document) {
		return write(fields(document));
	}

	/**
	 * Write any value as JSON, for building request bodies out of maps and
	 * lists.
	 *
	 * @throws IllegalArgumentException
	 *   if the value holds something Jackson cannot write
	 */
	public static String write(Object value) {
		try {
			return MAPPER.writeValueAsString(value);
		} catch(JsonProcessingException e) {
			throw new IllegalArgumentException("Could not write " + value + " as JSON", e);
		}
	}

	/**
	 * Read a JSON object into a map, for looking at what a node answered.
	 *
	 * @throws IllegalArgumentException
	 *   if the text is not a JSON object
	 */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> read(String json) {
		try {
			return MAPPER.readValue(json, Map.class);
		} catch(JsonProcessingException e) {
			throw new IllegalArgumentException("Could not read the answer as JSON", e);
		}
	}

	private static Map<String, Object> fields(Document document) {
		var json = new LinkedHashMap<String, Object>();

		for(var value : document.fields()) {
			json.merge(value.name(), value(value.value()), Documents::append);
		}

		return json;
	}

	/**
	 * Fold a second value of a field into the first, which is what turns a
	 * field holding several values into an array.
	 */
	private static Object append(Object held, Object added) {
		if(held instanceof List<?> list) {
			var values = new ArrayList<Object>(list);
			values.add(added);
			return values;
		}

		var values = new ArrayList<>();
		values.add(held);
		values.add(added);
		return values;
	}

	private static Object value(Object value) {
		if(value instanceof GeoPoint point) {
			return Map.of("lat", point.latitude(), "lon", point.longitude());
		}

		if(value instanceof Document nested) {
			return fields(nested);
		}

		return value;
	}
}
