package se.l4.exofind.engine.index.analysis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.AnalyzerWrapper;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.synonym.SynonymGraphFilter;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.apache.lucene.search.BoostAttribute;
import org.apache.lucene.util.CharsRefBuilder;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.map.ImmutableMap;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.set.SetIterable;

import se.l4.exofind.engine.errors.ErrorMessage;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.IndexSchema;
import se.l4.exofind.engine.index.schema.ResourcesDef;
import se.l4.exofind.engine.index.settings.QuerySynonyms;

/**
 * The synonym sets of an index's search settings, ready to widen the text a
 * search asks for.
 *
 * <p>The sets a definition holds are applied as a value is indexed, so the
 * documents already written answer for the rules that were in force when they
 * were written. These are applied to the search instead: the words that were
 * typed are searched for as they were typed and as the rules read them, which
 * is what lets a rule take effect over a catalogue without indexing it again.
 * The two are not alternatives - a set on either side of the same rule counts
 * it twice, once in the document and once in the search.
 *
 * <p>The rules are applied after the analysis chain of the field, so the terms
 * of a rule are read through that chain as well and meet the index as the words
 * of a document do. A term the chain leaves nothing of - a stopword, or
 * punctuation - cannot be matched or added, and the rule holding it is left
 * out.
 *
 * <p>An overlay is compiled against one generation, and settings outlive
 * generations: a set naming a field the generation does not have is applied to
 * the fields it does have, and the names it lost are carried in
 * {@link #skippedFields()} so they can be reported rather than only dropped.
 *
 * <p>Analyzers are built as they are asked for and kept for the life of the
 * overlay, so an overlay is thrown away with the settings it was compiled from
 * rather than growing a cache that outlives them.
 *
 * <p>Safe for concurrent use.
 */
public final class SynonymOverlay {
	/**
	 * What a term the rules added counts when a set does not say. Below one, so
	 * a document holding the word that was typed ranks above one holding only a
	 * synonym of it.
	 */
	public static final float DEFAULT_BOOST = 0.8f;

	private static final ErrorType UNKNOWN_FIELD =
		ErrorType.withCode("index:settings:synonyms:unknown_field")
			.withArguments("name", "field")
			.withMessage(
				"Synonym set `{{name}}` is applied to `{{field}}`, which the index does not have"
			);

	private static final ErrorType FIELD_NOT_TEXT =
		ErrorType.withCode("index:settings:synonyms:field_not_text")
			.withArguments("name", "field")
			.withMessage(
				"Synonym set `{{name}}` is applied to `{{field}}`, which is not searched as text"
			);

	private static final ErrorType INVALID_BOOST =
		ErrorType.withCode("index:settings:synonyms:invalid_boost")
			.withArguments("name")
			.withMessage(
				"Synonym set `{{name}}` has a boost that is not a positive number"
			);

	private static final SynonymOverlay NONE = new SynonymOverlay(
		Lists.immutable.empty(),
		Lists.immutable.empty()
	);

	/**
	 * One set as it applies to this generation.
	 *
	 * @param everyField
	 *   whether the set named no fields and so applies to all of them, which is
	 *   not the same as one whose every name this generation lost
	 */
	private record Set(
		ResourcesDef.SynonymsResource rules,
		float boost,
		boolean everyField,
		SetIterable<String> fields
	) {
		boolean covers(String field) {
			return everyField || fields.contains(field);
		}
	}

	/**
	 * What an analyzer is kept under: the analyzer of the field as the
	 * definition builds it, which the rules are read through, and the field
	 * itself, which decides the sets.
	 */
	private record Key(Analyzer base, String field) {
	}

	private final ImmutableList<Set> sets;
	private final ListIterable<String> skippedFields;
	private final ConcurrentHashMap<Key, Analyzer> analyzers = new ConcurrentHashMap<>();

	private SynonymOverlay(ImmutableList<Set> sets, ListIterable<String> skippedFields) {
		this.sets = sets;
		this.skippedFields = skippedFields;
	}

	/**
	 * Get the overlay of an index whose settings hold no synonym sets, which
	 * leaves every analyzer as the definition built it.
	 *
	 * @return
	 */
	public static SynonymOverlay none() {
		return NONE;
	}

