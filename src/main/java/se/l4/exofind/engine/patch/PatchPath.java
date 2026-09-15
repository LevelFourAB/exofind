package se.l4.exofind.engine.patch;

import java.util.regex.Pattern;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.MutableList;

import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ValidationException;

/**
 * A path naming a place to change, taken apart from the text it was written
 * as.
 *
 * <p>Every endpoint that changes part of something reads its paths with this
 * class, so one syntax serves a change to a document and a change to search
 * settings. A path is names joined by {@code .}, and a name can carry a
 * selector in brackets:
 *
 * <pre>
 * price                      the field itself
 * dimensions.width           a name inside it
 * tags[]                     a value added to the ones the place holds
 * title[sv]                  the value going by one word
 * variants[sku=V-2]          the values whose `sku` reads as `V-2`
 * variants[sku=V-2].price    one name inside those values
 * fields.variants\.colour    a name holding a `.` of its own
 * </pre>
 *
 * <p>A backslash escapes the character after it, in a name and inside brackets
 * alike. Escape a character the syntax uses: a {@code .}, a {@code [} or a
 * backslash in a name, and a {@code ]}, an {@code =} or a backslash in a
 * selector. Every other character stands for itself, so a locale tag such as
 * {@code en-GB} needs no backslash.
 *
 * <p>A selector holding an unescaped {@code =} names a field inside the value
 * and what that field reads as. Only the first unescaped {@code =} splits, so
 * a later one belongs to the value. A selector holding no unescaped {@code =}
 * is a single word, and empty brackets add a value.
 *
 * <p>This class reads the syntax and nothing else. What a name reaches and
 * what a single word means are for the caller to decide against the index
 * definition or the settings model, so a path that parses here can still be
 * refused with one of the other codes {@link PatchErrors} describes.
 *
 * <p>Instances are immutable and every method is safe to call from several
 * threads.
 */
public final class PatchPath {
	/**
	 * What the field of a selector can hold. Narrower than a name: a selector
	 * reads a declared field, and every declared name is written from these
	 * characters.
	 */
	private static final Pattern SELECTOR_FIELD = Pattern.compile("[a-zA-Z0-9_.*]+");

	private PatchPath() {
	}

	/**
	 * One name of a path, and the selector it carries.
	 *
	 * @param name
	 *   the name before the brackets, with every escape resolved
	 * @param selectorField
	 *   the name before the {@code =} of the selector, {@code null} when the
	 *   selector is a single word or the name carries no selector
	 * @param selectorValue
	 *   what the selector asks for: the text after the {@code =}, or the
	 *   single word. {@code null} when the name carries no selector, and empty
	 *   when the brackets hold nothing
	 */
	public record Step(String name, String selectorField, String selectorValue) {
		/**
		 * Get whether this name carries a selector.
		 */
		public boolean hasSelector() {
			return selectorValue != null;
		}

		/**
		 * Get whether this name carries empty brackets, which add a value.
		 */
		public boolean adds() {
			return selectorField == null && "".equals(selectorValue);
		}

		/**
		 * Get whether this name carries a single word, which the caller reads
		 * as a key or a locale tag.
		 */
		public boolean isWord() {
			return selectorField == null
				&& selectorValue != null
				&& !selectorValue.isEmpty();
		}
	}

	/**
	 * Take a path apart.
	 *
	 * @param text
	 *   the path as it was written
	 * @param pathInvalid
	 *   the code to report text that is not a path with, which carries the
	 *   prefix of the family the caller answers in
	 * @return
	 *   the names of the path, in the order they were written, never empty
	 * @throws ValidationException
	 *   if the text is not a path, located at the text itself
	 */
	public static ImmutableList<Step> parse(String text, ErrorType pathInvalid) {
		MutableList<Step> steps = Lists.mutable.empty();
		var name = new StringBuilder();
		var at = 0;

		while(at < text.length()) {
			var c = text.charAt(at);

			if(c == '\\') {
				at++;

				if(at >= text.length()) {
					throw malformed(pathInvalid, text, "the path ends in a backslash");
				}

				name.append(text.charAt(at));
				at++;
				continue;
			}

			if(c == '.') {
				steps.add(new Step(name(pathInvalid, name, text), null, null));
				name.setLength(0);

				at++;
				if(at >= text.length()) {
					throw malformed(pathInvalid, text, "a `.` needs a name after it");
				}

				continue;
			}

			if(c != '[') {
				name.append(c);
				at++;
				continue;
			}

			at = selector(pathInvalid, text, at + 1, name, steps);
		}

		if(name.length() > 0) {
			steps.add(new Step(name(pathInvalid, name, text), null, null));
		}

		if(steps.isEmpty()) {
			throw malformed(pathInvalid, text, "a name is required");
		}

		return steps.toImmutable();
	}

