package se.l4.exofind.engine.index;

import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.schema.Field;

/**
 * The place in a document that one change of a {@link DocumentPatch} names,
 * taken apart from the text it was written as.
 *
 * <p>A path is a field name, and may carry a selector in brackets and a field
 * inside the value that selector picks:
 *
 * <pre>
 * price                    the field itself
 * title[sv]                one locale variant of a locale specific field
 * tags[]                   a value added to the ones the field holds
 * variants[V-2]            the object value whose key reads as `V-2`
 * variants[sku=V-2]        the object values whose `sku` reads as `V-2`
 * variants[sku=V-2].price  one field inside those values
 * </pre>
 *
 * <p>A selector holding an unescaped {@code =} names a field inside the value
 * and what it reads as; one holding none is a single word, and what that word
 * means is decided by the field it was given to - a locale tag on a locale
 * specific field, a key on an object field. Only the first unescaped {@code =}
 * splits, so a later one belongs to the value.
 *
 * <p>Inside brackets, a backslash escapes the character after it, which is how
 * a selector holds a {@code ]} of its own, and how a single word holds an
 * {@code =} without becoming the other shape.
 *
 * <p>A path without brackets carries its whole name in {@link #field()}, dots
 * included: whether {@code dimensions.width} names a field of that name or the
 * field {@code width} inside the object {@code dimensions} is a question for
 * the definition of the index, which this does not read.
 *
 * @param field
 *   the name before the brackets
 * @param selectorField
 *   the name before the {@code =} of a selector, {@code null} when the
 *   selector is a single word or the path has no selector
 * @param selectorValue
 *   what the selector asks for - the text after the {@code =}, or the single
 *   word. {@code null} when the path has no selector and empty when the
 *   brackets hold nothing
 * @param inner
 *   the name after the brackets, {@code null} when the path ends at the
 *   selector
 */
public record DocumentPath(
	String field,
	String selectorField,
	String selectorValue,
	String inner
) {
	private static final ErrorType MALFORMED = ErrorType
		.withCode("request:update:path_invalid")
		.withArguments("path", "reason")
		.withMessage("`{{path}}` does not name a place in a document: {{reason}}");

	/**
	 * Take a path apart.
	 *
	 * @param text
	 *   the path as it was written
	 * @throws ValidationException
	 *   if the text is not a path, located at the text itself
	 */
	public static DocumentPath parse(String text) {
		var bracket = text.indexOf('[');
		if(bracket < 0) {
			return new DocumentPath(
				name(text, text, "a field name is required"),
				null,
				null,
				null
			);
		}

		var field = name(
			text.substring(0, bracket),
			text,
			"a selector needs a field before it"
		);

		/*
		 * The two halves are collected as one buffer until an unescaped `=`
		 * says there are two, at which point what has been read so far is the
		 * name and the rest is the value. A second `=` belongs to the value, so
		 * only the first one splits.
		 */
		var selector = new StringBuilder();
		String selectorField = null;
		var at = bracket + 1;
		while(at < text.length() && text.charAt(at) != ']') {
			/*
			 * A backslash stands for the character after it, so that a value
			 * holding a `]` can be told from the one that closes the selector,
			 * and a single word holding an `=` from a name and a value.
			 */
			if(text.charAt(at) == '\\') {
				at++;

				if(at >= text.length()) {
					throw malformed(text, "the path ends in a backslash");
				}
			} else if(text.charAt(at) == '='
				&& selectorField == null
				&& selector.length() > 0) {
				/*
				 * Nothing before the `=` is not a name, so it stays part of a
				 * single word rather than becoming an empty one.
				 */
				selectorField = name(
					selector.toString(),
					text,
					"a `=` needs a field before it"
				);
				selector.setLength(0);
				at++;
				continue;
			}

			selector.append(text.charAt(at));
			at++;
		}

		if(at >= text.length()) {
			throw malformed(text, "the `[` is never closed");
		}

		var rest = text.substring(at + 1);
		if(rest.isEmpty()) {
			return new DocumentPath(field, selectorField, selector.toString(), null);
		}

		if(rest.charAt(0) != '.') {
			throw malformed(text, "a `]` is followed by `.` and a field, or by nothing");
		}

		return new DocumentPath(
			field,
			selectorField,
			selector.toString(),
			name(rest.substring(1), text, "a `.` needs a field after it")
		);
	}

	/**
	 * Read one name of a path, refusing what can never be a field.
	 */
	private static String name(String name, String text, String whenEmpty) {
		if(name.isEmpty()) {
			throw malformed(text, whenEmpty);
		}

		if(!Field.VALID_NAME_PATTERN.matcher(name).matches()) {
			throw malformed(
				text,
				"`" + name + "` is not a field name"
			);
		}

		return name;
	}

	private static ValidationException malformed(String text, String reason) {
		return new ValidationException(
			MALFORMED.toMessage(
				ObjectLocation.root().forField(text),
				"path", text,
				"reason", reason
			)
		);
	}

	/**
	 * Write this path back the way it was given, which is how a change points
	 * at itself in an error.
	 */
	@Override
	public String toString() {
		var text = new StringBuilder(field);

		if(selectorValue != null) {
			text.append('[');

			if(selectorField != null) {
				/*
				 * A field name holds no `=` or `]`, so it is written as it is,
				 * and the `=` after it is what makes the value's own `=`
				 * signs ordinary text.
				 */
				text.append(selectorField).append('=');
			}

			escape(text, selectorValue, selectorField == null);
			text.append(']');
		}

		if(inner != null) {
			text.append('.').append(inner);
		}

		return text.toString();
	}

	/**
	 * Write the text of a selector so that reading it back gives what went in.
	 *
	 * @param equals
	 *   whether an {@code =} has to be escaped, which it does exactly where an
	 *   unescaped one would split the selector in two
	 */
	private static void escape(StringBuilder text, String value, boolean equals) {
		for(var i = 0; i < value.length(); i++) {
			var c = value.charAt(i);
			if(c == ']' || c == '\\' || (equals && c == '=')) {
				text.append('\\');
			}

			text.append(c);
		}
	}
}
