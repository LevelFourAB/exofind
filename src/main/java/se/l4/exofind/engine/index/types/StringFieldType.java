package se.l4.exofind.engine.index.types;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.spans.SpanMultiTermQueryWrapper;
import org.apache.lucene.queries.spans.SpanNearQuery;
import org.apache.lucene.queries.spans.SpanOrQuery;
import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.queries.spans.SpanTermQuery;
import org.apache.lucene.search.AutomatonQuery;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.DisjunctionMaxQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.MultiPhraseQuery;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TermRangeQuery;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.automaton.Automata;
import org.apache.lucene.util.automaton.Automaton;
import org.apache.lucene.util.automaton.LevenshteinAutomata;
import org.apache.lucene.util.automaton.Operations;
import org.eclipse.collections.api.collection.MutableCollection;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.impl.factory.Lists;

import se.l4.exofind.engine.errors.ErrorMessage;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.index.AnalyzingTextField;
import se.l4.exofind.engine.index.FacetCounter;
import se.l4.exofind.engine.index.FieldNames;
import se.l4.exofind.engine.index.HierarchyFacetCounter;
import se.l4.exofind.engine.index.IndexEncounter;
import se.l4.exofind.engine.index.IndexException;
import se.l4.exofind.engine.index.IndexFieldUsageException;
import se.l4.exofind.engine.index.IndexInvalidQueryTypeException;
import se.l4.exofind.engine.index.IndexInvalidQueryValueException;
import se.l4.exofind.engine.index.analysis.AnalyzerChains;
import se.l4.exofind.engine.index.analysis.AnalyzerMode;
import se.l4.exofind.engine.index.analysis.Analyzers;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.ResourcesDef;
import se.l4.exofind.engine.index.schema.SortConfig;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.query.matchers.AnyMatcher;
import se.l4.exofind.engine.query.matchers.EqualsMatcher;
import se.l4.exofind.engine.query.matchers.InMatcher;
import se.l4.exofind.engine.query.matchers.Matcher;
import se.l4.exofind.engine.query.matchers.PrefixMatcher;
import se.l4.exofind.engine.query.matchers.RangeMatcher;
import se.l4.exofind.engine.query.matchers.TextMatcher;
import se.l4.exofind.engine.query.matchers.UnderMatcher;

public class StringFieldType implements FieldType {
	private static final ErrorType ANALYSIS_FAILED = ErrorType
		.withCode("index:query:analysis_failed")
		.withArguments("name")
		.withMessage("The text searched for in field `{{name}}` could not be analyzed");

	/**
	 * How long a word has to be before one typo is allowed in it, unless the
	 * definition says otherwise. Short words are mostly typos of each other,
	 * so allowing mistakes in them finds more noise than intent.
	 */
	private static final int DEFAULT_MIN_LENGTH_ONE_TYPO = 5;

	/**
	 * How long a word has to be before two typos are allowed in it, unless the
	 * definition says otherwise.
	 */
	private static final int DEFAULT_MIN_LENGTH_TWO_TYPOS = 9;

	/**
	 * The most typos a word may carry, whatever it is long enough for. Every
	 * further edit multiplies the terms a word is near, so two is as many as
	 * matching can afford.
	 */
	private static final int MAX_EDITS = 2;

	/**
	 * The most typos a word still being typed may carry, unless the definition
	 * names a length for two.
	 *
	 * An autocomplete field holds every prefix of a word as a term of its own,
	 * so a word long enough for two typos is looked for among terms of four
	 * lengths rather than one, and the walk of them costs several times what
	 * one typo costs - on every long word, including the ones typed correctly.
	 * What it buys is small: a word that is long enough is one the person has
	 * nearly finished typing, and two mistakes surviving that far is rare. A
	 * definition that wants them anyway says so by naming the length.
	 */
	private static final int MAX_EDITS_WHILE_TYPING = 1;

	/**
	 * How many leading characters have to match exactly, unless the definition
	 * says otherwise. People rarely get the first letter wrong, and anchoring
	 * it keeps the walk of nearby terms short.
	 */
	private static final int DEFAULT_PREFIX_LENGTH = 1;

	/**
	 * How the terms a half typed or misspelled word stands for are run.
	 *
	 * Both of the rewrites that could serve here score every term the word
	 * matched the same, so this decides nothing about ranking - only whether
	 * the terms arrive as one set of documents or as a scorer per term with a
	 * set for whatever is left over. Lucene's default is the latter, which is
	 * the better trade for a word that stands for a handful of terms; a word
	 * cut short or read with mistakes stands for a large part of the
	 * vocabulary, and each scorer is asked for the best score it could still
	 * reach once per block of documents the search skips over, whether or not
	 * it matched anything there.
	 */
	private static final MultiTermQuery.RewriteMethod EXPANSION_REWRITE =
		MultiTermQuery.CONSTANT_SCORE_REWRITE;

	/**
	 * How many built typo tolerant queries are kept. The words are text
	 * somebody typed, so what is kept has to have a ceiling; this one holds
	 * what a search box being typed into produces - a word per keystroke, per
	 * field it covers - for a good number of people at once, while the
	 * automata behind them stay a few megabytes rather than a share of the
	 * heap.
	 */
	private static final int FUZZY_CACHE_SIZE = 512;

