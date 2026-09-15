package se.l4.exofind.engine.index;

import org.eclipse.collections.api.list.ListIterable;

import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.patch.PatchErrors;
import se.l4.exofind.engine.patch.PatchPath;

/**
 * The place in a document that one change of a {@link DocumentPatch} names,
 * taken apart from the text it was written as.
 *
 * <p>{@link PatchPath} reads the syntax, which is the same syntax a change to
 * search settings is written in. This class says what the names of a path mean
 * for a document: a document carries one selector, and the names on either
 * side of it are a dotted path through object values.
 *
 * <pre>
 * price                    the field itself
 * dimensions.width         a field inside a single object value
 * title[sv]                one locale variant of a locale specific field
 * tags[]                   a value added to the ones the field holds
 * variants[V-2]            the object value whose key reads as `V-2`
 * variants[sku=V-2]        the object values whose `sku` reads as `V-2`
 * variants[sku=V-2].price  one field inside those values
 * </pre>
 *
 * <p>A selector holding an unescaped {@code =} names a field inside the value
 * and what it reads as. One holding a single word is a locale tag on a locale
 * specific field and a declared key on an object field, and the field it was
 * given to decides which.
 *
 * <p>Which objects sit on the path {@code dimensions.width}, and where it
 * stops being one, is a question for the definition of the index, which this
 * does not read. A declared name holds no {@code .}, so escaping one reaches
 * the same place as writing it plain.
 *
 * @param field
 *   the dotted name up to and including the name carrying the selector
 * @param selectorField
 *   the name before the {@code =} of a selector, {@code null} when the
 *   selector is a single word or the path has no selector
 * @param selectorValue
 *   what the selector asks for - the text after the {@code =}, or the single
 *   word. {@code null} when the path has no selector and empty when the
 *   brackets hold nothing
 * @param inner
 *   the dotted name after the selector, {@code null} when the path ends at the
 *   selector
 */
public record DocumentPath(
	String field,
	String selectorField,
	String selectorValue,
	String inner
) {
	private static final ErrorType MALFORMED = ErrorType
		.withCode("document:patch:path_invalid")
		.withStatus(400)
		.withArguments("path", "reason")
		.withMessage(PatchErrors.PATH_INVALID);

	/**
	 * Take a path apart.
	 *
	 * @param text
	 *   the path as it was written
	 * @throws ValidationException
	 *   if the text is not a path, or carries more than one selector. The
	 *   error is located at the text itself
	 */
	public static DocumentPath parse(String text) {
		var steps = PatchPath.parse(text, MALFORMED);

		var selectorAt = -1;
		for(var i = 0; i < steps.size(); i++) {
			if(!steps.get(i).hasSelector()) {
				continue;
			}

			if(selectorAt >= 0) {
				throw PatchErrors.failed(
					MALFORMED,
					text,
					"reason", "a path into a document names one value, so it carries one selector"
				);
			}

			selectorAt = i;
		}

		if(selectorAt < 0) {
			return new DocumentPath(join(steps, 0, steps.size()), null, null, null);
		}

		var selector = steps.get(selectorAt);

		return new DocumentPath(
			join(steps, 0, selectorAt + 1),
			selector.selectorField(),
			selector.selectorValue(),
			selectorAt + 1 < steps.size()
				? join(steps, selectorAt + 1, steps.size())
				: null
		);
	}

	/**
	 * Join the names of a range back into the dotted name the definition
	 * resolves.
	 */
	private static String join(
		ListIterable<PatchPath.Step> steps,
		int from,
		int to
	) {
		var name = new StringBuilder(steps.get(from).name());

		for(var i = from + 1; i < to; i++) {
			name.append('.').append(steps.get(i).name());
		}

		return name.toString();
	}

	/**
	 * Write this path back the way it was given, which is how a change points
	 * at itself in an error.
	 */
	@Override
	public String toString() {
		var text = new StringBuilder(field);

		if(selectorValue != null) {
			PatchPath.appendSelector(text, selectorField, selectorValue);
		}

		if(inner != null) {
			text.append('.').append(inner);
		}

		return text.toString();
	}
}
