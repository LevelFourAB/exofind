package se.l4.exofind.engine.index.analysis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.set.ImmutableSet;
import org.eclipse.collections.api.set.SetIterable;

import se.l4.exofind.engine.errors.ErrorMessage;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.IndexSchema;
import se.l4.exofind.engine.index.settings.QueryTypoExclusions;

/**
 * The words an index's search settings match as they are spelled, whatever
 * typo tolerance the fields they are searched in declare.
 *
 * <p>A definition declares typo tolerance per field. A word list narrows it
 * per word: a brand name or a model code sitting in a field whose other words
 * are worth forgiving mistakes in. A listed word is looked up as it was typed,
 * and every other word in the same search keeps the tolerance of its field.
 *
 * <p>The list is read against the words a search was typed with, so a word
 * that is not listed still reaches a listed one when a near reading of it
 * lands there.
 *
 * <p>Words are read through the analysis chain of the field, so they meet the
 * index as the words of a search do. A word the chain leaves nothing of, such
 * as a stopword, excludes nothing, and one it leaves several terms of excludes
 * each of them.
 *
 * <p>Compiled against one generation, and settings outlive generations: a list
 * naming a field the generation does not have covers the fields it does have,
 * and the names it lost are carried in {@link #skippedFields()}.
 *
 * <p>The words of a field are read as they are asked for and kept for the life
 * of the object, so it is thrown away with the settings it was compiled from.
 *
 * <p>Safe for concurrent use.
 */
public final class TypoExclusions {
	private static final ErrorType UNKNOWN_FIELD =
		ErrorType.withCode("index:settings:typo_exclusions:unknown_field")
			.withArguments("name", "field")
			.withMessage(
				"Typo exclusions `{{name}}` are applied to `{{field}}`, which the index does not have"
			);

	private static final ErrorType FIELD_NOT_TEXT =
		ErrorType.withCode("index:settings:typo_exclusions:field_not_text")
			.withArguments("name", "field")
			.withMessage(
				"Typo exclusions `{{name}}` are applied to `{{field}}`, which is not searched as text"
			);

	private static final TypoExclusions NONE = new TypoExclusions(
		Lists.immutable.empty(),
		Lists.immutable.empty()
	);

	private static final ImmutableSet<String> NO_WORDS = Sets.immutable.empty();

	/**
	 * One list as it applies to this generation.
	 *
	 * @param everyField
	 *   whether the list named no fields and so covers all of them, which is
	 *   not the same as one whose every name this generation lost
	 */
	private record Excluded(
		ImmutableList<String> words,
		boolean everyField,
		SetIterable<String> fields
	) {
		boolean covers(String field) {
			return everyField || fields.contains(field);
		}
	}

	/**
	 * What a read list is kept under: the analyzer the words are read through,
	 * and the field, which decides both the lists and how the analyzer reads
	 * them.
	 */
	private record Key(Analyzer analyzer, String field) {
	}

	private final ImmutableList<Excluded> lists;
	private final ListIterable<String> skippedFields;
	private final ConcurrentHashMap<Key, ImmutableSet<String>> words = new ConcurrentHashMap<>();

	private TypoExclusions(ImmutableList<Excluded> lists, ListIterable<String> skippedFields) {
		this.lists = lists;
		this.skippedFields = skippedFields;
	}

	/**
	 * Get the exclusions of an index whose settings hold none, which leaves
	 * every word to the typo tolerance of its field.
	 *
	 * @return
	 */
	public static TypoExclusions none() {
		return NONE;
	}

	/**
	 * Compile the word lists of an index's search settings against one
	 * generation.
	 *
	 * <p>A list naming a field the generation does not have keeps the fields it
	 * does have; one that is left with none is dropped. Nothing here fails: the
	 * settings belong to the index name and the generation under them can
	 * change, so a list that no longer fits excludes less rather than stopping
	 * the search.
	 *
	 * @param settings
	 *   the lists as stored, by name
	 * @param schema
	 *   the generation to read the field names against
	 * @return
	 */
	public static TypoExclusions compile(
		Map<String, QueryTypoExclusions> settings,
		IndexSchema schema
	) {
		if(settings.isEmpty()) {
			return NONE;
		}

		var lists = Lists.mutable.<Excluded>empty();
		var skipped = Sets.mutable.<String>empty();

		for(var stored : settings.values()) {
			if(stored.getWordsCount() == 0) {
				continue;
			}

			var everyField = stored.getFieldsCount() == 0;
			var fields = Sets.mutable.<String>empty();

			for(var field : stored.getFieldsList()) {
				if(isText(schema, field)) {
					fields.add(field);
				} else {
					skipped.add(field);
				}
			}

			if(!everyField && fields.isEmpty()) {
				continue;
			}

			lists.add(
				new Excluded(
					Lists.immutable.ofAll(stored.getWordsList()),
					everyField,
					fields.toImmutable()
				)
			);
		}

		if(lists.isEmpty() && skipped.isEmpty()) {
			return NONE;
		}

		return new TypoExclusions(lists.toImmutable(), skipped.toSortedList());
	}