	/**
	 * Compile the synonym sets of an index's search settings against one
	 * generation.
	 *
	 * <p>A set naming a field the generation does not have keeps the fields it
	 * does have; one that is left with none is dropped. Nothing here fails: the
	 * settings belong to the index name and the generation under them can
	 * change, so a set that no longer fits widens less rather than stopping the
	 * search.
	 *
	 * @param settings
	 *   the sets as stored, by name
	 * @param schema
	 *   the generation to read the field names against
	 * @return
	 */
	public static SynonymOverlay compile(
		Map<String, QuerySynonyms> settings,
		IndexSchema schema
	) {
		if(settings.isEmpty()) {
			return NONE;
		}

		var sets = Lists.mutable.<Set>empty();
		var skipped = Sets.mutable.<String>empty();

		for(var entry : settings.entrySet()) {
			var stored = entry.getValue();
			if(stored.getSet().getRulesCount() == 0) {
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

			/*
			 * A boost of nothing is refused when settings are stored, so one
			 * here was written by something that skipped that check. Reading it
			 * as the default keeps searches answering.
			 */
			var boost = stored.hasBoost() && stored.getBoost() > 0
				&& Float.isFinite(stored.getBoost())
					? stored.getBoost()
					: DEFAULT_BOOST;

			sets.add(new Set(stored.getSet(), boost, everyField, fields.toImmutable()));
		}

		if(sets.isEmpty() && skipped.isEmpty()) {
			return NONE;
		}

		return new SynonymOverlay(sets.toImmutable(), skipped.toSortedList());
	}

	/**
	 * Check that the synonym sets of search settings run against a generation,
	 * for storing settings rather than for searching with them. What passes
	 * here can still be skipped by a generation promoted later - see
	 * {@link #compile}.
	 *
	 * @param settings
	 *   the sets as they would be stored, by name
	 * @param schema
	 *   the generation to check the field names against
	 * @param location
	 *   where the sets sit in what the caller is validating, for the errors to
	 *   point into it
	 * @return
	 *   what stops the sets, empty when this generation answers for all of them
	 */
	public static ListIterable<ErrorMessage> validate(
		Map<String, QuerySynonyms> settings,
		IndexSchema schema,
		ObjectLocation location
	) {
		var errors = Lists.mutable.<ErrorMessage>empty();

		for(var entry : settings.entrySet()) {
			var name = entry.getKey();
			var stored = entry.getValue();
			var at = location.forField(name);

			if(stored.hasBoost()
				&& (!(stored.getBoost() > 0) || !Float.isFinite(stored.getBoost()))) {
				errors.add(INVALID_BOOST.toMessage(at.forField("boost"), "name", name));
			}

			var fields = stored.getFieldsList();
			for(var i = 0; i < fields.size(); i++) {
				var field = fields.get(i);
				var index = at.forField("fields").forIndex(i);

				var found = schema.getField(field).or(
					() -> schema.getNestedField(field).map(IndexSchema.NestedField::field)
				);

				if(found.isEmpty()) {
					errors.add(
						UNKNOWN_FIELD.toMessage(index, "name", name, "field", field)
					);
				} else if(!isText(found.get().getDef())) {
					errors.add(
						FIELD_NOT_TEXT.toMessage(index, "name", name, "field", field)
					);
				}
			}
		}

		return errors;
	}

	/**
	 * Get whether the overlay leaves every field as the definition analyzes it.
	 *
	 * @return
	 */
	public boolean isEmpty() {
		return sets.isEmpty();
	}

	/**
	 * Get the fields named by the sets that this generation cannot answer for,
	 * sorted. Empty when every set applies as it was written.
	 *
	 * @return
	 */
	public ListIterable<String> skippedFields() {
		return skippedFields;
	}

	/**
	 * Get the analyzer to read the text of a search with, which is the one the
	 * definition builds widened by the sets that apply to the field.
	 *
	 * <p>The words the rules add come out of the analyzer carrying what they
	 * count, which is read through {@link BoostAttribute}. The words a value is
	 * taken whole as are left alone, so widening never reaches a whole-value
	 * match.
	 *
	 * @param base
	 *   the analyzer the field's usage is read with
	 * @param field
	 *   name of the field, as a search names it
	 * @return
	 *   {@code base} itself when no set applies to the field
	 */
	public Analyzer wrap(Analyzer base, String field) {
		if(sets.isEmpty()) {
			return base;
		}

		var applied = sets.select(set -> set.covers(field));
		if(applied.isEmpty()) {
			return base;
		}

		return analyzers.computeIfAbsent(new Key(base, field), key -> build(key, applied));
	}

	/**
	 * Build the analyzer for one field, reading the terms of every rule that
	 * applies to it through the field's own analyzer.
	 */
	private static Analyzer build(Key key, ListIterable<Set> applied) {
		var builder = new SynonymMap.Builder(true);
		var boosts = Maps.mutable.<String, Float>empty();
		var rules = 0;

		for(var set : applied) {
			for(var rule : set.rules().getRulesList()) {
				switch(rule.getRuleCase()) {
					case EQUIVALENT -> {
						var terms = rule.getEquivalent().getTermsList();
						for(var from : terms) {
							for(var to : terms) {
								if(!from.equals(to)
									&& add(builder, boosts, key, from, to, set.boost())) {
									rules++;
								}
							}
						}
					}
					case MAPPING -> {
						for(var from : rule.getMapping().getFromList()) {
							for(var to : rule.getMapping().getToList()) {
								if(add(builder, boosts, key, from, to, set.boost())) {
									rules++;
								}
							}
						}
					}
					case RULE_NOT_SET -> {
						/*
						 * A kind of rule this build has no code for, which the
						 * required features of the settings would have set the
						 * whole object aside for. Left out rather than refused,
						 * as failing here would fail searches.
						 */
					}
				}
			}
		}

		if(rules == 0) {
			// Every rule was of words the chain leaves nothing of
			return key.base();
		}

		SynonymMap map;
		try {
			map = builder.build();
		} catch(IOException e) {
			throw new UncheckedIOException("Unable to build synonym map", e);
		}

		return new OverlayAnalyzer(key.base(), map, boosts.toImmutable());
	}

	/**
	 * Add one direction of a rule, with both sides read through the analyzer of
	 * the field.
	 *
	 * @return
	 *   whether the rule was added, {@code false} when analysis left nothing of
	 *   one of its sides
	 */
	private static boolean add(
		SynonymMap.Builder builder,
		MutableMap<String, Float> boosts,
		Key key,
		String from,
		String to,
		float boost
	) {
		var in = words(key, from);
		var out = words(key, to);

		if(in.isEmpty() || out.isEmpty()) {
			return false;
		}

		var input = new CharsRefBuilder();
		SynonymMap.Builder.join(in.toArray(new String[0]), input);

		var output = new CharsRefBuilder();
		SynonymMap.Builder.join(out.toArray(new String[0]), output);

		builder.add(input.get(), output.get(), true);

		/*
		 * A word two sets both add is worth the less generous of them, so that
		 * adding a set can never raise what another set's word counts.
		 */
		for(var word : out) {
			boosts.merge(word, boost, Math::min);
		}

		return true;
	}

	/**
	 * Read the words of one side of a rule, as the field writes them.
	 */
	private static ImmutableList<String> words(Key key, String text) {
		var words = Lists.mutable.<String>empty();

		try(var stream = key.base().tokenStream(key.field(), text)) {
			var term = stream.addAttribute(CharTermAttribute.class);

			stream.reset();
			while(stream.incrementToken()) {
				words.add(term.toString());
			}
			stream.end();
		} catch(IOException e) {
			throw new UncheckedIOException("Unable to read the terms of a synonym rule", e);
		}

		return words.toImmutable();
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

	/**
	 * The analyzer of a field with the rules that apply to it added after it.
	 */
	private static final class OverlayAnalyzer extends AnalyzerWrapper {
		private final Analyzer base;
		private final SynonymMap map;
		private final ImmutableMap<String, Float> boosts;

		OverlayAnalyzer(Analyzer base, SynonymMap map, ImmutableMap<String, Float> boosts) {
			super(base.getReuseStrategy());

			this.base = base;
			this.map = map;
			this.boosts = boosts;
		}

		@Override
		protected Analyzer getWrappedAnalyzer(String fieldName) {
			return base;
		}

		@Override
		protected TokenStreamComponents wrapComponents(
			String fieldName,
			TokenStreamComponents components
		) {
			/*
			 * The graph is kept rather than flattened: what a rule of several
			 * words stands for is a reading of the text, and a query can ask
			 * for the readings one at a time where an index can only hold one.
			 */
			var widened = new SynonymGraphFilter(components.getTokenStream(), map, true);

			return new TokenStreamComponents(
				components.getSource(),
				new BoostingFilter(widened, boosts)
			);
		}
	}

	/**
	 * Marks the words the rules added with what they count, so that the query
	 * built from the text can tell them from the words that were typed.
	 */
	private static final class BoostingFilter extends TokenFilter {
		private final CharTermAttribute term = addAttribute(CharTermAttribute.class);
		private final TypeAttribute type = addAttribute(TypeAttribute.class);
		private final BoostAttribute boost = addAttribute(BoostAttribute.class);

		private final ImmutableMap<String, Float> boosts;

		BoostingFilter(TokenStream input, ImmutableMap<String, Float> boosts) {
			super(input);

			this.boosts = boosts;
		}

		@Override
		public boolean incrementToken() throws IOException {
			if(!input.incrementToken()) {
				return false;
			}

			Float added = SynonymGraphFilter.TYPE_SYNONYM.equals(type.type())
				? boosts.get(term.toString())
				: null;

			// Set either way, as the attribute is carried between tokens
			boost.setBoost(added == null ? 1f : added);

			return true;
		}
	}
}
