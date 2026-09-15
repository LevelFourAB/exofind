package se.l4.exofind.engine.patch;

import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.errors.ValidationException;

/**
 * The status, the arguments, and the wording every code of a patch family
 * carries.
 *
 * <p>An endpoint that changes part of something answers in its own family:
 * {@code document:patch:*} for a change to a document and
 * {@code settings:patch:*} for a change to search settings. The two families
 * hold the same last segments, answer with the same status, take the same
 * arguments, and read the same. Only the prefix tells them apart, so a client
 * that handles one handles the other by swapping the prefix.
 *
 * <p>Each family declares its own {@link ErrorType} constants beside the code
 * that throws them, because the tests that build the API document read the
 * codes out of the source. This class holds what those declarations share.
 * {@code PatchErrorFamilyTest} holds the two families to each other.
 *
 * <p>Two codes sit outside the family because only one side can answer with
 * them: {@code settings:patch:value_invalid} reports a value the settings
 * model cannot hold, where a document reports the same thing per field type as
 * {@code document:number:value_invalid} and its siblings.
 */
public final class PatchErrors {
	/*
	 * Every code of a family answers with 400, and each declaration writes
	 * that status out as a literal. ErrorCodeFilterTest reads the code and the
	 * status out of the source text, so a constant in their place leaves the
	 * code without a stated status.
	 */

	/** Text that does not parse as a path. Arguments: {@code path}, {@code reason}. */
	public static final String PATH_INVALID =
		"`{{path}}` does not name a place to change: {{reason}}";

	/** A path reaching a name that is not declared. Arguments: {@code path}, {@code field}. */
	public static final String FIELD_UNKNOWN =
		"`{{path}}` reaches into `{{field}}`, which is not declared";

	/** A path reaching inside a value holding no fields. Arguments: {@code path}, {@code field}. */
	public static final String NOT_AN_OBJECT =
		"`{{path}}` reaches inside `{{field}}`, which holds no fields";

	/**
	 * A path reaching into a list without saying which value. Arguments:
	 * {@code path}, {@code field}, {@code how}, where {@code how} spells the
	 * selector to write.
	 */
	public static final String SELECTOR_REQUIRED =
		"`{{field}}` holds a list of values, so `{{path}}` has to say which one, as {{how}}";

	/** A selector on a place holding no list. Arguments: {@code path}, {@code field}. */
	public static final String SELECTOR_UNSUPPORTED =
		"`{{field}}` holds no list of values, so `{{path}}` cannot name one of them";

	/** A {@code field=value} selector on values that are not objects. Arguments: {@code path}, {@code field}. */
	public static final String MATCH_NOT_AN_OBJECT =
		"`{{path}}` matches on a field inside `{{field}}`, whose values are not objects";

	/** A single word where no key is declared. Arguments: {@code path}, {@code field}. */
	public static final String KEY_UNSUPPORTED =
		"`{{path}}` names a value of `{{field}}` by a key, which `{{field}}` declares none of "
		+ "- name a field inside the value instead, as `{{field}}[field=value]`";

	/** Empty brackets on a place holding a single value. Arguments: {@code path}, {@code field}. */
	public static final String ADD_UNSUPPORTED =
		"`{{path}}` adds a value to `{{field}}`, which holds a single value "
		+ "- name it on its own to replace it";

	/** A name after empty brackets. Arguments: {@code path}. */
	public static final String ADD_REACHES_INSIDE =
		"`{{path}}` reaches inside a value that is being added, which does not exist yet "
		+ "- give the whole value instead";

	/** A selector matching nothing that is stored. Arguments: {@code path}. */
	public static final String NO_MATCH =
		"`{{path}}` names no value that is stored";

	private PatchErrors() {
	}

	/**
	 * Report a path that a change cannot use.
	 *
	 * <p>The path becomes the {@code path} argument of the message and the
	 * location of the error, so the client reads back the key it sent.
	 *
	 * @param type
	 *   the code to report, from the family the caller answers in
	 * @param path
	 *   the path as it was written
	 * @param arguments
	 *   the remaining arguments of the message, as name and value in turn
	 * @return
	 *   the exception to throw
	 */
	public static ValidationException failed(
		ErrorType type,
		String path,
		Object... arguments
	) {
		var all = new Object[arguments.length + 2];
		all[0] = "path";
		all[1] = path;
		System.arraycopy(arguments, 0, all, 2, arguments.length);

		return new ValidationException(
			type.toMessage(ObjectLocation.root().forField(path), all)
		);
	}
}
