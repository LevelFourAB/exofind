package se.l4.exofind.engine.index.analysis;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.collections.api.collection.MutableCollection;

import se.l4.exofind.engine.errors.ErrorMessage;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.index.locales.Locales;
import se.l4.exofind.engine.index.schema.AnalyzerDef;
import se.l4.exofind.engine.index.schema.ResourcesDef;

/**
 * Checks a stored analysis chain for the parts that can be wrong: a locale
 * this build has no support for, a named resource that does not exist, n-gram
 * sizes that make no sense and patterns that do not compile.
 *
 * The shape of the chain itself needs no checking - a component this build
 * does not know is refused through the required features of the definition.
 *
 * A chain sits either on a text usage of a field or among the resources of
 * the index, and both are validated here so they can not drift apart.
 */
public final class AnalyzerChains {
	private static final ErrorType UNSUPPORTED_LOCALE = ErrorType
		.withCode("index:field:analyzer:unsupported_locale")
		.withArguments("locale")
		.withMessage(
			"The analysis chain names locale `{{locale}}` which this version of the engine does not support"
		);

	private static final ErrorType INVALID_GRAMS = ErrorType
		.withCode("index:field:analyzer:invalid_grams")
		.withMessage(
			"An n-gram in the analysis chain needs sizes of at least one, with the shortest not longer than the longest"
		);

	private static final ErrorType INVALID_PATTERN = ErrorType
		.withCode("index:field:analyzer:invalid_pattern")
		.withMessage(
			"A pattern replacement in the analysis chain has to have a valid regular expression"
		);

	private static final ErrorType UNKNOWN_STOPWORDS = ErrorType
		.withCode("index:field:analyzer:unknown_stopwords")
		.withArguments("resource")
		.withMessage(
			"The analysis chain names stopword list `{{resource}}` which the resources of the index do not define"
		);

	private static final ErrorType UNKNOWN_SYNONYMS = ErrorType
		.withCode("index:field:analyzer:unknown_synonyms")
		.withArguments("resource")
		.withMessage(
			"The analysis chain names synonym set `{{resource}}` which the resources of the index do not define"
		);

	private static final ErrorType UNSUPPORTED_DECOMPOUNDING = ErrorType
		.withCode("index:field:analyzer:unsupported_decompounding")
		.withArguments("locale")
		.withMessage(
			"The analysis chain splits compounds by locale `{{locale}}` which this version of the engine has no decompounding data for"
		);

	private AnalyzerChains() {
	}

	/**
	 * Validate a chain, adding what is wrong with it to the given errors.
	 *
	 * @param location
	 *   where the chain sits, so an error points at it
	 * @param errors
	 * @param analyzer
	 * @param resources
	 *   what the index shares between fields, for checking that what the
	 *   chain names by name exists
	 */
	public static void validate(
		ObjectLocation location,
		MutableCollection<ErrorMessage> errors,
		AnalyzerDef analyzer,
		ResourcesDef resources
	) {
		for(var charFilter : analyzer.getCharFiltersList()) {
			if(charFilter.hasPatternReplace()) {
				try {
					Pattern.compile(charFilter.getPatternReplace().getPattern());
				} catch(PatternSyntaxException e) {
					errors.add(INVALID_PATTERN.toMessage(location));
				}
			}
		}

		for(var filter : analyzer.getFiltersList()) {
			switch(filter.getFilterCase()) {
				case STOPWORDS -> {
					var stopwords = filter.getStopwords();
					if(stopwords.hasLocale() && stopwords.getLocale().hasLocale()) {
						validateLocale(location, errors, stopwords.getLocale().getLocale());
					}
					if(stopwords.hasNamed()) {
						var name = stopwords.getNamed().getName();
						if(!resources.getStopwordsMap().containsKey(name)) {
							errors.add(
								UNKNOWN_STOPWORDS.toMessage(location, "resource", name)
							);
						}
					}
				}
				case STEMMING -> {
					if(filter.getStemming().hasLocale()) {
						validateLocale(location, errors, filter.getStemming().getLocale());
					}
				}
				case SYNONYMS -> {
					var name = filter.getSynonyms().getName();
					if(!resources.getSynonymsMap().containsKey(name)) {
						errors.add(UNKNOWN_SYNONYMS.toMessage(location, "resource", name));
					}
				}
				case DECOMPOUND -> {
					/*
					 * Only a named locale is refused here. Without one the
					 * component follows the locale of the value and quietly
					 * passes locales without data through, the same way
					 * stemming quietly does nothing for a locale without a
					 * stemmer.
					 */
					if(filter.getDecompound().hasLocale()) {
						var locale = filter.getDecompound().getLocale();
						validateLocale(location, errors, locale);

						var support = Locales.get(locale);
						if(support.isPresent() && !support.get().isDecompoundingSupported()) {
							errors.add(
								UNSUPPORTED_DECOMPOUNDING.toMessage(location, "locale", locale)
							);
						}
					}
				}
				case EDGE_NGRAM -> validateGrams(
					location,
					errors,
					filter.getEdgeNgram().hasMinGram() ? filter.getEdgeNgram().getMinGram() : null,
					filter.getEdgeNgram().hasMaxGram() ? filter.getEdgeNgram().getMaxGram() : null
				);
				case NGRAM -> validateGrams(
					location,
					errors,
					filter.getNgram().hasMinGram() ? filter.getNgram().getMinGram() : null,
					filter.getNgram().hasMaxGram() ? filter.getNgram().getMaxGram() : null
				);
				default -> {
					// Nothing to check for the rest
				}
			}
		}
	}

	private static void validateLocale(
		ObjectLocation location,
		MutableCollection<ErrorMessage> errors,
		String locale
	) {
		if(!Locales.isSupported(locale)) {
			errors.add(UNSUPPORTED_LOCALE.toMessage(location, "locale", locale));
		}
	}

	private static void validateGrams(
		ObjectLocation location,
		MutableCollection<ErrorMessage> errors,
		Integer min,
		Integer max
	) {
		var resolvedMin = min != null ? min : Analyzers.DEFAULT_MIN_GRAM;
		var resolvedMax = max != null ? max : Analyzers.DEFAULT_MAX_GRAM;

		if(resolvedMin < 1 || resolvedMax < resolvedMin) {
			errors.add(INVALID_GRAMS.toMessage(location));
		}
	}
}
