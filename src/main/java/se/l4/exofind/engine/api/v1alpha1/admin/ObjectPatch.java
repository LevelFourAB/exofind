package se.l4.exofind.engine.api.v1alpha1.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.errors.ValidationException;

/**
 * A change to some of an admin object, written as paths over the JSON the API
 * reads that object as.
 *
 * <p>Every key of the change is a path naming a place, and what it maps to is
 * what that place becomes: a value replaces it, {@code null} clears it, and a
 * place no path names is left as it is. A path is field names joined by
 * {@code .}, and a name may carry a selector in brackets:
 *
 * <pre>
 * ranking                            the field itself
 * ranking.signals                    a field inside it
 * ranking.signals[]                  a value added to the list
 * ranking.signals[field=sales]       the list entries whose `field` reads as `sales`
 * ranking.signals[field=sales].weight  one field inside those entries
 * </pre>
 *
 * <p>Inside brackets, a backslash stands for the character after it, which is
 * how a selector holds a {@code ]} of its own. A selector picks entries by
 * what they hold rather than by where they sit, so a change written against
 * one read of an object still names the same entry after the list is reordered.
 *
 * <p>A place is replaced whole, so what a change leaves alone is decided by how
 * deeply it reaches: {@code ranking.signals} replaces every entry,
 * {@code ranking.signals[field=sales]} replaces one of them, and
 * {@code ranking.signals[field=sales].weight} replaces one field inside it.
 * Objects a path reaches through are made where the object has none, so a
 * change can name a place nothing has been stored under yet; a list is not,
 * because a selector picks entries rather than inventing one.
 *
 * <p>Paths are reported by the same {@code request:update:*} codes a change to
 * some of a document reports, so the two describe a change in one language.
 */
final class ObjectPatch {
	/**
	 * What one name of a path may be. Narrower than a field of an index: these
	 * name the JSON the API is written in, where {@code .} separates names
	 * rather than being part of one.
	 */
	private static final Pattern NAME = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

	private static final ErrorType MALFORMED = ErrorType
		.withCode("request:update:path_invalid")
		.withArguments("path", "reason")
		.withMessage("`{{path}}` does not name a place to change: {{reason}}");

	private static final ErrorType NO_MATCH = ErrorType
		.withCode("request:update:no_match")
		.withArguments("path")
		.withMessage("`{{path}}` names no value that is stored");

	private static final ErrorType NOT_AN_OBJECT = ErrorType
		.withCode("request:update:not_an_object")
		.withArguments("path", "field")
		.withMessage("`{{path}}` reaches inside `{{field}}`, which holds no fields");

	private static final ErrorType VALUE_REQUIRED = ErrorType
		.withCode("request:update:value_required")
		.withArguments("path", "field")
		.withMessage(
			"`{{field}}` holds a list of values, so `{{path}}` has to say which one, "
			+ "as `{{field}}[field=value]`"
		);

	private static final ErrorType SELECTOR_NOT_SUPPORTED = ErrorType
		.withCode("request:update:selector_not_supported")
		.withArguments("path", "field")
		.withMessage("`{{path}}` names one value of `{{field}}`, which holds no list");

	private static final ErrorType ADD_REACHES_INSIDE = ErrorType
		.withCode("request:update:add_reaches_inside")
		.withArguments("path")
		.withMessage(
			"`{{path}}` reaches inside a value that is being added, which does not exist yet - "
			+ "give the whole value instead"
		);

	/**
	 * One name of a path, and the selector it carries.
	 *
	 * @param name
	 *   the name before the brackets
	 * @param selector
	 *   the text between the brackets, {@code null} when the name has none and
	 *   empty when the brackets hold nothing
	 */
	private record Step(String name, String selector) {
	}

	private ObjectPatch() {
	}

	/**
	 * Apply a change to an object.
	 *
	 * <p>Changes are applied in the order the map holds them, so two paths
	 * naming the same place leave what the later one gives it.
	 *
	 * @param base
	 *   the object as it is stored, left as it was
	 * @param changes
	 *   the places to change, keyed by path
	 * @param mapper
	 *   what the values of the change and the nodes made along the way are read
	 *   with
	 * @return
	 *   a new object
	 * @throws ValidationException
	 *   if a key is not a path, or names a place the object cannot be changed
	 *   at - a list without saying which entry, an entry no selector matches,
	 *   or a field inside something holding no fields. The path that failed is
	 *   the location of the error
	 */
	static ObjectNode applyTo(JsonNode base, Map<String, Object> changes, ObjectMapper mapper) {
		var root = base != null && base.isObject()
			? (ObjectNode) base.deepCopy()
			: mapper.createObjectNode();

		for(var change : changes.entrySet()) {
			var path = change.getKey();

			apply(
				root,
				parse(path),
				0,
				change.getValue() == null
					? NullNode.getInstance()
					: mapper.valueToTree(change.getValue()),
				path,
				mapper
			);
		}

		return root;
	}

	private static void apply(
		ObjectNode parent,
		List<Step> steps,
		int at,
		JsonNode value,
		String path,
		ObjectMapper mapper
	) {
		var step = steps.get(at);
		var last = at == steps.size() - 1;
		var child = parent.get(step.name());

		if(step.selector() == null) {
			applyToField(parent, steps, at, last, child, value, path, mapper);
			return;
		}

		if(step.selector().isEmpty()) {
			applyToAdded(parent, step, last, child, value, path, mapper);
			return;
		}

		applyToMatching(steps, at, last, child, value, path, mapper);
	}