	/**
	 * The typo tolerant queries already built, by the field they ask of, the
	 * word they forgive mistakes in, how many are forgiven and whether fewer
	 * are kept out, how much of the word has to be right and whether the rest
	 * of it is still being typed.
	 *
	 * Building one turns every reading of the word within those mistakes into
	 * a table the term dictionary is walked against, which is worth more than
	 * the walk itself: a search covering several fields builds the same word
	 * once per field, a search that found nothing builds it again for every
	 * word it weighs before letting one go, and the next person to type the
	 * word builds it again after that. What is built depends on the word and
	 * on nothing of the index, so it is as good later as it was when it was
	 * built.
	 *
	 * The least recently asked for goes when the cache is full. Held through
	 * {@link Collections#synchronizedMap} rather than a concurrent map because
	 * that order is what has to be kept, and the lock is held only for the
	 * lookup - see {@link #withinEdits}.
	 */
	private static final Map<FuzzyKey, Query> FUZZY_QUERIES = Collections.synchronizedMap(
		new LinkedHashMap<FuzzyKey, Query>(FUZZY_CACHE_SIZE, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<FuzzyKey, Query> eldest) {
				return size() > FUZZY_CACHE_SIZE;
			}
		}
	);

	/**
	 * What a built typo tolerant query is decided by, and so what one is kept
	 * under.
	 */
	private record FuzzyKey(
		String field,
		String text,
		int edits,
		boolean exactly,
		int prefixLength,
		boolean prefix
	) {
	}

	/**
	 * How much a whole-value match adds when the definition names no amount.
	 *
	 * On the scale of what a hit in the field already counts, so it is a
	 * multiple rather than an absolute - enough that a document named what was
	 * typed comes ahead of one that only mentions it, small enough that a
	 * badly matched name does not come ahead of a well matched description.
	 */
	private static final float DEFAULT_EXACT_BOOST = 2f;

	private static final ErrorType INVALID_MATCHING_WEIGHT = ErrorType
		.withCode("index:field:matching:invalid_weight")
		.withMessage("The weight of matching has to be greater than zero");

	private static final ErrorType INVALID_AUTOCOMPLETE_WEIGHT = ErrorType
		.withCode("index:field:autocomplete:invalid_weight")
		.withMessage("The weight of autocomplete has to be greater than zero");

	private static final ErrorType INVALID_TYPO_MIN_LENGTH = ErrorType
		.withCode("index:field:matching:invalid_typo_min_length")
		.withMessage(
			"The shortest word that may contain a typo has to be at least one character"
		);

	private static final ErrorType INVALID_TYPO_ORDER = ErrorType
		.withCode("index:field:matching:invalid_typo_order")
		.withMessage(
			"A word can not be long enough for two typos before it is long enough for one"
		);

	private static final ErrorType INVALID_EXACT_BOOST = ErrorType
		.withCode("index:field:exact:invalid_boost")
		.withMessage("The boost of a whole-value match has to be greater than zero");

	/**
	 * What separates one level of a path from the next when the definition
	 * names nothing else - the separator a path is usually written with.
	 */
	private static final String DEFAULT_SEPARATOR = "/";

	private static final ErrorType INVALID_SEPARATOR = ErrorType
		.withCode("index:field:hierarchy:invalid_separator")
		.withMessage(
			"The separator between the levels of a path can not be empty - leave it out for `/`"
		);

	private static final ErrorType INVALID_TYPO_PREFIX = ErrorType
		.withCode("index:field:matching:invalid_typo_prefix")
		.withMessage("The exactly matched prefix of typo tolerance can not be negative");

	private static final ErrorType UNKNOWN_ANALYZER_REF = ErrorType
		.withCode("index:field:analyzer:unknown_ref")
		.withArguments("resource")
		.withMessage(
			"The usage names analysis chain `{{resource}}` which the resources of the index do not define"
		);

	private static final ErrorType AMBIGUOUS_ANALYZER = ErrorType
		.withCode("index:field:analyzer:ambiguous")
		.withMessage(
			"The usage carries an analysis chain and names one in the resources - at most one of the two can be given"
		);

	@Override
	public boolean isSortingSupported() {
		return true;
	}

	@Override
	public boolean isDocValuesSupported() {
		return true;
	}

	@Override
	public boolean isPrimaryKeySupported() {
		return true;
	}

	@Override
	public boolean isTextSearchable(IndexEncounter encounter) {
		var stringType = encounter.getFieldType().getString();
		return stringType.hasMatching() || stringType.hasAutocomplete();
	}

	@Override
	public boolean isPhraseSearchable(IndexEncounter encounter) {
		return encounter.getFieldType().getString().hasMatching();
	}

	@Override
	public ListIterable<ErrorMessage> validate(
		ObjectLocation location,
		FieldDef def,
		ResourcesDef resources
	) {
		var errors = Lists.mutable.<ErrorMessage>empty();
		var stringType = def.getType().getString();

		if(stringType.hasMatching()) {
			var matching = stringType.getMatching();

			// The negated form also refuses NaN, which no comparison lets through
			if(matching.hasWeight() && !(matching.getWeight() > 0)) {
				errors.add(INVALID_MATCHING_WEIGHT.toMessage(location));
			}

			validateTypoTolerance(location, errors, matching);
			validateExact(location, errors, matching);
			validateAnalyzerChoice(location, errors, matching, resources);
		}

		if(stringType.hasAutocomplete()) {
			var autocomplete = stringType.getAutocomplete();

			if(autocomplete.hasWeight() && !(autocomplete.getWeight() > 0)) {
				errors.add(INVALID_AUTOCOMPLETE_WEIGHT.toMessage(location));
			}

			validateTypoTolerance(location, errors, autocomplete);
			validateExact(location, errors, autocomplete);
			validateAnalyzerChoice(location, errors, autocomplete, resources);
		}

		if(stringType.hasHierarchy()) {
			var hierarchy = stringType.getHierarchy();

			/*
			 * Without a separator every value would be one level of its own,
			 * which is what the field does anyway when it holds no paths.
			 */
			if(hierarchy.hasSeparator() && hierarchy.getSeparator().isEmpty()) {
				errors.add(INVALID_SEPARATOR.toMessage(location));
			}
		}

		return errors;
	}

	/**
	 * Check the typing mistakes a usage forgives: how long a word has to be
	 * before it may carry one, before it may carry two, and how much of it is
	 * held exact. The thresholds mean the same thing in both usages - the word
	 * they measure is the one that was typed - so they are checked in one
	 * place.
	 */
	private static void validateTypoTolerance(
		ObjectLocation location,
		MutableCollection<ErrorMessage> errors,
		StringFieldTypeDef.TextUsageConfig usage
	) {
		if(!usage.hasTypoTolerance()) {
			return;
		}

		var typos = usage.getTypoTolerance();

		if(typos.hasMinLengthOneTypo() && typos.getMinLengthOneTypo() < 1) {
			errors.add(INVALID_TYPO_MIN_LENGTH.toMessage(location));
		}

		if(typos.hasPrefixLength() && typos.getPrefixLength() < 0) {
			errors.add(INVALID_TYPO_PREFIX.toMessage(location));
		}

		var one = typos.hasMinLengthOneTypo()
			? typos.getMinLengthOneTypo()
			: DEFAULT_MIN_LENGTH_ONE_TYPO;
		var two = typos.hasMinLengthTwoTypos()
			? typos.getMinLengthTwoTypos()
			: DEFAULT_MIN_LENGTH_TWO_TYPOS;
		if(two < one) {
			errors.add(INVALID_TYPO_ORDER.toMessage(location));
		}
	}

	/**
	 * Check what a whole-value match counts. The boost is a multiple of what a
	 * hit in the field already counts, so zero would be a usage asking to be
	 * ranked by nothing rather than one asking for less.
	 */
	private static void validateExact(
		ObjectLocation location,
		MutableCollection<ErrorMessage> errors,
		StringFieldTypeDef.TextUsageConfig usage
	) {
		if(!usage.hasExact()) {
			return;
		}

		var exact = usage.getExact();

		// The negated form also refuses NaN, which no comparison lets through
		if(exact.hasBoost() && !(exact.getBoost() > 0)) {
			errors.add(INVALID_EXACT_BOOST.toMessage(location));
		}
	}

	/**
	 * Check how a usage analyzes: an inline chain is checked as a chain, a
	 * named one has to exist among the resources, and carrying both would
	 * leave it unclear which one the usage means.
	 */
	private static void validateAnalyzerChoice(
		ObjectLocation location,
		MutableCollection<ErrorMessage> errors,
		StringFieldTypeDef.TextUsageConfig usage,
		ResourcesDef resources
	) {
		if(usage.hasAnalyzer() && usage.hasAnalyzerRef()) {
			errors.add(AMBIGUOUS_ANALYZER.toMessage(location));
		}

		if(usage.hasAnalyzer()) {
			AnalyzerChains.validate(location, errors, usage.getAnalyzer(), resources);
		}

		if(usage.hasAnalyzerRef()
			&& !resources.getAnalyzersMap().containsKey(usage.getAnalyzerRef())) {
			errors.add(
				UNKNOWN_ANALYZER_REF.toMessage(location, "resource", usage.getAnalyzerRef())
			);
		}
	}

	/**
	 * Get the weight the definition gives this field, read from the same usage
	 * a text search of it goes to - matching when the field has it, otherwise
	 * autocomplete.
	 */
	@Override
	public float getTextWeight(IndexEncounter encounter) {
		var stringType = encounter.getFieldType().getString();

		if(stringType.hasMatching()) {
			var matching = stringType.getMatching();
			return matching.hasWeight() ? matching.getWeight() : 1f;
		}

		if(stringType.hasAutocomplete()) {
			var autocomplete = stringType.getAutocomplete();
			return autocomplete.hasWeight() ? autocomplete.getWeight() : 1f;
		}

		return 1f;
	}

	@Override
	public Iterable<? extends IndexableField> createFields(
		IndexEncounter encounter,
		Object value0
	) {
		var results = Lists.mutable.<IndexableField>empty();

		var type = encounter.getFieldType();
		var stringType = type.getString();
		var needsStorage = encounter.isStored();

		var value = (String) value0;

		if(encounter.isPrimaryKey()) {
			var field = new StringField(
				encounter.name(FieldNames.PRIMARY_KEY),
				value,
				Field.Store.NO
			);
			results.add(field);
		}

		if(encounter.isFiltered()) {
			var field = new StringField(
				encounter.name(FieldNames.FILTER),
				filterValue(encounter, value),
				Field.Store.NO
			);
			results.add(field);
		}

		if(stringType.hasMatching()) {
			var matchConfig = stringType.getMatching();
			var analyzer = Analyzers.matching(
				matchConfig,
				encounter.getResources(),
				encounter.getLocaleSupport(),
				AnalyzerMode.INDEXING
			);
			if(matchConfig.hasHighlight()) {
				needsStorage = true;
			}

			var field = new AnalyzingTextField(
				encounter.name(FieldNames.MATCHING),
				value,
				matchConfig.hasHighlight(),
				analyzer
			);
			results.add(field);

			if(matchConfig.hasExact()) {
				results.add(exactField(encounter, analyzer, FieldNames.MATCHING_EXACT, value));
			}
		}

		if(stringType.hasAutocomplete()) {
			var autocompleteConfig = stringType.getAutocomplete();
			var analyzer = Analyzers.autocomplete(
				autocompleteConfig,
				encounter.getResources(),
				encounter.getLocaleSupport(),
				AnalyzerMode.INDEXING
			);
			if(autocompleteConfig.hasHighlight()) {
				needsStorage = true;
			}

			var field = new AnalyzingTextField(
				encounter.name(FieldNames.AUTOCOMPLETE),
				value,
				autocompleteConfig.hasHighlight(),
				analyzer
			);
			results.add(field);

			if(autocompleteConfig.hasExact()) {
				results.add(exactField(encounter, analyzer, FieldNames.AUTOCOMPLETE_EXACT, value));
			}
		}

		if(needsStorage) {
			var field = new StoredField(
				encounter.name(FieldNames.STORED),
				value
			);
			results.add(field);
		}

		/*
		 * Facet counts are shown to a user, so they count the value as it was
		 * given rather than the normalized one that filtering uses.
		 *
		 * A field holding paths is counted a level at a time and never as
		 * whole values, and its deepest level is the whole value, so these
		 * would be a second copy of what the levels below already hold.
		 */
		if(encounter.isStoreDocValues() && !stringType.hasHierarchy()) {
			var field = new SortedSetDocValuesField(
				encounter.name(FieldNames.VALUES),
				new BytesRef(value)
			);
			results.add(field);
		}

		/*
		 * A path is written once per level it passes through, which is what
		 * lets a level be narrowed to and counted without reading every value
		 * that runs through it. The terms are normalized the way filtering
		 * normalizes a value, the doc values hold the level as it was given -
		 * the same split FILTER and VALUES make for the value as a whole.
		 */
		if(stringType.hasHierarchy()) {
			var name = encounter.name(FieldNames.HIERARCHY);
			for(var level : levels(value, separator(stringType))) {
				results.add(new StringField(name, filterValue(encounter, level), Field.Store.NO));

				if(encounter.isStoreDocValues()) {
					results.add(new SortedSetDocValuesField(name, new BytesRef(level)));
				}
			}
		}

		// Sorting requires its own DocValues field
		if(encounter.isSorted()) {
			var field = new SortedDocValuesField(
				encounter.name(FieldNames.SORT),
				sortValue(encounter, value)
			);
			results.add(field);
		}

		return results;
	}

	/**
	 * Build the field holding the value as one term, which is what tells a
	 * value a search matched whole from one that merely holds the same words.
	 *
	 * Written through the usage's own analyzer, which normalizes a whole value
	 * the same way it normalizes one of its words - so the term is the value
	 * as the field reads it, and the text of a search normalized the same way
	 * meets it. Indexed without positions or norms, as nothing is looked for
	 * inside a term that is the whole value.
	 *
	 * @param encounter
	 * @param analyzer
	 *   the analyzer of the usage the term belongs to
	 * @param suffix
	 *   what the term is written under, the exact name of that usage
	 * @param value
	 * @return
	 */
	private static IndexableField exactField(
		IndexEncounter encounter,
		Analyzer analyzer,
		String suffix,
		String value
	) {
		var name = encounter.name(suffix);
		return new StringField(name, analyzer.normalize(name, value), Field.Store.NO);
	}

	/**
	 * Get the value as it is written to, and looked up in, the field used for
	 * filtering. Case is folded away unless the keyword config of the type
	 * keeps it, so that filtering on {@code Fiction} also finds
	 * {@code fiction}.
	 *
	 * @param encounter
	 * @param value
	 * @return
	 */
	private static String filterValue(IndexEncounter encounter, String value) {
		var keyword = encounter.getFieldType().getString().getKeyword();
		if(keyword.hasCaseFolding() && !keyword.getCaseFolding()) {
			return value;
		}

		return value.toLowerCase(encounter.getLocale().orElse(Locale.ROOT));
	}

	/**
	 * Get the bytes that order this value. Ordering by the bytes of the value
	 * itself only reads correctly for plain ASCII, so by default the value is
	 * turned into a collation key for the locale first.
	 *
	 * @param encounter
	 * @param value
	 * @return
	 */
	private static BytesRef sortValue(IndexEncounter encounter, String value) {
		if(encounter.getSortConfig().getCollation() == SortConfig.Collation.COLLATION_BINARY) {
			return new BytesRef(value);
		}

		return encounter.getLocaleSupport().getCollationKey(value);
	}

	@Override
	public Query createQuery(IndexEncounter encounter, Matcher matcher) {
		/*
		 * Text is the one matcher that reads the value the way it was written
		 * rather than as a whole, so it is the one that goes to a different
		 * field than the rest.
		 */
		if(matcher instanceof TextMatcher m) {
			return createTextQuery(encounter, m);
		}

		if(matcher instanceof EqualsMatcher m) {
			return new TermQuery(
				new Term(
					filterName(encounter),
					filterValue(encounter, stringValue(encounter, m.value()))
				)
			);
		}

		if(matcher instanceof InMatcher m) {
			return new TermInSetQuery(
				filterName(encounter),
				m.values()
					.collect(v -> new BytesRef(filterValue(encounter, stringValue(encounter, v))))
					.toList()
			);
		}

		if(matcher instanceof PrefixMatcher m) {
			return new PrefixQuery(
				new Term(filterName(encounter), filterValue(encounter, m.value()))
			);
		}

		if(matcher instanceof UnderMatcher m) {
			/*
			 * The levels of a path stand in the index as terms of their own,
			 * so everything filed below a level is what holds that level - one
			 * term lookup however deep the subtree reaches.
			 */
			return new TermQuery(
				new Term(hierarchyName(encounter), filterValue(encounter, m.path()))
			);
		}

		if(matcher instanceof RangeMatcher m) {
			/*
			 * Bounded by the bytes of the value as it is filtered on, not by
			 * the collation ordering results uses - a range is between two
			 * values a caller wrote, and it holds whatever falls between them
			 * however the locale would have sorted it.
			 */
			return new TermRangeQuery(
				filterName(encounter),
				bound(encounter, m.lower()),
				bound(encounter, m.upper()),
				m.lowerInclusive(),
				m.upperInclusive()
			);
		}

		if(matcher instanceof AnyMatcher) {
			/*
			 * Every term in the field, which is every document that has a
			 * value for it. Costs a walk of the terms of the field, so it is
			 * meant for narrowing rather than for listing an index.
			 */
			return new TermRangeQuery(filterName(encounter), null, null, true, true);
		}

		throw new IndexInvalidQueryTypeException("string", matcher.id());
	}

	@Override
	public FacetCounter createFacetCounter(IndexEncounter encounter) {
		// The doc values hold the value as it was given, so it is the count key
		return FacetCounter.overStrings(encounter.name(FieldNames.VALUES), value -> value);
	}

	@Override
	public boolean isHierarchical(IndexEncounter encounter) {
		return encounter.getFieldType().getString().hasHierarchy();
	}

	@Override
	public HierarchyFacetCounter createHierarchyFacetCounter(IndexEncounter encounter) {
		var stringType = encounter.getFieldType().getString();
		if(!stringType.hasHierarchy()) {
			throw new IndexFieldUsageException(encounter.getFieldName(), "hierarchy");
		}

		return HierarchyFacetCounter.overPaths(
			encounter.name(FieldNames.HIERARCHY),
			separator(stringType),
			value -> filterValue(encounter, value)
		);
	}

	/**
	 * Get the name the levels of a path are written under, refusing a field
	 * whose values were never read as paths.
	 *
	 * @param encounter
	 * @return
	 */
	private static String hierarchyName(IndexEncounter encounter) {
		if(!encounter.getFieldType().getString().hasHierarchy()) {
			throw new IndexFieldUsageException(encounter.getFieldName(), "hierarchy");
		}

		return encounter.name(FieldNames.HIERARCHY);
	}

	/**
	 * Get what separates one level of a path from the next.
	 *
	 * @param stringType
	 * @return
	 */
	private static String separator(StringFieldTypeDef stringType) {
		var hierarchy = stringType.getHierarchy();
		return hierarchy.hasSeparator() ? hierarchy.getSeparator() : DEFAULT_SEPARATOR;
	}

	/**
	 * Take a path apart into every level it passes through, each of them the
	 * whole way down to itself - {@code Men/Shoes/Running} is {@code Men},
	 * {@code Men/Shoes} and the value itself.
	 *
	 * A separator with nothing between it and the next is no level, so a value
	 * written with a leading, trailing or doubled separator holds the same
	 * levels as one written without.
	 *
	 * @param value
	 * @param separator
	 * @return
	 */
	private static ImmutableList<String> levels(String value, String separator) {
		var levels = Lists.mutable.<String>empty();
		var path = new StringBuilder();

		var at = 0;
		while(at <= value.length()) {
			var next = value.indexOf(separator, at);
			var end = next < 0 ? value.length() : next;

			if(end > at) {
				if(path.length() > 0) {
					path.append(separator);
				}

				path.append(value, at, end);
				levels.add(path.toString());
			}

			if(next < 0) {
				break;
			}

			at = next + separator.length();
		}

		return levels.toImmutable();
	}

	@Override
	public String getHighlightFieldName(IndexEncounter encounter) {
		var stringType = encounter.getFieldType().getString();

		/*
		 * The same choice textTermQueries makes: a field that can match is
		 * searched on matching, and only a field that cannot falls back to
		 * autocomplete. Highlighting the usage the search never targets would
		 * read term vectors no query term ever lands in, so a highlight that
		 * was declared on the other usage is refused rather than answered
		 * with nothing.
		 */
		if(stringType.hasMatching()) {
			if(stringType.getMatching().hasHighlight()) {
				return encounter.name(FieldNames.MATCHING);
			}
		} else if(stringType.hasAutocomplete() && stringType.getAutocomplete().hasHighlight()) {
			return encounter.name(FieldNames.AUTOCOMPLETE);
		}

		throw new IndexFieldUsageException(encounter.getFieldName(), "highlight");
	}

	/**
	 * Get the name of the field values are looked up in, refusing fields that
	 * were never written for it.
	 *
	 * @param encounter
	 * @return
	 */
	private static String filterName(IndexEncounter encounter) {
		if(!encounter.isFiltered()) {
			throw new IndexFieldUsageException(encounter.getFieldName(), "filter");
		}

		return encounter.name(FieldNames.FILTER);
	}

	/**
	 * Get a value as the string this type holds.
	 *
	 * @param encounter
	 * @param value
	 * @return
	 */
	private static String stringValue(IndexEncounter encounter, Object value) {
		if(value instanceof String s) {
			return s;
		}

		throw new IndexInvalidQueryValueException(encounter.getFieldName(), "string");
	}

	/**
	 * Get one end of a range as it is written in the field, leaving an open
	 * end open.
	 *
	 * @param encounter
	 * @param value
	 * @return
	 */
	private static BytesRef bound(IndexEncounter encounter, Object value) {
		if(value == null) {
			return null;
		}

		return new BytesRef(filterValue(encounter, stringValue(encounter, value)));
	}

	/**
	 * Build a query for text somebody typed, combining its words the way the
	 * matcher asks.
	 *
	 * @param encounter
	 * @param matcher
	 * @return
	 */
	private static Query createTextQuery(IndexEncounter encounter, TextMatcher matcher) {
		var words = createWordsQuery(encounter, matcher);

		var exact = createExactQuery(encounter, matcher);
		if(exact == null) {
			return words;
		}

		/*
		 * The whole-value term is optional beside the words, which have to be
		 * found either way: a document only reaches this clause because its
		 * words matched, so ranking one above another can never change which
		 * documents a search found or what the facets beside them counted.
		 */
		return new BooleanQuery.Builder()
			.add(words, BooleanClause.Occur.MUST)
			.add(exact, BooleanClause.Occur.SHOULD)
			.build();
	}

	/**
	 * Build the query for the words of the text, however the matcher asks for
	 * them to be combined.
	 *
	 * @param encounter
	 * @param matcher
	 * @return
	 */
	private static Query createWordsQuery(IndexEncounter encounter, TextMatcher matcher) {
		if(matcher.match() == TextMatcher.Match.PHRASE) {
			return createPhraseQuery(encounter, matcher);
		}

		var words = textTermQueries(encounter, matcher);
		if(words.isEmpty()) {
			/*
			 * Nothing survived analysis, such as a query of only stopwords.
			 * Matching everything would be a stranger answer than matching
			 * nothing.
			 */
			return new MatchNoDocsQuery();
		}

		var occur = switch(matcher.match()) {
			case ALL -> BooleanClause.Occur.MUST;
			case ANY -> BooleanClause.Occur.SHOULD;
			/*
			 * A phrase left through the branch above. Text a person typed is
			 * taken apart into the clauses it stands for before a field is
			 * asked anything, so neither can be here.
			 */
			case PHRASE, USER -> throw new IllegalStateException(
				"Matching " + matcher.match() + " is not a combination of words"
			);
		};

		var builder = new BooleanQuery.Builder();
		for(var word : words) {
			builder.add(word, occur);
		}

		return builder.build();
	}

	@Override
	public ListIterable<Query> createTextTermQueries(
		IndexEncounter encounter,
		TextMatcher matcher
	) {
		if(matcher.match() != TextMatcher.Match.ALL && matcher.match() != TextMatcher.Match.ANY) {
			// A phrase holds within one field, so there is nothing to combine per word
			return null;
		}

		return textTermQueries(encounter, matcher);
	}

	@Override
	public Query createTextExactQuery(IndexEncounter encounter, TextMatcher matcher) {
		return createExactQuery(encounter, matcher);
	}

	/**
	 * Build the query that finds this field holding, whole, the text that was
	 * typed - which is what ranks a document named what was searched for above
	 * one that only mentions it.
	 *
	 * The text is normalized through the same analyzer the value went through,
	 * so the two meet as one term however the chain folds case, accents or
	 * Unicode forms.
	 *
	 * Whole means whole, including the last word, so a search that is still
	 * being typed finds nothing here until the word is finished. Asking for
	 * the value to start with what was typed instead would lift every listing
	 * that opens with the name as much as the thing itself - which on a shop
	 * is most of what the name was meant to beat.
	 *
	 * @param encounter
	 * @param matcher
	 * @return
	 *   the query, already carrying what the usage says a whole-value match
	 *   counts, or {@code null} where the usage did not ask for one
	 */
	private static Query createExactQuery(IndexEncounter encounter, TextMatcher matcher) {
		var stringType = encounter.getFieldType().getString();

		// The same choice textTermQueries makes about which usage is searched
		StringFieldTypeDef.TextUsageConfig usage;
		String suffix;
		Analyzer analyzer;

		if(stringType.hasMatching()) {
			usage = stringType.getMatching();
			suffix = FieldNames.MATCHING_EXACT;
			analyzer = Analyzers.matching(
				usage,
				encounter.getResources(),
				encounter.getLocaleSupport(),
				AnalyzerMode.QUERYING
			);
		} else if(stringType.hasAutocomplete()) {
			usage = stringType.getAutocomplete();
			suffix = FieldNames.AUTOCOMPLETE_EXACT;
			analyzer = Analyzers.autocomplete(
				usage,
				encounter.getResources(),
				encounter.getLocaleSupport(),
				AnalyzerMode.QUERYING
			);
		} else {
			// Left to the words of the text, which refuse the field with the usage
			return null;
		}

		if(!usage.hasExact()) {
			return null;
		}

		var name = encounter.name(suffix);
		Query query = new TermQuery(new Term(name, analyzer.normalize(name, matcher.text())));

		var boost = usage.getExact().hasBoost()
			? usage.getExact().getBoost()
			: DEFAULT_EXACT_BOOST;

		return boost == 1f ? query : new BoostQuery(query, boost);
	}

	/**
	 * Build the query each word of the text asks of this field, in the order
	 * the words were typed.
	 *
	 * The text is analyzed the same way the values of the field were, so that
	 * what comes out of both sides can be compared as terms - which is also
	 * where {@link AnalyzerMode#QUERYING} differs from indexing, as a field
	 * that indexed every prefix of a value must not cut the query into
	 * prefixes again.
	 *
	 * @param encounter
	 * @param matcher
	 * @return
	 */
	private static ImmutableList<Query> textTermQueries(
		IndexEncounter encounter,
		TextMatcher matcher
	) {
		var stringType = encounter.getFieldType().getString();
		var localeSupport = encounter.getLocaleSupport();

		String name;
		Analyzer analyzer;
		boolean prefixLast;
		StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig typos = null;
		var maxEdits = MAX_EDITS;

		if(stringType.hasMatching()) {
			name = encounter.name(FieldNames.MATCHING);
			analyzer = Analyzers.matching(
				stringType.getMatching(),
				encounter.getResources(),
				localeSupport,
				AnalyzerMode.QUERYING
			);
			prefixLast = matcher.prefix() == TextMatcher.Prefix.LAST_TOKEN;

			/*
			 * Typos are allowed where the definition declared them and the
			 * search did not turn them off. The search can only narrow - the
			 * definition is what says where fuzziness may be afforded.
			 */
			if(stringType.getMatching().hasTypoTolerance()
				&& matcher.typos() != TextMatcher.Typos.OFF) {
				typos = stringType.getMatching().getTypoTolerance();
			}
		} else if(stringType.hasAutocomplete()) {
			/*
			 * A field that only completes what is typed already holds every
			 * prefix of its values, so every word is looked up whole - what
			 * completes a half typed word here is the terms the field wrote,
			 * not a prefix query over them.
			 */
			name = encounter.name(FieldNames.AUTOCOMPLETE);
			analyzer = Analyzers.autocomplete(
				stringType.getAutocomplete(),
				encounter.getResources(),
				localeSupport,
				AnalyzerMode.QUERYING
			);
			prefixLast = false;

			/*
			 * Which is also why one fuzzy lookup forgives a mistake in a word
			 * that is only half typed: the prefixes stand in the index as terms
			 * of their own, so the word as it should have been typed so far is
			 * one of the terms a near reading of it can land on.
			 */
			if(stringType.getAutocomplete().hasTypoTolerance()
				&& matcher.typos() != TextMatcher.Typos.OFF) {
				typos = stringType.getAutocomplete().getTypoTolerance();
				maxEdits = typos.hasMinLengthTwoTypos() ? MAX_EDITS : MAX_EDITS_WHILE_TYPING;
			}
		} else {
			throw new IndexFieldUsageException(encounter.getFieldName(), "matching");
		}

		var tokens = analyze(encounter, analyzer, name, matcher.text());

		var words = Lists.mutable.<Query>empty();
		for(var i = 0; i < tokens.size(); i++) {
			var term = new Term(name, tokens.get(i));
			var isLast = i == tokens.size() - 1;

			words.add(tokenQuery(term, prefixLast && isLast, typos, maxEdits));
		}

		return words.toImmutable();
	}

	/**
	 * Build a query for the words of the text found in the order they were
	 * typed, next to each other or as far apart as the slop of the matcher
	 * allows.
	 *
	 * The words carry the positions query analysis gave them, so a dropped
	 * stopword leaves the same hole in the phrase that indexing left in the
	 * value, and the two line up. Only the matching usage holds positions
	 * worth reading - an autocomplete field stacks every prefix of a word at
	 * one position - so a field without matching refuses the phrase.
	 *
	 * @param encounter
	 * @param matcher
	 * @return
	 */
	private static Query createPhraseQuery(IndexEncounter encounter, TextMatcher matcher) {
		var stringType = encounter.getFieldType().getString();
		if(!stringType.hasMatching()) {
			throw new IndexFieldUsageException(encounter.getFieldName(), "matching");
		}

		var name = encounter.name(FieldNames.MATCHING);
		var analyzer = Analyzers.matching(
			stringType.getMatching(),
			encounter.getResources(),
			encounter.getLocaleSupport(),
			AnalyzerMode.QUERYING
		);

		var positions = analyzePositions(encounter, analyzer, name, matcher.text());
		if(positions.isEmpty()) {
			// Nothing survived analysis, the same answer a text of stopwords gets
			return new MatchNoDocsQuery();
		}

		var prefixLast = matcher.prefix() == TextMatcher.Prefix.LAST_TOKEN;

		if(positions.size() == 1) {
			// A phrase of one word is that word, half typed or not
			return positionQuery(name, positions.get(0), prefixLast);
		}

		if(!prefixLast && matcher.slop() == 0) {
			return exactPhrase(name, positions);
		}

		return spanPhrase(name, positions, prefixLast, matcher.slop());
	}

	/**
	 * Build the query a phrase of a single position asks: one word, or a
	 * choice between the variants analysis stacked there.
	 */
	private static Query positionQuery(
		String name,
		PositionedTerms position,
		boolean prefix
	) {
		if(position.terms().size() == 1) {
			return tokenQuery(new Term(name, position.terms().get(0)), prefix, null, 0);
		}

		var builder = new BooleanQuery.Builder();
		for(var term : position.terms()) {
			builder.add(
				tokenQuery(new Term(name, term), prefix, null, 0),
				BooleanClause.Occur.SHOULD
			);
		}

		return builder.build();
	}

	/**
	 * Build a phrase of whole words. A position holding a single term goes
	 * into a {@link PhraseQuery}; one where analysis stacked variants - a
	 * filter that keeps an original alongside its folded form - needs
	 * {@link MultiPhraseQuery}, which accepts any of them at that position.
	 */
	private static Query exactPhrase(String name, ListIterable<PositionedTerms> positions) {
		if(positions.allSatisfy(p -> p.terms().size() == 1)) {
			var builder = new PhraseQuery.Builder();
			for(var position : positions) {
				builder.add(new Term(name, position.terms().get(0)), position.position());
			}

			return builder.build();
		}

		var builder = new MultiPhraseQuery.Builder();
		for(var position : positions) {
			builder.add(
				position.terms()
					.collect(term -> new Term(name, term))
					.toArray(new Term[0]),
				position.position()
			);
		}

		return builder.build();
	}

	/**
	 * Build a phrase as spans, which is what the two things a
	 * {@link PhraseQuery} has no place for need: a last word that may still be
	 * half typed, and a distance the words may be moved apart while staying in
	 * the order they were typed.
	 *
	 * Each position is a term - or a choice between the variants analysis
	 * stacked there - with the holes of dropped stopwords kept as gaps. The
	 * slop is counted across the phrase as a whole, so it is the number of
	 * other words that may sit anywhere between its words, and a document
	 * whose words sit closer together scores above one where they are further
	 * apart.
	 */
	private static Query spanPhrase(
		String name,
		ListIterable<PositionedTerms> positions,
		boolean prefixLast,
		int slop
	) {
		var builder = new SpanNearQuery.Builder(name, true);
		builder.setSlop(slop);

		// Measured from the first surviving word, as phrase positions are relative
		var next = positions.get(0).position();
		var last = positions.size() - 1;

		for(var i = 0; i <= last; i++) {
			var position = positions.get(i);
			if(position.position() > next) {
				builder.addGap(position.position() - next);
			}

			var isLast = i == last && prefixLast;
			var variants = position.terms().collect(
				term -> isLast
					? (SpanQuery) new SpanMultiTermQueryWrapper<>(
						new PrefixQuery(new Term(name, term))
					)
					: new SpanTermQuery(new Term(name, term))
			);

			builder.addClause(
				variants.size() == 1
					? variants.get(0)
					: new SpanOrQuery(variants.toArray(new SpanQuery[0]))
			);

			next = position.position() + 1;
		}

		return builder.build();
	}

	/**
	 * The terms query analysis put at one position - several when a filter
	 * kept an original alongside a rewritten form of it.
	 */
	private record PositionedTerms(int position, ImmutableList<String> terms) {
	}

	/**
	 * Run text through an analyzer keeping the positions of what comes out,
	 * for queries where the place a word sits matters and not only which words
	 * there are.
	 *
	 * @param encounter
	 * @param analyzer
	 * @param name
	 * @param text
	 * @return
	 */
	private static ImmutableList<PositionedTerms> analyzePositions(
		IndexEncounter encounter,
		Analyzer analyzer,
		String name,
		String text
	) {
		var positions = Lists.mutable.<PositionedTerms>empty();

		try(var stream = analyzer.tokenStream(name, text)) {
			var term = stream.addAttribute(CharTermAttribute.class);
			var increment = stream.addAttribute(PositionIncrementAttribute.class);

			var position = -1;
			MutableList<String> current = null;

			stream.reset();
			while(stream.incrementToken()) {
				var inc = increment.getPositionIncrement();
				if(current == null || inc > 0) {
					if(current != null) {
						positions.add(new PositionedTerms(position, current.toImmutable()));
					}

					position += Math.max(1, inc);
					current = Lists.mutable.empty();
				}

				current.add(term.toString());
			}
			stream.end();

			if(current != null) {
				positions.add(new PositionedTerms(position, current.toImmutable()));
			}
		} catch(IOException e) {
			throw new IndexException(
				ANALYSIS_FAILED,
				e,
				"name", encounter.getFieldName()
			);
		}

		return positions.toImmutable();
	}

	/**
	 * Build the query for one word of the text.
	 *
	 * A word that is still being typed matches anything it starts. When typos
	 * are allowed the mistake may sit inside the part typed so far, so the
	 * word matches anything that a slightly different reading of it starts -
	 * comparing what was typed against whole terms would demand the rest of
	 * the word before the mistake is forgiven.
	 *
	 * @param term
	 *   the word as it came out of analysis
	 * @param prefix
	 *   if the word may still be half typed
	 * @param typos
	 *   what mistakes are allowed, {@code null} for none
	 * @param maxEdits
	 *   the most mistakes to forgive, whatever the word is long enough for
	 * @return
	 */
	private static Query tokenQuery(
		Term term,
		boolean prefix,
		StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig typos,
		int maxEdits
	) {
		var edits = typos == null ? 0 : editsAllowed(term.text(), typos, maxEdits);

		var exact = prefix
			? new PrefixQuery(term, EXPANSION_REWRITE)
			: (Query) new TermQuery(term);

		if(edits == 0) {
			return exact;
		}

		/*
		 * The word as it was typed next to its misreadings: only a document
		 * holding what was actually typed matches both clauses, which is what
		 * keeps the word spelled right ahead.
		 */
		return new BooleanQuery.Builder()
			.add(exact, BooleanClause.Occur.SHOULD)
			.add(typoLadder(term, edits, typos, prefix), BooleanClause.Occur.SHOULD)
			.build();
	}

	/**
	 * Match every reading of a word within the mistakes forgiven, scored by
	 * how many the reading needs.
	 *
	 * Each number of mistakes past the first is a band holding only the
	 * terms that many mistakes reach and fewer do not, so no term - and no
	 * postings of the documents holding it - is walked by more than one
	 * band. The correctly spelled term is the common one, so it is the walk
	 * this shape saves most: bands holding every narrower one would read its
	 * postings again per band, for a score the first band already decides.
	 * The first band alone keeps the word itself, because sitting in both
	 * clauses of {@link #tokenQuery} is what puts a document spelling the
	 * word right above every band.
	 *
	 * A band needing fewer mistakes is boosted above one needing more, and a
	 * document holding readings from several bands takes the best of them
	 * rather than their sum: a document is as close to the word as the
	 * closest reading it holds, and also holding a worse one says nothing
	 * more about it.
	 *
	 * @param term
	 *   the word as it came out of analysis
	 * @param edits
	 *   how many mistakes to forgive, at least one
	 * @param typos
	 *   the tolerance declared by the definition
	 * @param prefix
	 *   if the word may still be half typed
	 */
	private static Query typoLadder(
		Term term,
		int edits,
		StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig typos,
		boolean prefix
	) {
		if(edits == 1) {
			return editBand(term, 1, false, typos, prefix);
		}

		var bands = Lists.mutable.<Query>empty();
		bands.add(new BoostQuery(editBand(term, 1, false, typos, prefix), edits));

		for(var d = 2; d <= edits; d++) {
			var band = editBand(term, d, true, typos, prefix);
			var boost = edits - d + 1;
			bands.add(boost == 1 ? band : new BoostQuery(band, boost));
		}

		return new DisjunctionMaxQuery(bands, 0f);
	}

	/**
	 * Get how many typos a word is long enough to carry, up to the ceiling the
	 * usage affords. A short word is mostly other words, so how much mistake it
	 * can absorb grows with its length.
	 *
	 * A word of digits alone carries none however long it is, unless the usage
	 * asked for them through {@code numbers}: a number one digit off is a
	 * different number rather than a misspelling, so forgiving the difference
	 * answers with what was not asked for.
	 *
	 * @param token
	 * @param typos
	 * @param maxEdits
	 * @return
	 */
	private static int editsAllowed(
		String token,
		StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig typos,
		int maxEdits
	) {
		if(!typos.hasNumbers() && isAllDigits(token)) {
			return 0;
		}

		var length = token.codePointCount(0, token.length());

		var two = typos.hasMinLengthTwoTypos()
			? typos.getMinLengthTwoTypos()
			: DEFAULT_MIN_LENGTH_TWO_TYPOS;
		if(maxEdits >= 2 && length >= two) {
			return 2;
		}

		var one = typos.hasMinLengthOneTypo()
			? typos.getMinLengthOneTypo()
			: DEFAULT_MIN_LENGTH_ONE_TYPO;
		return length >= one ? 1 : 0;
	}

	/**
	 * Get whether a word is digits alone - in any script, the way the token
	 * came out of analysis. A word that mixes digits with anything else is a
	 * word like any other.
	 */
	private static boolean isAllDigits(String token) {
		return !token.isEmpty()
			&& token.codePoints().allMatch(Character::isDigit);
	}

	/**
	 * Match every term within the given number of edits of the typed word -
	 * or, when only the terms the last edit reaches are wanted, every term
	 * within that number and no fewer. A word still being typed matches every
	 * term such a reading of it starts.
	 *
	 * Answered from {@link #FUZZY_QUERIES} where the same word has been asked
	 * for before, because building one costs more than running it.
	 *
	 * @param term
	 *   the word as it came out of analysis
	 * @param edits
	 *   how many mistakes to forgive
	 * @param exactly
	 *   if terms reached with fewer mistakes are kept out
	 * @param typos
	 *   the tolerance declared by the definition
	 * @param prefix
	 *   if the word may still be half typed
	 * @return
	 */
	private static Query editBand(
		Term term,
		int edits,
		boolean exactly,
		StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig typos,
		boolean prefix
	) {
		var prefixLength = typos.hasPrefixLength()
			? typos.getPrefixLength()
			: DEFAULT_PREFIX_LENGTH;

		var key = new FuzzyKey(term.field(), term.text(), edits, exactly, prefixLength, prefix);
		var built = FUZZY_QUERIES.get(key);
		if(built != null) {
			return built;
		}

		/*
		 * Built outside the cache rather than through computeIfAbsent, so that
		 * one word being built does not hold up the searches looking for
		 * another. Two threads that want the same one build it twice and keep
		 * the second, which is two automata rather than a queue behind one.
		 */
		var query = buildEditBand(term, edits, exactly, prefixLength, prefix);
		FUZZY_QUERIES.put(key, query);
		return query;
	}

	/**
	 * Build what {@link #editBand} answers with, for a word not built
	 * before.
	 *
	 * The Levenshtein automaton of the word is what accepts a term close
	 * enough to it; a half typed word has "anything after" concatenated onto
	 * that, so a term is accepted as soon as some prefix of it is close
	 * enough. A band that keeps narrower readings out subtracts the automaton
	 * of one mistake fewer, leaving only the terms the last mistake buys -
	 * with the states that can no longer reach an accept removed, so the walk
	 * of the term dictionary does not descend into what the subtraction
	 * emptied. The leading characters the definition wants matched exactly
	 * are kept out of the fuzzy part and counted in code points, so a word of
	 * characters outside the basic plane keeps as much of itself fixed as one
	 * of ASCII.
	 */
	private static Query buildEditBand(
		Term term,
		int edits,
		boolean exactly,
		int prefixLength,
		boolean prefix
	) {
		var text = term.text();

		var codePoints = text.codePointCount(0, text.length());
		var prefixEnd = text.offsetByCodePoints(0, Math.min(prefixLength, codePoints));

		var automaton = levenshtein(text, prefixEnd, edits, prefix);
		if(exactly) {
			automaton = Operations.removeDeadStates(
				Operations.minus(
					automaton,
					levenshtein(text, prefixEnd, edits - 1, prefix),
					Operations.DEFAULT_DETERMINIZE_WORK_LIMIT
				)
			);
		}

		return new AutomatonQuery(
			term,
			Operations.determinize(automaton, Operations.DEFAULT_DETERMINIZE_WORK_LIMIT),
			false,
			EXPANSION_REWRITE
		);
	}

	/**
	 * The automaton accepting every term within the given number of edits of
	 * the word - or, when the word may still be half typed, every term some
	 * such reading of it starts.
	 */
	private static Automaton levenshtein(
		String text,
		int prefixEnd,
		int edits,
		boolean prefix
	) {
		var automaton = new LevenshteinAutomata(text.substring(prefixEnd), true)
			.toAutomaton(edits, text.substring(0, prefixEnd));

		if(prefix) {
			automaton = Operations.concatenate(automaton, Automata.makeAnyString());
		}

		return automaton;
	}

	/**
	 * Run text through an analyzer and collect the terms it produced.
	 *
	 * @param encounter
	 * @param analyzer
	 * @param name
	 * @param text
	 * @return
	 */
	private static ImmutableList<String> analyze(
		IndexEncounter encounter,
		Analyzer analyzer,
		String name,
		String text
	) {
		var tokens = Lists.mutable.<String>empty();

		try(var stream = analyzer.tokenStream(name, text)) {
			var term = stream.addAttribute(CharTermAttribute.class);

			stream.reset();
			while(stream.incrementToken()) {
				tokens.add(term.toString());
			}
			stream.end();
		} catch(IOException e) {
			throw new IndexException(
				ANALYSIS_FAILED,
				e,
				"name", encounter.getFieldName()
			);
		}

		return tokens.toImmutable();
	}

	@Override
	public SortField createSortField(IndexEncounter encounter, boolean ascending) {
		// Lucene takes whether to reverse, which is the opposite of ascending
		var field = new SortField(
			encounter.name(FieldNames.SORT),
			SortField.Type.STRING,
			!ascending
		);

		field.setMissingValue(
			encounter.getSortConfig().getMissing() == SortConfig.Missing.MISSING_FIRST
				? SortField.STRING_FIRST
				: SortField.STRING_LAST
		);

		return field;
	}

	@Override
	public Term createPrimaryKeyTerm(IndexEncounter encounter, Object value) {
		if(!(value instanceof String text)) {
			throw new IndexInvalidQueryValueException(encounter.getFieldName(), "string");
		}

		return new Term(encounter.name(FieldNames.PRIMARY_KEY), text);
	}
}