	/**
	 * Check that the word lists of search settings run against a generation,
	 * for storing settings rather than for searching with them. What passes
	 * here can still be skipped by a generation promoted later - see
	 * {@link #compile}.
	 *
	 * @param settings
	 *   the lists as they would be stored, by name
	 * @param schema
	 *   the generation to check the field names against
	 * @param location
	 *   where the lists sit in what the caller is validating, for the errors to
	 *   point into it
	 * @return
	 *   what stops the lists, empty when this generation answers for all of
	 *   them
	 */
	public static ListIterable<ErrorMessage> validate(
		Map<String, QueryTypoExclusions> settings,
		IndexSchema schema,
		ObjectLocation location
	) {
		var errors = Lists.mutable.<ErrorMessage>empty();

		for(var entry : settings.entrySet()) {
			var name = entry.getKey();
			var at = location.forField(name);

			var fields = entry.getValue().getFieldsList();
			for(var i = 0; i < fields.size(); i++) {
				var field = fields.get(i);
				var index = at.forField("fields").forIndex(i);

				var found = schema.getField(field).or(
					() -> schema.getNestedField(field).map(IndexSchema.NestedField::field)
				);

				if(found.isEmpty()) {
					errors.add(UNKNOWN_FIELD.toMessage(index, "name", name, "field", field));
				} else if(!isText(found.get().getDef())) {
					errors.add(FIELD_NOT_TEXT.toMessage(index, "name", name, "field", field));
				}
			}
		}

		return errors;
	}

	/**
	 * Get whether every word is left to the typo tolerance of its field.
	 *
	 * @return
	 */
	public boolean isEmpty() {
		return lists.isEmpty();
	}

	/**
	 * Get the fields named by the lists that this generation cannot answer for,
	 * sorted. Empty when every list applies as it was written.
	 *
	 * @return
	 */
	public ListIterable<String> skippedFields() {
		return skippedFields;
	}

	/**
	 * Get the terms matched as they are spelled in one field, as the field
	 * writes them.
	 *
	 * @param analyzer
	 *   the analyzer the field's usage is read with, which the words are read
	 *   through as well. Pass the one the definition builds rather than one
	 *   widened by {@link SynonymOverlay}, or the synonyms of a listed word
	 *   would be excluded with it
	 * @param field
	 *   name of the field, as a search names it
	 * @return
	 *   empty when no list covers the field
	 */
	public ImmutableSet<String> termsIn(Analyzer analyzer, String field) {
		if(lists.isEmpty()) {
			return NO_WORDS;
		}

		var applied = lists.select(list -> list.covers(field));
		if(applied.isEmpty()) {
			return NO_WORDS;
		}

		return words.computeIfAbsent(new Key(analyzer, field), key -> read(key, applied));
	}

	/**
	 * Read the words of every list that covers one field, as the field writes
	 * them.
	 */
	private static ImmutableSet<String> read(Key key, ListIterable<Excluded> applied) {
		var terms = Sets.mutable.<String>empty();

		for(var list : applied) {
			for(var word : list.words()) {
				try(var stream = key.analyzer().tokenStream(key.field(), word)) {
					var term = stream.addAttribute(CharTermAttribute.class);

					stream.reset();
					while(stream.incrementToken()) {
						terms.add(term.toString());
					}
					stream.end();
				} catch(IOException e) {
					throw new UncheckedIOException(
						"Unable to read the terms of an excluded word",
						e
					);
				}
			}
		}

		return terms.toImmutable();
	}

	private static boolean isText(IndexSchema schema, String field) {
		return schema.getField(field)
			.or(() -> schema.getNestedField(field).map(IndexSchema.NestedField::field))
			.filter(found -> isText(found.getDef()))
			.isPresent();
	}

	private static boolean isText(FieldDef def) {
		if(!def.getType().hasString()) {
			return false;
		}

		var string = def.getType().getString();
		return string.hasMatching() || string.hasAutocomplete();
	}
}
