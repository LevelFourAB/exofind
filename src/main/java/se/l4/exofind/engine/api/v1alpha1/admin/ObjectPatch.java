package se.l4.exofind.engine.api.v1alpha1.admin;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.eclipse.collections.api.list.ListIterable;

import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.patch.PatchErrors;
import se.l4.exofind.engine.patch.PatchKeys;
import se.l4.exofind.engine.patch.PatchPath;

/**
 * A change to some of an admin object, written as paths over the JSON the API
 * reads that object as.
 *
 * <p>Every key of the change is a path naming a place, and what it maps to is
 * what that place becomes: a value replaces it, {@code null} clears it, and a
 * place no path names stays as it is. {@link PatchPath} reads the syntax,
 * which is the same syntax a change to a document is written in:
 *
 * <pre>
 * ranking                              the field itself
 * ranking.signals                      a field inside it
 * ranking.signals[]                    a value added to the list
 * ranking.signals[field=sales]         the list entries whose `field` reads as `sales`
 * ranking.signals[field=sales].weight  one field inside those entries
 * fields.variants\.colour.interpret    a field of the field named `variants.colour`
 * </pre>
 *
 * <p>A place is replaced whole, so what a change leaves alone depends on how
 * deeply it reaches: {@code ranking.signals} replaces every entry,
 * {@code ranking.signals[field=sales]} replaces one of them, and
 * {@code ranking.signals[field=sales].weight} replaces one field inside it. A
 * selector picks entries by what they hold and not by where they sit, so a
 * change written against one read of an object names the same entry after the
 * list is reordered.
 *
 * <p>Objects a path reaches through are made where the object has none, so a
 * change can name a place nothing has been stored under yet. A list is not,
 * because a selector picks entries instead of inventing one, and a selector
 * naming nothing stored is {@code settings:patch:no_match}.
 *
 * <p>A selector holding a single word names an entry by the key of its list,
 * which the caller supplies as {@link PatchKeys}. A word on a list that
 * declares no key is {@code settings:patch:key_unsupported}, and the same
 * mistake in a path into a document answers with
 * {@code document:patch:key_unsupported}. The two families differ only in that
 * prefix, and {@link PatchErrors} states what they share.
 */
final class ObjectPatch {
	private static final ErrorType MALFORMED = ErrorType
		.withCode("settings:patch:path_invalid")
		.withStatus(400)
		.withArguments("path", "reason")
		.withMessage(PatchErrors.PATH_INVALID);

	private static final ErrorType NO_MATCH = ErrorType
		.withCode("settings:patch:no_match")
		.withStatus(400)
		.withArguments("path")
		.withMessage(PatchErrors.NO_MATCH);

	private static final ErrorType NOT_AN_OBJECT = ErrorType
		.withCode("settings:patch:not_an_object")
		.withStatus(400)
		.withArguments("path", "field")
		.withMessage(PatchErrors.NOT_AN_OBJECT);

	private static final ErrorType VALUE_REQUIRED = ErrorType
		.withCode("settings:patch:selector_required")
		.withStatus(400)
		.withArguments("path", "field", "how")
		.withMessage(PatchErrors.SELECTOR_REQUIRED);

	private static final ErrorType SELECTOR_NOT_SUPPORTED = ErrorType
		.withCode("settings:patch:selector_unsupported")
		.withStatus(400)
		.withArguments("path", "field")
		.withMessage(PatchErrors.SELECTOR_UNSUPPORTED);

	private static final ErrorType MATCH_NOT_AN_OBJECT = ErrorType
		.withCode("settings:patch:match_not_an_object")
		.withStatus(400)
		.withArguments("path", "field")
		.withMessage(PatchErrors.MATCH_NOT_AN_OBJECT);

	private static final ErrorType KEY_NOT_DECLARED = ErrorType
		.withCode("settings:patch:key_unsupported")
		.withStatus(400)
		.withArguments("path", "field")
		.withMessage(PatchErrors.KEY_UNSUPPORTED);

	private static final ErrorType ADD_NOT_MULTIPLE = ErrorType
		.withCode("settings:patch:add_unsupported")
		.withStatus(400)
		.withArguments("path", "field")
		.withMessage(PatchErrors.ADD_UNSUPPORTED);

