package se.l4.exofind.engine.index;

import java.util.Optional;
import java.util.regex.Pattern;

import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.errors.ValidationException;

/**
 * What a caller writes to name an index: either the index, which answers for
 * whichever generation is live, or one generation of it by name.
 *
 * <p>The two are told apart by an {@code @}, as in {@code books@2}. The
 * character is reserved: it can appear in no index name and no generation name,
 * so a full name is always taken apart the same way and the index a name
 * belongs to can be read off it without asking anything. That is what makes a
 * generation reachable only through the index it belongs to - a name can never
 * be pointed at a generation of another index, whatever is stored - and what
 * lets a grant of {@code books} reach the index while {@code books@*} reaches
 * its generations.
 *
 * @param index
 *   name of the index
 * @param generation
 *   name of the generation, or {@code null} for the live one
 */
public record IndexName(String index, String generation) {
	/**
	 * What separates an index from a generation of it.
	 */
	public static final char SEPARATOR = '@';

	/**
	 * Names of indexes and generations both become path segments, locally and
	 * in the remote, so they are kept to a conservative set of characters.
	 */
	public static final Pattern VALID_INDEX_PATTERN =
		Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

	/**
	 * Generations are named alongside the index they belong to, so their names
	 * are shorter than an index's - a full name has to stay something a person
	 * can read.
	 */
	public static final Pattern VALID_GENERATION_PATTERN =
		Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");

	private static final ErrorType INVALID_INDEX = ErrorType.withCode("index:invalid_name")
		.withArguments("name")
		.withMessage(
			"Index name `{{name}}` should start with a letter or number and only contain"
				+ " lowercase letters, numbers, underscores and dashes"
		);

	private static final ErrorType INVALID_GENERATION =
		ErrorType.withCode("index:invalid_generation_name")
			.withArguments("name")
			.withMessage(
				"Generation name `{{name}}` should start with a letter or number and only"
					+ " contain lowercase letters, numbers, underscores and dashes"
			);

	public IndexName {
		if(index == null || !VALID_INDEX_PATTERN.matcher(index).matches()) {
			throw new ValidationException(
				INVALID_INDEX.toMessage(ObjectLocation.root(), "name", index)
			);
		}

		if(generation != null && !VALID_GENERATION_PATTERN.matcher(generation).matches()) {
			throw new ValidationException(
				INVALID_GENERATION.toMessage(ObjectLocation.root(), "name", generation)
			);
		}
	}

	/**
	 * Name the index itself, which answers for whichever generation is live.
	 *
	 * @param index
	 * @return
	 * @throws ValidationException
	 *   if the name is not one an index can have
	 */
	public static IndexName of(String index) {
		return new IndexName(index, null);
	}

	/**
	 * Name one generation of an index.
	 *
	 * @param index
	 * @param generation
	 * @return
	 * @throws ValidationException
	 *   if either name is not one that can be used
	 */
	public static IndexName of(String index, String generation) {
		return new IndexName(index, generation);
	}

	/**
	 * Take apart a name as a caller wrote it.
	 *
	 * @param name
	 * @return
	 * @throws ValidationException
	 *   if the name is not one that can be used, which includes naming more
	 *   than one generation
	 */
	public static IndexName parse(String name) {
		if(name == null) {
			throw new ValidationException(
				INVALID_INDEX.toMessage(ObjectLocation.root(), "name", name)
			);
		}

		var separator = name.indexOf(SEPARATOR);
		if(separator < 0) {
			return new IndexName(name, null);
		}

		/*
		 * A second separator is refused rather than read as part of either
		 * name, so that a full name always takes apart into the same two
		 * pieces however it is written.
		 */
		var generation = name.substring(separator + 1);
		if(generation.indexOf(SEPARATOR) >= 0) {
			throw new ValidationException(
				INVALID_GENERATION.toMessage(ObjectLocation.root(), "name", generation)
			);
		}

		return new IndexName(name.substring(0, separator), generation);
	}

	/**
	 * Take apart a name that came from somewhere other than a caller - a
	 * directory left on disk, an entry read out of storage - where anything
	 * unusable is something to pass over rather than an error to answer with.
	 *
	 * @param name
	 * @return
	 *   empty when the name is not one that could have been created here
	 */
	public static Optional<IndexName> tryParse(String name) {
		try {
			return Optional.of(parse(name));
		} catch(ValidationException e) {
			return Optional.empty();
		}
	}

	/**
	 * Whether this names one generation rather than whichever is live.
	 */
	public boolean isPinned() {
		return generation != null;
	}

	/**
	 * This name with a generation of its index, for turning what a caller asked
	 * for into what actually answers.
	 *
	 * @param generation
	 * @return
	 */
	public IndexName withGeneration(String generation) {
		return new IndexName(index, generation);
	}

	/**
	 * The index this name belongs to, without any generation.
	 */
	public IndexName withoutGeneration() {
		return generation == null ? this : new IndexName(index, null);
	}

	@Override
	public String toString() {
		return generation == null ? index : index + SEPARATOR + generation;
	}
}