	/**
	 * Change a field, or reach through it.
	 */
	private static void applyToField(
		ObjectNode parent,
		List<Step> steps,
		int at,
		boolean last,
		JsonNode child,
		JsonNode value,
		String path,
		ObjectMapper mapper
	) {
		var step = steps.get(at);

		if(last) {
			if(value.isNull()) {
				parent.remove(step.name());
			} else {
				parent.set(step.name(), value);
			}

			return;
		}

		var into = child;
		if(into == null || into.isNull()) {
			into = mapper.createObjectNode();
			parent.set(step.name(), into);
		}

		if(into.isArray()) {
			throw failed(VALUE_REQUIRED, path, "field", step.name());
		}

		if(!into.isObject()) {
			throw failed(NOT_AN_OBJECT, path, "field", step.name());
		}

		apply((ObjectNode) into, steps, at + 1, value, path, mapper);
	}

	/**
	 * Add a value to a list, making the list where the object has none.
	 */
	private static void applyToAdded(
		ObjectNode parent,
		Step step,
		boolean last,
		JsonNode child,
		JsonNode value,
		String path,
		ObjectMapper mapper
	) {
		if(!last) {
			throw failed(ADD_REACHES_INSIDE, path);
		}

		if(value.isNull()) {
			// Naming a value to add and giving none adds none
			return;
		}

		ArrayNode list;
		if(child == null || child.isNull()) {
			list = mapper.createArrayNode();
			parent.set(step.name(), list);
		} else if(child.isArray()) {
			list = (ArrayNode) child;
		} else {
			throw failed(SELECTOR_NOT_SUPPORTED, path, "field", step.name());
		}

		list.add(value);
	}

	/**
	 * Change every entry of a list that holds what the selector asks of it.
	 */
	private static void applyToMatching(
		List<Step> steps,
		int at,
		boolean last,
		JsonNode child,
		JsonNode value,
		String path,
		ObjectMapper mapper
	) {
		var step = steps.get(at);

		var equals = step.selector().indexOf('=');
		if(equals < 0) {
			throw malformed(path, "a selector names one value as `field=value`");
		}

		var field = name(step.selector().substring(0, equals), path);
		var wanted = step.selector().substring(equals + 1);

		if(child != null && !child.isNull() && !child.isArray()) {
			throw failed(SELECTOR_NOT_SUPPORTED, path, "field", step.name());
		}

		var matched = false;

		if(child != null && child.isArray()) {
			var list = (ArrayNode) child;

			/*
			 * Walked from the end so that removing an entry leaves the ones
			 * still to be looked at where they are.
			 */
			for(var i = list.size() - 1; i >= 0; i--) {
				var entry = list.get(i);
				if(!entry.isObject() || !holds((ObjectNode) entry, field, wanted)) {
					continue;
				}

				matched = true;

				if(!last) {
					apply((ObjectNode) entry, steps, at + 1, value, path, mapper);
				} else if(value.isNull()) {
					list.remove(i);
				} else {
					list.set(i, value);
				}
			}
		}

		if(!matched) {
			throw failed(NO_MATCH, path);
		}
	}

	/**
	 * Get whether an entry holds what a selector asks of it. Read as text, so a
	 * value held as a number matches the digits that were written.
	 */
	private static boolean holds(ObjectNode entry, String field, String wanted) {
		var value = entry.get(field);

		return value != null && !value.isNull() && wanted.equals(value.asText());
	}

	/**
	 * Take a path apart.
	 *
	 * @throws ValidationException
	 *   if the text is not a path, located at the text itself
	 */
	private static List<Step> parse(String text) {
		var steps = new ArrayList<Step>();
		var name = new StringBuilder();
		var at = 0;

		while(at < text.length()) {
			var c = text.charAt(at);

			if(c == '.') {
				steps.add(new Step(name(name.toString(), text), null));
				name.setLength(0);

				at++;
				if(at >= text.length()) {
					throw malformed(text, "a `.` needs a field after it");
				}

				continue;
			}

			if(c != '[') {
				name.append(c);
				at++;
				continue;
			}

			var selector = new StringBuilder();
			at++;
			while(at < text.length() && text.charAt(at) != ']') {
				/*
				 * A backslash stands for the character after it, so that a
				 * value holding a `]` can be told from the one that closes the
				 * selector.
				 */
				if(text.charAt(at) == '\\') {
					at++;

					if(at >= text.length()) {
						throw malformed(text, "the path ends in a backslash");
					}
				}

				selector.append(text.charAt(at));
				at++;
			}

			if(at >= text.length()) {
				throw malformed(text, "the `[` is never closed");
			}

			steps.add(new Step(name(name.toString(), text), selector.toString()));
			name.setLength(0);
			at++;

			if(at >= text.length()) {
				continue;
			}

			if(text.charAt(at) != '.') {
				throw malformed(text, "a `]` is followed by `.` and a field, or by nothing");
			}

			at++;
			if(at >= text.length()) {
				throw malformed(text, "a `.` needs a field after it");
			}
		}

		if(name.length() > 0) {
			steps.add(new Step(name(name.toString(), text), null));
		}

		if(steps.isEmpty()) {
			throw malformed(text, "a field name is required");
		}

		return steps;
	}

	private static String name(String name, String text) {
		if(name.isEmpty()) {
			throw malformed(text, "a field name is required");
		}

		if(!NAME.matcher(name).matches()) {
			throw malformed(text, "`" + name + "` is not a field name");
		}

		return name;
	}

	private static ValidationException malformed(String text, String reason) {
		return failed(MALFORMED, text, "reason", reason);
	}

	private static ValidationException failed(ErrorType type, String path, Object... arguments) {
		var all = new Object[arguments.length + 2];
		all[0] = "path";
		all[1] = path;
		System.arraycopy(arguments, 0, all, 2, arguments.length);

		return new ValidationException(
			type.toMessage(ObjectLocation.root().forField(path), all)
		);
	}
}