	private static final ErrorType ADD_REACHES_INSIDE = ErrorType
		.withCode("settings:patch:add_reaches_inside")
		.withStatus(400)
		.withArguments("path")
		.withMessage(PatchErrors.ADD_REACHES_INSIDE);

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
	static ObjectNode applyTo(
		JsonNode base,
		Map<String, Object> changes,
		ObjectMapper mapper,
		PatchKeys keys
	) {
		var root = base != null && base.isObject()
			? (ObjectNode) base.deepCopy()
			: mapper.createObjectNode();

		for(var change : changes.entrySet()) {
			var path = change.getKey();

			apply(
				root,
				PatchPath.parse(path, MALFORMED),
				0,
				change.getValue() == null
					? NullNode.getInstance()
					: mapper.valueToTree(change.getValue()),
				path,
				mapper,
				keys
			);
		}

		return root;
	}

	private static void apply(
		ObjectNode parent,
		ListIterable<PatchPath.Step> steps,
		int at,
		JsonNode value,
		String path,
		ObjectMapper mapper,
		PatchKeys keys
	) {
		var step = steps.get(at);
		var last = at == steps.size() - 1;
		var child = parent.get(step.name());

		if(!step.hasSelector()) {
			applyToField(parent, steps, at, last, child, value, path, mapper, keys);
			return;
		}

		if(step.adds()) {
			applyToAdded(parent, step, last, child, value, path, mapper);
			return;
		}

		var field = step.selectorField();
		if(field == null) {
			field = keys.keyOf(steps.collect(PatchPath.Step::name).subList(0, at + 1));

			if(field == null) {
				throw PatchErrors.failed(KEY_NOT_DECLARED, path, "field", step.name());
			}
		}

		applyToMatching(steps, at, last, child, value, path, mapper, keys, field);
	}

	/**
	 * Change a field, or reach through it.
	 */
	private static void applyToField(
		ObjectNode parent,
		ListIterable<PatchPath.Step> steps,
		int at,
		boolean last,
		JsonNode child,
		JsonNode value,
		String path,
		ObjectMapper mapper,
		PatchKeys keys
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
			throw PatchErrors.failed(
				VALUE_REQUIRED,
				path,
				"field", step.name(),
				"how", "`" + step.name() + "[field=value]`"
			);
		}

		if(!into.isObject()) {
			throw PatchErrors.failed(NOT_AN_OBJECT, path, "field", step.name());
		}

		apply((ObjectNode) into, steps, at + 1, value, path, mapper, keys);
	}

	/**
	 * Add a value to a list, making the list where the object has none.
	 */
	private static void applyToAdded(
		ObjectNode parent,
		PatchPath.Step step,
		boolean last,
		JsonNode child,
		JsonNode value,
		String path,
		ObjectMapper mapper
	) {
		if(!last) {
			throw PatchErrors.failed(ADD_REACHES_INSIDE, path);
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
			throw PatchErrors.failed(ADD_NOT_MULTIPLE, path, "field", step.name());
		}

		list.add(value);
	}

	/**
	 * Change every entry of a list that holds what the selector asks of it.
	 */
	private static void applyToMatching(
		ListIterable<PatchPath.Step> steps,
		int at,
		boolean last,
		JsonNode child,
		JsonNode value,
		String path,
		ObjectMapper mapper,
		PatchKeys keys,
		String field
	) {
		var step = steps.get(at);

		if(child != null && !child.isNull() && !child.isArray()) {
			throw PatchErrors.failed(SELECTOR_NOT_SUPPORTED, path, "field", step.name());
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
				if(!entry.isObject()) {
					throw PatchErrors.failed(
						MATCH_NOT_AN_OBJECT,
						path,
						"field", step.name()
					);
				}

				if(!holds((ObjectNode) entry, field, step.selectorValue())) {
					continue;
				}

				matched = true;

				if(!last) {
					apply((ObjectNode) entry, steps, at + 1, value, path, mapper, keys);
				} else if(value.isNull()) {
					list.remove(i);
				} else {
					list.set(i, value);
				}
			}
		}

		if(!matched) {
			throw PatchErrors.failed(NO_MATCH, path);
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
}
