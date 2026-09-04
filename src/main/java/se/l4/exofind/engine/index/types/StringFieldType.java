package se.l4.exofind.engine.index.types;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.FilteredTermsEnum;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.queries.spans.SpanMultiTermQueryWrapper;
import org.apache.lucene.queries.spans.SpanNearQuery;
import org.apache.lucene.queries.spans.SpanOrQuery;
import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.queries.spans.SpanTermQuery;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.DisjunctionMaxQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.MultiPhraseQuery;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.SynonymQuery;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TermRangeQuery;
import org.apache.lucene.util.AttributeSource;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.automaton.Automata;
import org.apache.lucene.util.automaton.Automaton;
import org.apache.lucene.util.automaton.ByteRunAutomaton;
import org.apache.lucene.util.automaton.CompiledAutomaton;
import org.apache.lucene.util.automaton.LevenshteinAutomata;
import org.apache.lucene.util.automaton.Operations;
import org.eclipse.collections.api.collection.MutableCollection;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.set.ImmutableSet;
import org.eclipse.collections.impl.factory.Lists;

import se.l4.exofind.engine.errors.ErrorMessage;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.index.AnalyzedFields;
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
import se.l4.exofind.engine.index.analysis.SynonymOverlay;
import se.l4.exofind.engine.index.analysis.TokenGraph;
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
import se.l4.exofind.engine.query.matchers.RangesMatcher;
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
	 *
	 * That every term scores the same is also what makes an expansion's
	 * matches worth caching as a set - {@link #cacheable} rests on it, so a
	 * rewrite that scored terms apart could not just be swapped in here.
	 */
	private static final MultiTermQuery.RewriteMethod EXPANSION_REWRITE =
		MultiTermQuery.CONSTANT_SCORE_REWRITE;

	/**
	 * How many compiled typo tolerant automata are kept. The words are text
	 * somebody typed, so what is kept has to have a ceiling; this one holds
	 * what a search box being typed into produces - a word per keystroke - for
	 * a good number of people at once, while the automata stay a few megabytes
	 * rather than a share of the heap.
	 */
	private static final int FUZZY_CACHE_SIZE = 512;

	/**
	 * The typo tolerant automata already compiled, by the word they forgive
	 * mistakes in, how many are forgiven, how much of the word has to be right
	 * and whether the rest of it is still being typed.
	 *
	 * Compiling one turns every reading of the word within those mistakes into
	 * a table the term dictionary is walked against, which costs more than the
	 * walk itself: a search that found nothing compiles the same word again
	 * for every word it weighs before letting one go, and the next person to
	 * type it compiles it again after that. What is compiled depends on the
	 * word and on nothing of the index, so it is as good later as it was when
	 * it was compiled.
	 *
	 * The field a word is asked of does not decide one, because an automaton
	 * accepts terms and every field's terms are read the same way - so a search
	 * covering several fields compiles the word once and asks each field with
	 * it. Neither does the band of {@link #typoLadder} it serves: a band that
	 * keeps narrower readings out walks the same terms as the band of its own
	 * number of mistakes and drops what the band below already holds. A ladder
	 * therefore compiles one automaton per number of mistakes, and its first
	 * two bands share the automaton of one mistake. {@link EditBandQuery} holds
	 * the field and the band apart from what is compiled.
	 *
	 * The least recently asked for goes when the cache is full. Held through
	 * {@link Collections#synchronizedMap} rather than a concurrent map to keep
	 * that order, and the lock is held only for the lookup - see
	 * {@link #editAutomaton}.
	 */
	private static final Map<AutomatonKey, CompiledAutomaton> FUZZY_AUTOMATA =
		Collections.synchronizedMap(
			new LinkedHashMap<AutomatonKey, CompiledAutomaton>(FUZZY_CACHE_SIZE, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(
					Map.Entry<AutomatonKey, CompiledAutomaton> eldest
				) {
					return size() > FUZZY_CACHE_SIZE;
				}
			}
		);

	/**
	 * What a compiled typo tolerant automaton is decided by, and so what one is
	 * kept under.
	 */
	private record AutomatonKey(
		String text,
		int edits,
		int prefixLength,
		boolean prefix
	) {
	}

	/**
	 * Which band of {@link #typoLadder} an {@link EditBandQuery} stands for.
	 * Two queries for the same band and field are the same query, whatever
	 * automata they were handed.
	 */
	private record FuzzyKey(
		String text,
		int edits,
		boolean exactly,
		int prefixLength,
		boolean prefix
	) {
		/**
		 * Get the automaton reaching every term within this band's mistakes,
		 * including the terms fewer mistakes reach.
		 */
		AutomatonKey reached() {
			return new AutomatonKey(text, edits, prefixLength, prefix);
		}

		/**
		 * Get the automaton reaching the terms this band keeps out, which is
		 * the band of one mistake fewer.
		 */
		AutomatonKey narrower() {
			return new AutomatonKey(text, edits - 1, prefixLength, prefix);
		}
	}

	/**
	 * The terms one band of {@link #typoLadder} reaches in one field, matched
	 * against an automaton compiled before the field was known.
	 *
	 * Lucene's own {@link org.apache.lucene.search.AutomatonQuery} compiles the
	 * automaton it is handed, and compiling costs several times what building
	 * the automaton did, so a word asked of several fields would pay for the
	 * same table once per field. What the automaton accepts depends on the word
	 * alone, so the compiled table is kept in {@link #FUZZY_AUTOMATA} and the
	 * field lives here.
	 *
	 * A band that keeps narrower readings out walks the terms every reading
	 * within its own mistakes reaches, and drops each term the automaton of one
	 * mistake fewer accepts. A dropped term is left before its postings are
	 * opened, so the band reads the documents of a term only when the term is
	 * its own.
	 *
	 * Two of these are the same query when they ask the same field for the same
	 * band, which lets the searcher's own cache answer one from the documents
	 * another matched.
	 */
	private static final class EditBandQuery extends MultiTermQuery {
		private final FuzzyKey band;
		private final CompiledAutomaton reached;
		private final CompiledAutomaton narrower;

		EditBandQuery(
			String field,
			FuzzyKey band,
			CompiledAutomaton reached,
			CompiledAutomaton narrower
		) {
			super(field, EXPANSION_REWRITE);

			this.band = band;
			this.reached = reached;
			this.narrower = narrower;
		}

		@Override
		protected TermsEnum getTermsEnum(Terms terms, AttributeSource atts)
			throws IOException
		{
			var matched = reached.getTermsEnum(terms);

			/*
			 * Lucene leaves a compiled automaton without a table to run when it
			 * recognizes the language as every term or no term. A narrower band
			 * reaching every term takes a word still being typed, no leading
			 * characters held fixed and fewer characters typed than mistakes
			 * forgiven; both bands then reach the same terms and the ladder
			 * scores each term by the narrower band, which it ranks higher.
			 */
			if(narrower == null || narrower.runAutomaton == null) {
				return matched;
			}

			return new ExactBandTermsEnum(matched, narrower.runAutomaton);
		}

		/**
		 * Offer a highlighter the terms every reading within this band's
		 * mistakes reaches. That includes the terms the band keeps out of its
		 * matches, and the band below it in the same ladder offers those, so
		 * the ladder as a whole marks the same text.
		 */
		@Override
		public void visit(QueryVisitor visitor) {
			reached.visit(visitor, this, getField());
		}

		@Override
		public int hashCode() {
			return 31 * super.hashCode() + band.hashCode();
		}

		@Override
		public boolean equals(Object obj) {
			return super.equals(obj) && band.equals(((EditBandQuery) obj).band);
		}

		@Override
		public String toString(String field) {
			var builder = new StringBuilder();
			if(!getField().equals(field)) {
				builder.append(getField()).append(':');
			}

			builder.append(band.text()).append('~').append(band.edits());

			if(band.exactly()) {
				builder.append(" exactly");
			}

			if(band.prefix()) {
				builder.append('*');
			}

			return builder.toString();
		}
	}

	/**
	 * The terms a band of {@link #typoLadder} matches when it keeps narrower
	 * readings out: the terms its own mistakes reach, less the ones the band of
	 * one mistake fewer already holds.
	 *
	 * The wrapped enumeration is the walk of the wider band, so a rejected term
	 * is one the narrower band of the same ladder answers. The test runs the
	 * narrower band's compiled automaton over the bytes the term dictionary
	 * hands over, which are the UTF-8 that automaton was compiled to read.
	 *
	 * Rejecting a term costs the automaton alone. Everything a matched term is
	 * asked for - its bytes, its document frequency, its state and its postings
	 * - comes from the wrapped enumeration.
	 */
	private static final class ExactBandTermsEnum extends FilteredTermsEnum {
		private final ByteRunAutomaton narrower;

		ExactBandTermsEnum(TermsEnum reached, ByteRunAutomaton narrower) {
			// Read the wrapped enumeration forward from where it starts: it is
			// already limited to the terms of the wider band, so there is
			// nothing to seek past
			super(reached, false);

			this.narrower = narrower;
		}

		@Override
		protected AcceptStatus accept(BytesRef term) {
			return narrower.run(term.bytes, term.offset, term.length)
				? AcceptStatus.NO
				: AcceptStatus.YES;
		}
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

	/**
	 * Report the fields {@link #createFields} writes through
	 * {@link AnalyzingTextField}. The names and shapes are built the same way
	 * there, so a usage added to one belongs in the other.
	 */
	@Override
	public void collectAnalyzedFields(
		IndexEncounter encounter,
		AnalyzedFields.Collector collector
	) {
		var stringType = encounter.getFieldType().getString();

		if(stringType.hasMatching()) {
			collector.add(
				encounter.name(FieldNames.MATCHING),
				textShape(encounter, stringType.getMatching().hasHighlight())
			);
		}

		if(stringType.hasAutocomplete()) {
			collector.add(
				encounter.name(FieldNames.AUTOCOMPLETE),
				textShape(encounter, stringType.getAutocomplete().hasHighlight())
			);
		}
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
				textShape(encounter, matchConfig.hasHighlight()),
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
				textShape(encounter, autocompleteConfig.hasHighlight()),
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
	 * Get the shape a text usage's terms are written in: bare unless the
	 * usage highlights, and then carrying offsets where the index's layout
	 * keeps them.
	 */
	private static AnalyzingTextField.Shape textShape(
		IndexEncounter encounter,
		boolean highlighted
	) {
		if(!highlighted) {
			return AnalyzingTextField.Shape.PLAIN;
		}

		return encounter.isHighlightingInPostings()
			? AnalyzingTextField.Shape.HIGHLIGHTABLE_POSTINGS
			: AnalyzingTextField.Shape.HIGHLIGHTABLE_TERM_VECTORS;
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
			return rangeQuery(encounter, m);
		}

		if(matcher instanceof RangesMatcher m) {
			if(m.ranges().isEmpty()) {
				return new MatchNoDocsQuery();
			}

			var builder = new BooleanQuery.Builder();
			builder.setMinimumNumberShouldMatch(1);
			for(var range : m.ranges()) {
				builder.add(rangeQuery(encounter, range), BooleanClause.Occur.SHOULD);
			}

			return builder.build();
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

	private Query rangeQuery(IndexEncounter encounter, RangeMatcher m) {
		/*
		 * Bounded by the bytes of the value as it is filtered on, not by the
		 * collation ordering results uses - a range is between two values a
		 * caller wrote, and it holds whatever falls between them however the
		 * locale would have sorted it.
		 */
		return new TermRangeQuery(
			filterName(encounter),
			bound(encounter, m.lower()),
			bound(encounter, m.upper()),
			m.lowerInclusive(),
			m.upperInclusive()
		);
	}

	@Override
	public FacetCounter createFacetCounter(IndexEncounter encounter) {
		var stringType = encounter.getFieldType().getString();

		/*
		 * A prefix is compared with the values folded the way the autocomplete
		 * usage folds them - the engine-built chain where the field declares
		 * none - as the field's own filter terms fold case alone.
		 */
		var normalizer = Analyzers.autocomplete(
			stringType.hasAutocomplete()
				? stringType.getAutocomplete()
				: StringFieldTypeDef.TextUsageConfig.getDefaultInstance(),
			encounter.getResources(),
			encounter.getLocaleSupport(),
			AnalyzerMode.QUERYING
		);

		// The doc values hold the value as it was given, so it is the count key
		return FacetCounter.overStrings(
			encounter.name(FieldNames.VALUES),
			value -> value,
			normalizer
		);
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
		 * read offsets no query term ever lands in, so a highlight that was
		 * declared on the other usage is refused rather than answered with
		 * nothing.
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
	 * Build the query each part of the text asks of this field, in the order
	 * the words were typed.
	 *
	 * The text is analyzed the same way the values of the field were, so that
	 * what comes out of both sides can be compared as terms - which is also
	 * where {@link AnalyzerMode#QUERYING} differs from indexing, as a field
	 * that indexed every prefix of a value must not cut the query into
	 * prefixes again.
	 *
	 * A part is one word wherever nothing widened the text. Where the search
	 * settings widen it, a rule standing for several words makes one part of
	 * the words it stands for - see {@link TokenGraph}.
	 *
	 * The last word of a text of several, when it may still be half typed, is
	 * asked of the autocomplete usage when the definition holds one beside
	 * matching - see {@link #completedLastWord} for when that holds and why.
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
		Tolerance tolerance = null;

		/*
		 * Only the matching usage holds positions worth reading; an
		 * autocomplete usage stacks every prefix of a word at one position, so
		 * a rule of several words is asked of it as words rather than as a
		 * phrase.
		 */
		var positioned = stringType.hasMatching();

		if(stringType.hasMatching()) {
			name = encounter.name(FieldNames.MATCHING);
			var base = Analyzers.matching(
				stringType.getMatching(),
				encounter.getResources(),
				localeSupport,
				AnalyzerMode.QUERYING
			);
			analyzer = queryAnalyzer(encounter, base);
			prefixLast = matcher.prefix() == TextMatcher.Prefix.LAST_TOKEN;

			/*
			 * Typos are allowed where the definition declared them and the
			 * search did not turn them off. The search can only narrow - the
			 * definition is what says where fuzziness may be afforded.
			 */
			if(stringType.getMatching().hasTypoTolerance()
				&& matcher.typos() != TextMatcher.Typos.OFF) {
				tolerance = new Tolerance(
					stringType.getMatching().getTypoTolerance(),
					MAX_EDITS,
					excludedTerms(encounter, base)
				);
			}
		} else if(stringType.hasAutocomplete()) {
			/*
			 * A field that only completes what is typed already holds every
			 * prefix of its values, so every word is looked up whole - what
			 * completes a half typed word here is the terms the field wrote,
			 * not a prefix query over them.
			 */
			name = encounter.name(FieldNames.AUTOCOMPLETE);
			var base = Analyzers.autocomplete(
				stringType.getAutocomplete(),
				encounter.getResources(),
				localeSupport,
				AnalyzerMode.QUERYING
			);
			analyzer = queryAnalyzer(encounter, base);
			prefixLast = false;

			/*
			 * Which is also why one fuzzy lookup forgives a mistake in a word
			 * that is only half typed: the prefixes stand in the index as terms
			 * of their own, so the word as it should have been typed so far is
			 * one of the terms a near reading of it can land on.
			 */
			if(stringType.getAutocomplete().hasTypoTolerance()
				&& matcher.typos() != TextMatcher.Typos.OFF) {
				var typos = stringType.getAutocomplete().getTypoTolerance();

				tolerance = new Tolerance(
					typos,
					typos.hasMinLengthTwoTypos() ? MAX_EDITS : MAX_EDITS_WHILE_TYPING,
					excludedTerms(encounter, base)
				);
			}
		} else {
			throw new IndexFieldUsageException(encounter.getFieldName(), "matching");
		}

		var segments = analyze(encounter, analyzer, name, matcher.text());

		var completedLast = prefixLast && segments.size() > 1 && !encounter.isForHighlighting()
			? completedLastWord(encounter, matcher, segments.size())
			: null;

		var parts = Lists.mutable.<Query>empty();
		for(var i = 0; i < segments.size(); i++) {
			var isLast = i == segments.size() - 1;

			parts.add(
				isLast && completedLast != null
					? completedLast
					: segmentQuery(
						name,
						segments.get(i),
						prefixLast && isLast,
						tolerance,
						positioned
					)
			);
		}

		return parts.toImmutable();
	}

	/**
	 * Build the query one part of the text asks for.
	 *
	 * <p>A part of one position is a word, or a choice between the words
	 * analysis stacked there - a {@link SynonymQuery}, which counts the choice
	 * as one word however rare each of its terms happens to be, so that a
	 * document found through a synonym is scored like one found through the
	 * word that was typed. Where the choice cannot be one term query - a word
	 * still being typed, or one whose mistakes are forgiven - the terms are
	 * asked for one at a time instead.
	 *
	 * <p>A part of several positions is a choice between readings of the same
	 * stretch of text, each read as a phrase where the field holds positions
	 * and as its words where it does not.
	 *
	 * @param name
	 *   what the field is written under
	 * @param segment
	 * @param prefix
	 *   if the last word of the part may still be half typed
	 * @param tolerance
	 *   what mistakes are forgiven, {@code null} for none
	 * @param positioned
	 *   whether the field holds positions a phrase can be read from
	 * @return
	 */
	private static Query segmentQuery(
		String name,
		TokenGraph.Segment segment,
		boolean prefix,
		Tolerance tolerance,
		boolean positioned
	) {
		if(segment.isSingleWord()) {
			var words = distinct(segment.words());

			if(words.size() == 1) {
				return boosted(
					tokenQuery(new Term(name, words.get(0).text()), prefix, tolerance),
					words.get(0).boost()
				);
			}

			/*
			 * Words none of which is read fuzzily are one choice, whether the
			 * field forgives no mistakes at all or the settings match each of
			 * these words as it is spelled.
			 */
			if(!prefix && words.noneSatisfy(word -> forgiven(tolerance, word.text()))) {
				var builder = new SynonymQuery.Builder(name);
				for(var word : words) {
					builder.addTerm(new Term(name, word.text()), word.boost());
				}

				return builder.build();
			}

			var builder = new BooleanQuery.Builder();
			for(var word : words) {
				builder.add(
					boosted(
						tokenQuery(new Term(name, word.text()), prefix, tolerance),
						word.boost()
					),
					BooleanClause.Occur.SHOULD
				);
			}

			return builder.build();
		}

		var builder = new BooleanQuery.Builder();
		for(var alternative : segment.alternatives()) {
			builder.add(
				boosted(
					readingQuery(name, alternative, prefix, positioned),
					weightOf(alternative)
				),
				BooleanClause.Occur.SHOULD
			);
		}

		return builder.build();
	}

	/**
	 * Build the query one reading of a part asks for: its words in the order
	 * and at the distances the reading puts them, or the words on their own
	 * where the field holds no positions to read them at.
	 */
	private static Query readingQuery(
		String name,
		TokenGraph.Alternative alternative,
		boolean prefix,
		boolean positioned
	) {
		var terms = alternative.terms();

		if(terms.size() == 1) {
			return tokenQuery(new Term(name, terms.get(0).term().text()), prefix, null);
		}

		if(!positioned) {
			var builder = new BooleanQuery.Builder();
			for(var placed : terms) {
				builder.add(
					tokenQuery(new Term(name, placed.term().text()), false, null),
					BooleanClause.Occur.MUST
				);
			}

			return builder.build();
		}

		/*
		 * Spans rather than a phrase, because a reading can end in a word that
		 * is still being typed, which a PhraseQuery has no place for.
		 */
		return spanReading(name, alternative, prefix);
	}

	/**
	 * What a reading counts, which is what its least generous word counts - a
	 * reading is only as much of a synonym as the most distant word in it.
	 */
	private static float weightOf(TokenGraph.Alternative alternative) {
		var weight = 1f;
		for(var placed : alternative.terms()) {
			weight = Math.min(weight, placed.term().boost());
		}

		return weight;
	}

	private static Query boosted(Query query, float boost) {
		return boost == 1f ? query : new BoostQuery(query, boost);
	}

	/**
	 * The words of a part with each word kept once, worth the most generous of
	 * what its copies were worth.
	 *
	 * <p>Two rules can add the same word, and a rule can add the word that was
	 * typed. Asking for it twice would count a document holding it twice.
	 */
	private static ImmutableList<TokenGraph.Term> distinct(
		ListIterable<TokenGraph.Term> words
	) {
		var best = new LinkedHashMap<String, Float>();
		for(var word : words) {
			best.merge(word.text(), word.boost(), Math::max);
		}

		if(best.size() == words.size()) {
			return words.toList().toImmutable();
		}

		var distinct = Lists.mutable.<TokenGraph.Term>empty();
		best.forEach((text, boost) -> distinct.add(new TokenGraph.Term(text, boost)));

		return distinct.toImmutable();
	}

	/**
	 * Build the query a half typed last word asks of the autocomplete usage of
	 * a field defined for both matching and autocomplete.
	 *
	 * An autocomplete usage holds every prefix of its words as terms of their
	 * own, so the word as typed so far is one term looked up whole, carrying
	 * frequencies and per-block score bounds like any term. A prefix over the
	 * matching terms - what answers for a field without autocomplete - stands
	 * for a set in which every document scores the same, and a set that says
	 * as much about one block as the next lets a search skip nothing.
	 *
	 * The word is what the autocomplete chain leaves of the text, which keeps
	 * words the matching chain rewrites or drops. When the two chains disagree
	 * on how many words the text holds, which of their words is which cannot
	 * be told, and the matching usage answers alone.
	 *
	 * Only a text of several words asks this. A half typed word on its own has
	 * no other word to be narrowed by, so there is nothing for the score
	 * bounds to cut; what remains is the walk itself, and the autocomplete
	 * term - every word the typed one starts - is the longer one.
	 *
	 * A query compiled for highlighting never asks it either: the terms of a
	 * highlight query have to land in the field that carries the offsets,
	 * which is the matching field - see
	 * {@link IndexEncounter#isForHighlighting()}.
	 *
	 * Mistakes in the word are forgiven as the autocomplete usage declares,
	 * with the forgiveness a word still being typed gets - see
	 * {@link #MAX_EDITS_WHILE_TYPING}.
	 *
	 * @param matchingParts
	 *   how many parts the matching chain cut the text into
	 * @return
	 *   the query for the last word, or {@code null} when the field has no
	 *   autocomplete usage or the chains disagree on the parts of the text
	 */
	private static Query completedLastWord(
		IndexEncounter encounter,
		TextMatcher matcher,
		int matchingParts
	) {
		var stringType = encounter.getFieldType().getString();
		if(!stringType.hasAutocomplete()) {
			return null;
		}

		var usage = stringType.getAutocomplete();
		var name = encounter.name(FieldNames.AUTOCOMPLETE);
		var base = Analyzers.autocomplete(
			usage,
			encounter.getResources(),
			encounter.getLocaleSupport(),
			AnalyzerMode.QUERYING
		);
		var analyzer = queryAnalyzer(encounter, base);

		var segments = analyze(encounter, analyzer, name, matcher.text());
		if(segments.size() != matchingParts) {
			return null;
		}

		Tolerance tolerance = null;
		if(usage.hasTypoTolerance() && matcher.typos() != TextMatcher.Typos.OFF) {
			var typos = usage.getTypoTolerance();

			tolerance = new Tolerance(
				typos,
				typos.hasMinLengthTwoTypos() ? MAX_EDITS : MAX_EDITS_WHILE_TYPING,
				excludedTerms(encounter, base)
			);
		}

		return segmentQuery(name, segments.getLast(), false, tolerance, false);
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
	 * A phrase counts a word the search settings added as it counts the word
	 * that was typed: what a phrase asks is where the words sit, and there is
	 * no place in that for weighing one position against another.
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
		var analyzer = queryAnalyzer(
			encounter,
			Analyzers.matching(
				stringType.getMatching(),
				encounter.getResources(),
				encounter.getLocaleSupport(),
				AnalyzerMode.QUERYING
			)
		);

		var segments = analyze(encounter, analyzer, name, matcher.text());
		if(segments.isEmpty()) {
			// Nothing survived analysis, the same answer a text of stopwords gets
			return new MatchNoDocsQuery();
		}

		var prefixLast = matcher.prefix() == TextMatcher.Prefix.LAST_TOKEN;

		if(segments.size() == 1 && segments.get(0).isSingleWord()) {
			// A phrase of one word is that word, half typed or not
			return segmentQuery(name, segments.get(0), prefixLast, null, true);
		}

		if(!prefixLast
			&& matcher.slop() == 0
			&& segments.allSatisfy(TokenGraph.Segment::isSingleWord)) {
			return exactPhrase(name, segments);
		}

		return spanPhrase(name, segments, prefixLast, matcher.slop());
	}

	/**
	 * Build a phrase of whole words. A position holding a single term goes
	 * into a {@link PhraseQuery}; one where analysis stacked variants - a
	 * filter that keeps an original alongside its folded form, or a rule that
	 * added a word - needs {@link MultiPhraseQuery}, which accepts any of them
	 * at that position.
	 */
	private static Query exactPhrase(String name, ListIterable<TokenGraph.Segment> segments) {
		if(segments.allSatisfy(segment -> segment.words().size() == 1)) {
			var builder = new PhraseQuery.Builder();
			for(var segment : segments) {
				builder.add(
					new Term(name, segment.words().get(0).text()),
					segment.position()
				);
			}

			return builder.build();
		}

		var builder = new MultiPhraseQuery.Builder();
		for(var segment : segments) {
			builder.add(
				segment.words()
					.collect(word -> new Term(name, word.text()))
					.toArray(new Term[0]),
				segment.position()
			);
		}

		return builder.build();
	}

	/**
	 * Build a phrase as spans, which is what the three things a
	 * {@link PhraseQuery} has no place for need: a last word that may still be
	 * half typed, a distance the words may be moved apart while staying in the
	 * order they were typed, and a part standing for several words at once.
	 *
	 * Each part is a term, a choice between the variants analysis stacked
	 * there, or a choice between readings of several words, with the holes of
	 * dropped stopwords kept as gaps. The slop is counted across the phrase as
	 * a whole, so it is the number of other words that may sit anywhere
	 * between its words, and a document whose words sit closer together scores
	 * above one where they are further apart.
	 */
	private static Query spanPhrase(
		String name,
		ListIterable<TokenGraph.Segment> segments,
		boolean prefixLast,
		int slop
	) {
		var builder = new SpanNearQuery.Builder(name, true);
		builder.setSlop(slop);

		// Measured from the first surviving word, as phrase positions are relative
		var next = segments.get(0).position();
		var last = segments.size() - 1;

		for(var i = 0; i <= last; i++) {
			var segment = segments.get(i);
			if(segment.position() > next) {
				builder.addGap(segment.position() - next);
			}

			builder.addClause(spanSegment(name, segment, i == last && prefixLast));

			next = segment.position() + segment.length();
		}

		return builder.build();
	}

	/**
	 * Build the span one part of a phrase stands for.
	 */
	private static SpanQuery spanSegment(
		String name,
		TokenGraph.Segment segment,
		boolean prefix
	) {
		if(segment.isSingleWord()) {
			var variants = segment.words()
				.collect(word -> spanTerm(new Term(name, word.text()), prefix));

			return variants.size() == 1
				? variants.get(0)
				: new SpanOrQuery(variants.toArray(new SpanQuery[0]));
		}

		var readings = segment.alternatives()
			.collect(alternative -> spanReading(name, alternative, prefix));

		return readings.size() == 1
			? readings.get(0)
			: new SpanOrQuery(readings.toArray(new SpanQuery[0]));
	}

	/**
	 * Build the span one reading of several words stands for: the words in the
	 * order the reading puts them, with the holes it leaves kept as gaps.
	 */
	private static SpanQuery spanReading(
		String name,
		TokenGraph.Alternative alternative,
		boolean prefix
	) {
		var terms = alternative.terms();
		if(terms.size() == 1) {
			return spanTerm(new Term(name, terms.get(0).term().text()), prefix);
		}

		var builder = new SpanNearQuery.Builder(name, true);
		builder.setSlop(0);

		var next = terms.get(0).offset();
		for(var i = 0; i < terms.size(); i++) {
			var placed = terms.get(i);
			if(placed.offset() > next) {
				builder.addGap(placed.offset() - next);
			}

			builder.addClause(spanTerm(
				new Term(name, placed.term().text()),
				prefix && i == terms.size() - 1
			));

			next = placed.offset() + 1;
		}

		return builder.build();
	}

	private static SpanQuery spanTerm(Term term, boolean prefix) {
		return prefix
			? new SpanMultiTermQueryWrapper<>(new PrefixQuery(term))
			: new SpanTermQuery(term);
	}

	/**
	 * Get the analyzer to read the text of a search with: the one the field's
	 * usage defines, widened by whatever the search settings of the index add
	 * to that field. Values are never read through it - what a rule adds is a
	 * word to search for, not a word a document holds.
	 *
	 * @param encounter
	 * @param base
	 *   the analyzer the usage defines
	 * @return
	 */
	private static Analyzer queryAnalyzer(IndexEncounter encounter, Analyzer base) {
		return encounter.getQuerySynonyms().wrap(base, encounter.getFieldName());
	}

	/**
	 * Run the text of a search through an analyzer, as the parts a query is
	 * built from.
	 *
	 * @param encounter
	 * @param analyzer
	 * @param name
	 *   what the field is written under
	 * @param text
	 * @return
	 * @throws IndexException
	 *   if the text could not be analyzed
	 */
	private static ImmutableList<TokenGraph.Segment> analyze(
		IndexEncounter encounter,
		Analyzer analyzer,
		String name,
		String text
	) {
		try(var stream = analyzer.tokenStream(name, text)) {
			return TokenGraph.read(stream);
		} catch(IOException e) {
			throw new IndexException(
				ANALYSIS_FAILED,
				e,
				"name", encounter.getFieldName()
			);
		}
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
	 * @param tolerance
	 *   what mistakes are forgiven, {@code null} for none
	 * @return
	 */
	private static Query tokenQuery(
		Term term,
		boolean prefix,
		Tolerance tolerance
	) {
		var edits = tolerance == null ? 0 : tolerance.editsAllowed(term.text());

		var exact = prefix
			? cacheable(new PrefixQuery(term, EXPANSION_REWRITE))
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
			.add(typoLadder(term, edits, tolerance.config(), prefix), BooleanClause.Occur.SHOULD)
			.build();
	}

	/**
	 * Match every reading of a word within the mistakes forgiven, scored by
	 * how many the reading needs.
	 *
	 * Each number of mistakes past the first is a band holding only the
	 * terms that many mistakes reach and fewer do not, so no term's postings
	 * are read by more than one band. A band walks the term dictionary for
	 * every term its own mistakes reach and leaves the ones a narrower band
	 * holds before opening them. The correctly spelled term is the common one,
	 * so it is the reading this shape saves most: bands holding every narrower
	 * one would read its postings again per band, for a score the first band
	 * already decides.
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
	 * What mistakes are forgiven in the words of one search of one field: the
	 * tolerance the definition declared, the ceiling the shape of the query
	 * puts on it, and the words the search settings match as they are spelled.
	 *
	 * @param config
	 *   the tolerance the usage being searched declares
	 * @param maxEdits
	 *   the most mistakes to forgive, whatever a word is long enough for
	 * @param excluded
	 *   the terms matched as they are spelled, empty when the settings exclude
	 *   none in this field
	 */
	private record Tolerance(
		StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig config,
		int maxEdits,
		ImmutableSet<String> excluded
	) {
		/**
		 * Get how many typos a word is long enough to carry, up to the ceiling
		 * the usage affords. A short word is mostly other words, so how much
		 * mistake it can absorb grows with its length.
		 *
		 * A word the search settings exclude carries none, and neither does a
		 * word of digits alone unless the usage asked for them through
		 * {@code numbers}: a number one digit off is a different number rather
		 * than a misspelling, so forgiving the difference answers with what was
		 * not asked for.
		 *
		 * @param token
		 *   the word as it came out of analysis
		 * @return
		 */
		int editsAllowed(String token) {
			if(excluded.contains(token)) {
				return 0;
			}

			if(!config.hasNumbers() && isAllDigits(token)) {
				return 0;
			}

			var length = token.codePointCount(0, token.length());

			var two = config.hasMinLengthTwoTypos()
				? config.getMinLengthTwoTypos()
				: DEFAULT_MIN_LENGTH_TWO_TYPOS;
			if(maxEdits >= 2 && length >= two) {
				return 2;
			}

			var one = config.hasMinLengthOneTypo()
				? config.getMinLengthOneTypo()
				: DEFAULT_MIN_LENGTH_ONE_TYPO;
			return length >= one ? 1 : 0;
		}
	}

	/**
	 * Get whether a word is read fuzzily at all, for the shapes that ask for
	 * one query where nothing is.
	 */
	private static boolean forgiven(Tolerance tolerance, String token) {
		return tolerance != null && tolerance.editsAllowed(token) > 0;
	}

	/**
	 * Get the terms of a field the search settings match as they are spelled.
	 *
	 * <p>Read through the analyzer the definition builds rather than the one
	 * {@link SynonymOverlay} widens, so a set that names a synonym of an
	 * excluded word does not carry the exclusion over to it.
	 */
	private static ImmutableSet<String> excludedTerms(IndexEncounter encounter, Analyzer base) {
		return encounter.getTypoExclusions().termsIn(base, encounter.getFieldName());
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
	 * Both shapes walk the terms the given number of mistakes reaches. A band
	 * that keeps fewer mistakes out carries the automaton of one mistake fewer
	 * as well, and drops the terms it accepts as the walk reaches them, so a
	 * ladder needs one automaton per number of mistakes and none for the
	 * difference between two.
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

		var band = new FuzzyKey(term.text(), edits, exactly, prefixLength, prefix);

		return cacheable(new EditBandQuery(
			term.field(),
			band,
			editAutomaton(band.reached()),
			exactly ? editAutomaton(band.narrower()) : null
		));
	}

	/**
	 * Get the automaton accepting every term within the given number of
	 * mistakes of a word, for walking a dictionary outside a text search - a
	 * facet picking values near a typed prefix, see
	 * {@code StringFacetCount}. Shares the compiled automata of the text
	 * searches, so a word a search has forgiven before is answered without
	 * compiling.
	 *
	 * @param text
	 *   the word, already folded the way the dictionary it will walk is
	 * @param edits
	 *   how many mistakes to forgive, at most {@link #MAX_EDITS}
	 * @param prefixLength
	 *   how many leading code points are matched as they stand
	 * @param prefix
	 *   whether the word may still be half typed, so a term is accepted as
	 *   soon as some prefix of it is within the mistakes
	 * @return
	 *   the automaton, safe to share between threads
	 */
	public static CompiledAutomaton typoAutomaton(
		String text,
		int edits,
		int prefixLength,
		boolean prefix
	) {
		if(edits < 0 || edits > MAX_EDITS) {
			throw new IllegalArgumentException(
				"A word forgives between 0 and " + MAX_EDITS + " mistakes"
			);
		}

		return editAutomaton(new AutomatonKey(text, edits, prefixLength, prefix));
	}

	/**
	 * Get the table a field's terms are walked against for one reading of a
	 * word, from {@link #FUZZY_AUTOMATA} where the same reading has been asked
	 * for before - whatever field or band asked for it - because compiling one
	 * costs more than running it.
	 */
	private static CompiledAutomaton editAutomaton(AutomatonKey reading) {
		var compiled = FUZZY_AUTOMATA.get(reading);
		if(compiled == null) {
			/*
			 * Compiled outside the cache rather than through computeIfAbsent,
			 * so that one word being compiled does not hold up the searches
			 * looking for another. Two threads that want the same one compile
			 * it twice and keep the second, which is two automata rather than a
			 * queue behind one.
			 */
			compiled = compileEditAutomaton(reading);
			FUZZY_AUTOMATA.put(reading, compiled);
		}

		return compiled;
	}

	/**
	 * Compile the table {@link #editAutomaton} hands out, for a reading not
	 * compiled before.
	 *
	 * The Levenshtein automaton of the word accepts a term close enough to it;
	 * a half typed word has "anything after" concatenated onto that, so a term
	 * is accepted as soon as some prefix of it is close enough. The leading
	 * characters the definition wants matched exactly are kept out of the fuzzy
	 * part and counted in code points, so a word of characters outside the
	 * basic plane keeps as much of itself fixed as one of ASCII.
	 *
	 * Whether the automaton accepts finitely many terms is told rather than
	 * left to be found out. A word with an end is near finitely many others
	 * however many mistakes are forgiven, and a word still being typed stands
	 * for every term some reading of it starts, which is endless. Both follow
	 * from the shape asked for, while Lucene would walk the automaton again to
	 * learn what this already knows.
	 */
	private static CompiledAutomaton compileEditAutomaton(AutomatonKey reading) {
		var text = reading.text();

		var codePoints = text.codePointCount(0, text.length());
		var prefixEnd = text.offsetByCodePoints(0, Math.min(reading.prefixLength(), codePoints));

		var automaton = levenshtein(text, prefixEnd, reading.edits(), reading.prefix());

		return new CompiledAutomaton(
			Operations.determinize(automaton, Operations.DEFAULT_DETERMINIZE_WORK_LIMIT),
			!reading.prefix(),
			true,
			false
		);
	}

	/**
	 * Put an expansion where the searcher's query cache can see it, so a word
	 * asked for again answers from the documents it matched last time instead
	 * of walking the term dictionary of every segment again.
	 *
	 * The searcher only offers a query to its cache when it is asked for
	 * without scores, and a clause of a ranked search never is - wrapping in
	 * {@link ConstantScoreQuery} is what asks for the documents scorelessly
	 * while handing a score onward. That loses nothing only because of what
	 * {@link #EXPANSION_REWRITE} guarantees: every document an expansion
	 * matches scores the same, so the set alone is the whole answer. A rewrite
	 * that scored the matched terms apart would be flattened by this wrap, not
	 * heard through it.
	 */
	private static Query cacheable(Query expansion) {
		return new ConstantScoreQuery(expansion);
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