	/**
	 * Read the selector that starts at {@code at}, add the name carrying it,
	 * and answer with the offset the path goes on from.
	 */
	private static int selector(
		ErrorType pathInvalid,
		String text,
		int at,
		StringBuilder name,
		MutableList<Step> steps
	) {
		var selector = new StringBuilder();
		String selectorField = null;

		while(at < text.length() && text.charAt(at) != ']') {
			if(text.charAt(at) == '\\') {
				at++;

				if(at >= text.length()) {
					throw malformed(pathInvalid, text, "the path ends in a backslash");
				}
			} else if(text.charAt(at) == '='
				&& selectorField == null
				&& selector.length() > 0) {
				/*
				 * Nothing before the `=` is not a field name, so it stays part
				 * of a single word instead of becoming an empty one.
				 */
				selectorField = selectorField(pathInvalid, selector.toString(), text);
				selector.setLength(0);
				at++;
				continue;
			}

			selector.append(text.charAt(at));
			at++;
		}

		if(at >= text.length()) {
			throw malformed(pathInvalid, text, "the `[` is never closed");
		}

		steps.add(new Step(
			name(pathInvalid, name, text),
			selectorField,
			selector.toString()
		));
		name.setLength(0);
		at++;

		if(at >= text.length()) {
			return at;
		}

		if(text.charAt(at) != '.') {
			throw malformed(
				pathInvalid,
				text,
				"a `]` is followed by `.` and a name, or by nothing"
			);
		}

		at++;
		if(at >= text.length()) {
			throw malformed(pathInvalid, text, "a `.` needs a name after it");
		}

		return at;
	}

	/**
	 * Write one name back the way it parses, so a path built from names holding
	 * a {@code .} reads back as the same names.
	 *
	 * @param text
	 *   what to write to
	 * @param name
	 *   the name as it was resolved
	 */
	public static void appendName(StringBuilder text, String name) {
		for(var i = 0; i < name.length(); i++) {
			var c = name.charAt(i);
			if(c == '.' || c == '[' || c == '\\') {
				text.append('\\');
			}

			text.append(c);
		}
	}

	/**
	 * Write a selector back the way it parses, brackets included.
	 *
	 * @param text
	 *   what to write to
	 * @param selectorField
	 *   the name before the {@code =}, {@code null} for a single word
	 * @param selectorValue
	 *   what the selector asks for, empty to add a value
	 */
	public static void appendSelector(
		StringBuilder text,
		String selectorField,
		String selectorValue
	) {
		text.append('[');

		if(selectorField != null) {
			/*
			 * A field name holds no `=` or `]`, so it is written as it stands,
			 * and the `=` after it makes the value's own `=` signs ordinary
			 * text.
			 */
			text.append(selectorField).append('=');
		}

		for(var i = 0; i < selectorValue.length(); i++) {
			var c = selectorValue.charAt(i);
			if(c == ']' || c == '\\' || (selectorField == null && c == '=')) {
				text.append('\\');
			}

			text.append(c);
		}

		text.append(']');
	}

	/**
	 * Take one name as it was written. Anything but nothing is a name: a place
	 * can be keyed by a locale tag or by a dotted field path, and which names
	 * reach something is for the caller to answer.
	 */
	private static String name(ErrorType pathInvalid, StringBuilder name, String text) {
		if(name.length() == 0) {
			throw malformed(pathInvalid, text, "a name is required");
		}

		return name.toString();
	}

	/**
	 * Take the field a selector reads, which names a declared field.
	 */
	private static String selectorField(ErrorType pathInvalid, String name, String text) {
		if(name.isEmpty()) {
			throw malformed(pathInvalid, text, "a `=` needs a field before it");
		}

		if(!SELECTOR_FIELD.matcher(name).matches()) {
			throw malformed(pathInvalid, text, "`" + name + "` is not a field name");
		}

		return name;
	}

	private static ValidationException malformed(
		ErrorType pathInvalid,
		String text,
		String reason
	) {
		return PatchErrors.failed(pathInvalid, text, "reason", reason);
	}
}
