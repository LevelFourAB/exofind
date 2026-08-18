package se.l4.exofind.engine.index.schema;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.set.ImmutableSet;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.api.set.SetIterable;

import se.l4.exofind.engine.index.locales.Locales;

/**
 * The engine features a definition can need, and which of them this build has.
 *
 * Protobuf keeps fields it has no code for, so a node reading a definition
 * written by a newer version sees the parts it understands and silently drops
 * the rest. An index would come out looking fine while missing whatever the
 * newer version asked for. To stop that, whichever version stores a definition
 * writes down what the definition needs, and every version checks that list
 * against what it has before opening the index.
 *
 * This only works if the names are stable, so a name here is never renamed or
 * reused once released - it is a value written to disk, not an identifier.
 */
public final class IndexFeatures {
	public static final String TYPE_STRING = "type.string";
	public static final String TYPE_BOOLEAN = "type.boolean";

	/**
	 * The number types. One name each covers the type together with its
	 * validation bounds - they shipped together, so no node knows one without
	 * the other. Anything added to a type later gets a name of its own.
	 */
	public static final String TYPE_INT32 = "type.int32";
	public static final String TYPE_INT64 = "type.int64";
	public static final String TYPE_FLOAT = "type.float";
	public static final String TYPE_DOUBLE = "type.double";

	/**
	 * A field holds a point in time, compared as an instant. Named because a
	 * node without it could not index the values at all, let alone order them
	 * as instants.
	 */
	public static final String TYPE_TIMESTAMP = "type.timestamp";

	/**
	 * A field holds a point on the earth, searched by distance. One name
	 * covers the type together with its distance matcher and distance sort -
	 * they shipped together, so no geo-capable node knows only some of them.
	 */
	public static final String TYPE_GEO_POINT = "type.geo_point";

	/**
	 * A field holds a vector searched by similarity. One name covers the
	 * dimensions, similarity, HNSW parameters and quantization of the type -
	 * they shipped together, so no vector-capable node exists that knows only
	 * some of them. Anything added to the type later gets a name of its own.
	 */
	public static final String TYPE_VECTOR = "type.vector";

	/**
	 * A field holds documents of its own, each indexed as its own unit. One
	 * name covers the type together with the block writing and the {@code
	 * nested} clause that reads it - they shipped together. A node without it
	 * would not only miss the fields inside, it would answer searches with the
	 * inner documents as results, so refusing the whole index is the only safe
	 * answer. The fields inside carry their own type names besides this one.
	 */
	public static final String TYPE_OBJECT = "type.object";

	/**
	 * A field inside an object is defined for matching, sorting or faceting,
	 * rather than only for filtering.
	 *
	 * One name covers the three because they shipped together, and because they
	 * are one capability seen from three sides - a value of an object answering
	 * for the document that holds it. A node without it refuses the definition
	 * outright rather than writing the values with none of the terms and doc
	 * values the usages ask for, which is what an index searched from both
	 * would quietly be.
	 */
	public static final String TYPE_OBJECT_USAGES = "type.object.usages";

	/**
	 * A field holds objects whose fields fold into the document itself,
	 * addressed by the dotted path. Named because a node without it would keep
	 * the values as documents of their own and demand {@code nested} clauses
	 * for fields the definition promises answer directly - the same index
	 * written two ways depending on who indexed it.
	 */
	public static final String TYPE_OBJECT_FLATTENED = "type.object.flattened";

	/**
	 * The index keeps documents as they were given.
	 *
	 * Named because a node without it would store only the fields that ask to
	 * be stored and answer with whatever that came to, which for a definition
	 * that stores nothing per field is an empty document rather than an error.
	 */
	public static final String INDEX_SOURCE = "index.source";

	public static final String FIELD_FILTER = "field.filter";
	public static final String FIELD_SORT = "field.sort";
	public static final String FIELD_FACET = "field.facet";
	public static final String FIELD_MATCHING = "field.matching";
	public static final String FIELD_AUTOCOMPLETE = "field.autocomplete";

	/**
	 * How a string is normalized before it is compared exactly.
	 *
	 * Named because a node without it would fold case where the definition
	 * asked to keep it, writing filter terms that do not meet the ones an
	 * aware node looks up.
	 */
	public static final String FIELD_KEYWORD = "field.keyword";

	/**
	 * Values of a field are read as paths through a tree.
	 *
	 * Named because a node without it would write each value as one whole
	 * value and nothing else, leaving the levels a search narrows to and
	 * counts down missing from every document it indexed.
	 */
	public static final String FIELD_HIERARCHY = "field.hierarchy";

	/**
	 * Values of a field differ per locale.
	 *
	 * Named because a node without it would analyze every value by the default
	 * locale and quietly produce different terms than a node that follows the
	 * locale of the value.
	 */
	public static final String FIELD_LOCALES = "field.locales";

	/**
	 * A text usage carries its own analysis chain instead of the engine-built
	 * one.
	 *
	 * Named because a node without it would fall back to the engine-built
	 * chain and index different terms than the definition asked for. The
	 * components of the chain have names of their own below, so a chain using
	 * a component a node does not have is refused even when the node knows
	 * chains in general.
	 */
	public static final String FIELD_ANALYZER = "field.analyzer";

	/**
	 * A text usage analyzes with a chain named in the resources of the index.
	 *
	 * Named because a node without it would fall back to the engine-built
	 * chain and index different terms than the chain the name stands for.
	 */
	public static final String RESOURCE_ANALYZER = "resource.analyzer";

	/**
	 * A chain drops the words of a stopword list named in the resources.
	 *
	 * Named because a node without it would drop nothing and index the words
	 * the list asked to leave out.
	 */
	public static final String RESOURCE_STOPWORDS = "resource.stopwords";

	/**
	 * A chain widens tokens with a synonym set named in the resources.
	 *
	 * Named because a node without it would index a value without its
	 * synonyms and quietly answer searches for them with nothing.
	 */
	public static final String RESOURCE_SYNONYMS = "resource.synonyms";

	/**
	 * A chain splits compound words into their parts.
	 *
	 * Named because a node without it would index compounds whole and
	 * quietly answer searches for their parts with nothing. The data that
	 * splits a locale's words has a name of its own per locale, {@code
	 * decompound.} and the tag, so a build carrying the component but not a
	 * locale's data still refuses rather than splitting nothing.
	 */
	public static final String ANALYZER_DECOMPOUND = "analyzer.decompound";

	public static final String ANALYZER_ICU = "analyzer.icu";
	public static final String ANALYZER_WHITESPACE = "analyzer.whitespace";
	public static final String ANALYZER_KEYWORD = "analyzer.keyword";
	public static final String ANALYZER_LETTER = "analyzer.letter";
	public static final String ANALYZER_HTML_STRIP = "analyzer.html_strip";
	public static final String ANALYZER_MAPPING = "analyzer.mapping";
	public static final String ANALYZER_PATTERN_REPLACE = "analyzer.pattern_replace";
	public static final String ANALYZER_NORMALIZE = "analyzer.normalize";
	public static final String ANALYZER_STOPWORDS = "analyzer.stopwords";
	public static final String ANALYZER_STEMMING = "analyzer.stemming";
	public static final String ANALYZER_ASCII_FOLDING = "analyzer.ascii_folding";
	public static final String ANALYZER_EDGE_NGRAM = "analyzer.edge_ngram";
	public static final String ANALYZER_NGRAM = "analyzer.ngram";
	public static final String ANALYZER_SYNONYMS = "analyzer.synonyms";

	/**
	 * A field counts for more or less than the others when text is searched
	 * across several.
	 *
	 * Weights change nothing about how documents are indexed, but a node
	 * without them would rank every field the same and answer with a quietly
	 * different order.
	 */
	public static final String FIELD_WEIGHT = "field.weight";

	/**
	 * A field matches words despite typing mistakes.
	 *
	 * Also query-time only - a node without it would answer a misspelled
	 * search with nothing rather than with what was meant.
	 */
	public static final String FIELD_TYPO_TOLERANCE = "field.typo_tolerance";

	/**
	 * A field completes what is typed despite typing mistakes.
	 *
	 * Named separately from the one above because it arrived later and a node
	 * knowing typos in general may not know them here: an older node looks the
	 * typed word up whole among the prefixes the field wrote, and would answer
	 * a misspelled keystroke with nothing where an aware node completes it.
	 */
	public static final String FIELD_AUTOCOMPLETE_TYPO_TOLERANCE =
		"field.autocomplete.typo_tolerance";

	/**
	 * A field answers searches with highlighted fragments of its text.
	 *
	 * Unlike the two above this one changes how documents are indexed - the
	 * term vectors and the stored copy fragments are built from only exist
	 * because the field was indexed with them. A node without it would index
	 * the field bare, and a node asked to highlight what such a node indexed
	 * would find nothing to read.
	 */
	public static final String FIELD_HIGHLIGHT = "field.highlight";

	/**
	 * A field ranks a value a search matched whole above one that merely holds
	 * the same words.
	 *
	 * Like the highlight above, this changes how documents are indexed - the
	 * whole-value term only exists because the field was written with it. A
	 * node without it would index the field bare, and a node reading such an
	 * index would find no term to rank by and quietly go back to ranking a
	 * mention as highly as a name.
	 */
	public static final String FIELD_EXACT = "field.exact";

	/**
	 * A field says how much the length of a value counts against it.
	 *
	 * Query-time only, like the field weights - the length is in the norms
	 * whoever wrote them - but a node without it would weigh length the
	 * engine's way and answer with a quietly different order.
	 */
	public static final String FIELD_LENGTH_NORMALIZATION = "field.length_normalization";

	/**
	 * The index breaks ties in the order of results itself.
	 *
	 * A node without it would fall back to an arbitrary tie order and page
	 * differently through the same results.
	 */
	public static final String INDEX_RANKING = "index.ranking";

	/**
	 * The index takes a value of the documents themselves into their relevance.
	 *
	 * Named separately from the tie breakers above because it arrived later and
	 * a node knowing one may not know the other. Query-time only, like the
	 * field weights - but a node without it would answer with the documents in
	 * the order they matched, with nothing to say that the ranking it was asked
	 * for went missing.
	 */
	public static final String INDEX_RANKING_SIGNALS = "index.ranking_signals";

	/**
	 * The index fills the locales a document holds no value in from another
	 * locale.
	 *
	 * Named because it changes what is written: a node without it would index
	 * only the locales a document carries, leaving the variants an aware node
	 * fills empty. Searching such an index in a locale a document was never
	 * translated into would answer with nothing, which is the hole the
	 * fallback exists to close, and the two nodes would disagree about which
	 * documents an index holds rather than about how they rank.
	 */
	public static final String INDEX_LOCALE_FALLBACK = "index.locale_fallback";

	/**
	 * Everything this build knows how to honour. The locales this build has
	 * are features too, named {@code locale.} and the tag, so a definition
	 * naming a locale a node lacks is refused the same way as one naming a
	 * component it lacks.
	 */
	private static final ImmutableSet<String> SUPPORTED = Sets.mutable
		.of(
			TYPE_STRING,
			TYPE_BOOLEAN,
			TYPE_VECTOR,
			TYPE_OBJECT,
			TYPE_OBJECT_USAGES,
			TYPE_OBJECT_FLATTENED,
			TYPE_INT32,
			TYPE_INT64,
			TYPE_FLOAT,
			TYPE_DOUBLE,
			TYPE_TIMESTAMP,
			TYPE_GEO_POINT,
			INDEX_SOURCE,
			INDEX_RANKING,
			INDEX_RANKING_SIGNALS,
			INDEX_LOCALE_FALLBACK,
			FIELD_FILTER,
			FIELD_SORT,
			FIELD_FACET,
			FIELD_MATCHING,
			FIELD_AUTOCOMPLETE,
			FIELD_KEYWORD,
			FIELD_HIERARCHY,
			FIELD_LOCALES,
			FIELD_ANALYZER,
			ANALYZER_ICU,
			ANALYZER_WHITESPACE,
			ANALYZER_KEYWORD,
			ANALYZER_LETTER,
			ANALYZER_HTML_STRIP,
			ANALYZER_MAPPING,
			ANALYZER_PATTERN_REPLACE,
			ANALYZER_NORMALIZE,
			ANALYZER_STOPWORDS,
			ANALYZER_STEMMING,
			ANALYZER_DECOMPOUND,
			ANALYZER_ASCII_FOLDING,
			ANALYZER_EDGE_NGRAM,
			ANALYZER_NGRAM,
			ANALYZER_SYNONYMS,
			RESOURCE_ANALYZER,
			RESOURCE_STOPWORDS,
			RESOURCE_SYNONYMS,
			FIELD_WEIGHT,
			FIELD_TYPO_TOLERANCE,
			FIELD_AUTOCOMPLETE_TYPO_TOLERANCE,
			FIELD_HIGHLIGHT,
			FIELD_EXACT,
			FIELD_LENGTH_NORMALIZATION
		)
		.withAll(Locales.supported().collect(IndexFeatures::localeFeature))
		.withAll(decompoundingLocales().collect(IndexFeatures::decompoundFeature))
		.toImmutable();

	private IndexFeatures() {
	}

	/**
	 * Get the name a locale goes by in the feature list.
	 *
	 * @param locale
	 *   BCP-47 tag
	 * @return
	 */
	public static String localeFeature(String locale) {
		return "locale." + locale;
	}

	/**
	 * Get the name a locale's decompounding data goes by in the feature list.
	 *
	 * @param locale
	 *   BCP-47 tag
	 * @return
	 */
	public static String decompoundFeature(String locale) {
		return "decompound." + locale;
	}

	/**
	 * The locales this build can split compounds for.
	 */
	private static SetIterable<String> decompoundingLocales() {
		return Locales.supported().select(
			tag -> Locales.get(tag)
				.map(support -> support.isDecompoundingSupported())
				.orElse(false)
		);
	}

	/**
	 * Work out what a definition needs, so it can be stored alongside it.
	 *
	 * Only features this build knows about can be found this way, which is
	 * exactly right - a definition is described by the version that writes it,
	 * and a version can only describe what it understands.
	 *
	 * @param definition
	 * @return
	 *   the names, sorted so that the same definition always produces the same
	 *   list and therefore the same version
	 */
	public static ListIterable<String> requiredBy(IndexDef definition) {
		var features = Sets.mutable.<String>empty();

		/*
		 * Only named when the definition says to keep documents. Saying not to
		 * asks for nothing a node without this would fail to do, so an index
		 * that keeps nothing stays readable by an older one.
		 */
		if(definition.hasSource() && IndexSchema.storesSource(definition)) {
			features.add(INDEX_SOURCE);
		}

		if(definition.hasRanking()) {
			features.add(INDEX_RANKING);

			if(definition.getRanking().getSignalsCount() > 0) {
				features.add(INDEX_RANKING_SIGNALS);
			}
		}

		/*
		 * The locales of the chain are named besides the fallback itself: a
		 * value is analyzed and collated as the locale it fills, so a node
		 * missing one of them could not write the copies even knowing to.
		 */
		if(definition.hasLocaleFallback()) {
			features.add(INDEX_LOCALE_FALLBACK);

			for(var locale : definition.getLocaleFallback().getChainList()) {
				features.add(localeFeature(locale));
			}
		}

		/*
		 * A chain among the resources runs on whichever node opens the index,
		 * so its components are described the same way an inline chain's are.
		 * The stopword lists and synonym sets themselves need no name here -
		 * they only do anything through the chain components that refer to
		 * them, and those carry the names.
		 *
		 * A resource chain serves any field that names it, so a component
		 * following the locale of the value is described against every locale
		 * this build can split - over-asking, which refuses where the
		 * alternative is nodes quietly splitting differently.
		 */
		for(var chain : definition.getResources().getAnalyzersMap().values()) {
			collectChain(chain, features, decompoundingLocales());
		}

		for(var field : definition.getFieldsMap().values()) {
			collectField(field, features);
		}

		return features.toSortedList().toImmutable();
	}

	private static void collectField(FieldDef field, MutableSet<String> features) {
		var localeTags = declaredLocales(field);

		switch(field.getType().getTypeCase()) {
			case STRING -> {
				features.add(TYPE_STRING);

				var string = field.getType().getString();
				if(string.hasKeyword()) {
					features.add(FIELD_KEYWORD);
				}
				if(string.hasHierarchy()) {
					features.add(FIELD_HIERARCHY);
				}
				if(string.hasMatching()) {
					features.add(FIELD_MATCHING);
					collectTextUsage(string.getMatching(), features, localeTags);

					/*
					 * The engine-built matching chain splits compounds unless
					 * the usage turned it off, so a field left to that chain
					 * needs the data of its locales the same way a stored
					 * chain would.
					 */
					if(!string.getMatching().hasAnalyzer()
						&& !string.getMatching().hasAnalyzerRef()
						&& string.getMatching().getDecompound()
							!= StringFieldTypeDef.TextUsageConfig.DecompoundMode.DECOMPOUND_MODE_NONE) {
						collectDecompounding(localeTags, features);
					}
				}
				if(string.hasAutocomplete()) {
					features.add(FIELD_AUTOCOMPLETE);
					collectTextUsage(string.getAutocomplete(), features, localeTags);

					/*
					 * Besides the name every typo tolerant field carries, because
					 * forgiving a mistake in a word the user is still typing came
					 * later than forgiving one in a word they finished.
					 */
					if(string.getAutocomplete().hasTypoTolerance()) {
						features.add(FIELD_AUTOCOMPLETE_TYPO_TOLERANCE);
					}
				}
			}
			case BOOLEAN -> features.add(TYPE_BOOLEAN);
			case VECTOR -> features.add(TYPE_VECTOR);
			case INT32 -> features.add(TYPE_INT32);
			case INT64 -> features.add(TYPE_INT64);
			case FLOAT -> features.add(TYPE_FLOAT);
			case DOUBLE -> features.add(TYPE_DOUBLE);
			case TIMESTAMP -> features.add(TYPE_TIMESTAMP);
			case GEO_POINT -> features.add(TYPE_GEO_POINT);
			case OBJECT -> {
				var nested = field.getType().getObject().getMode()
					== ObjectFieldTypeDef.Mode.MODE_NESTED;

				features.add(nested ? TYPE_OBJECT : TYPE_OBJECT_FLATTENED);

				// The fields inside need their own features besides this one
				for(var inner : field.getType().getObject().getFieldsMap().values()) {
					collectField(inner, features);

					/*
					 * Named besides the usages themselves, which say nothing
					 * about where they sit: a node knowing how to sort by a
					 * field of the index may not know how to sort by one that
					 * answers across a join. A flattened field is a field of
					 * the index, so its usages carry no name beyond their own.
					 */
					if(nested && usesMoreThanFiltering(inner)) {
						features.add(TYPE_OBJECT_USAGES);
					}
				}
			}
			default -> {
				// Left to Field.validate, which can point at the field
			}
		}

		if(field.hasLocales()) {
			features.add(FIELD_LOCALES);

			if(field.getLocales().hasDefaultLocale()) {
				features.add(localeFeature(field.getLocales().getDefaultLocale()));
			}

			for(var locale : field.getLocales().getLocalesList()) {
				features.add(localeFeature(locale));
			}
		}

		if(field.hasFilter()) {
			features.add(FIELD_FILTER);
		}

		if(field.hasSort()) {
			features.add(FIELD_SORT);
		}

		if(field.hasFacet()) {
			features.add(FIELD_FACET);
		}
	}

	/**
	 * Get whether a field inside an object asks for more of its values than
	 * deciding which documents match - ordering them, counting them or ranking
	 * them.
	 */
	private static boolean usesMoreThanFiltering(FieldDef inner) {
		if(inner.hasSort() || inner.hasFacet()) {
			return true;
		}

		return inner.getType().getTypeCase() == FieldTypeDef.TypeCase.STRING
			&& (inner.getType().getString().hasMatching()
				|| inner.getType().getString().hasAutocomplete());
	}

	private static void collectTextUsage(
		StringFieldTypeDef.TextUsageConfig usage,
		MutableSet<String> features,
		SetIterable<String> localeTags
	) {
		if(usage.hasWeight()) {
			features.add(FIELD_WEIGHT);
		}

		if(usage.hasTypoTolerance()) {
			features.add(FIELD_TYPO_TOLERANCE);
		}

		if(usage.hasHighlight()) {
			features.add(FIELD_HIGHLIGHT);
		}

		if(usage.hasExact()) {
			features.add(FIELD_EXACT);
		}

		if(usage.hasLengthNormalization()) {
			features.add(FIELD_LENGTH_NORMALIZATION);
		}

		if(usage.hasAnalyzer()) {
			features.add(FIELD_ANALYZER);
			collectChain(usage.getAnalyzer(), features, localeTags);
		}

		if(usage.hasAnalyzerRef()) {
			features.add(RESOURCE_ANALYZER);
		}
	}

	/**
	 * The locales a field says it holds values in - what a component that
	 * follows the locale of the value can end up running as.
	 */
	private static SetIterable<String> declaredLocales(FieldDef field) {
		var tags = Sets.mutable.<String>empty();

		if(field.hasLocales()) {
			if(field.getLocales().hasDefaultLocale()) {
				tags.add(field.getLocales().getDefaultLocale());
			}
			tags.addAll(field.getLocales().getLocalesList());
		}

		return tags;
	}

	/**
	 * Record the decompounding data a component without a named locale needs:
	 * the data of every given locale this build can split. A locale no build
	 * splits asks for nothing - the component passes it through - and a
	 * locale a later build learns to split is a change in what existing
	 * definitions produce, rolled out by reindexing rather than named here.
	 */
	private static void collectDecompounding(
		SetIterable<String> localeTags,
		MutableSet<String> features
	) {
		for(var tag : localeTags) {
			if(Locales.get(tag).map(support -> support.isDecompoundingSupported()).orElse(false)) {
				features.add(decompoundFeature(tag));
			}
		}
	}

	private static void collectChain(
		AnalyzerDef chain,
		MutableSet<String> features,
		SetIterable<String> localeTags
	) {
		for(var charFilter : chain.getCharFiltersList()) {
			switch(charFilter.getFilterCase()) {
				case HTML_STRIP -> features.add(ANALYZER_HTML_STRIP);
				case MAPPING -> features.add(ANALYZER_MAPPING);
				case PATTERN_REPLACE -> features.add(ANALYZER_PATTERN_REPLACE);
				default -> {
					// A component from a newer version, which it will describe
				}
			}
		}

		if(chain.hasTokenizer()) {
			switch(chain.getTokenizer().getTokenizerCase()) {
				case ICU -> features.add(ANALYZER_ICU);
				case WHITESPACE -> features.add(ANALYZER_WHITESPACE);
				case KEYWORD -> features.add(ANALYZER_KEYWORD);
				case LETTER -> features.add(ANALYZER_LETTER);
				default -> {
					// A component from a newer version, which it will describe
				}
			}
		}

		for(var filter : chain.getFiltersList()) {
			switch(filter.getFilterCase()) {
				case NORMALIZE -> features.add(ANALYZER_NORMALIZE);
				case STOPWORDS -> {
					features.add(ANALYZER_STOPWORDS);

					var stopwords = filter.getStopwords();
					if(stopwords.hasLocale() && stopwords.getLocale().hasLocale()) {
						features.add(localeFeature(stopwords.getLocale().getLocale()));
					}
					if(stopwords.hasNamed()) {
						features.add(RESOURCE_STOPWORDS);
					}
				}
				case STEMMING -> {
					features.add(ANALYZER_STEMMING);

					if(filter.getStemming().hasLocale()) {
						features.add(localeFeature(filter.getStemming().getLocale()));
					}
				}
				case ASCII_FOLDING -> features.add(ANALYZER_ASCII_FOLDING);
				case EDGE_NGRAM -> features.add(ANALYZER_EDGE_NGRAM);
				case NGRAM -> features.add(ANALYZER_NGRAM);
				case SYNONYMS -> {
					features.add(ANALYZER_SYNONYMS);
					features.add(RESOURCE_SYNONYMS);
				}
				case DECOMPOUND -> {
					features.add(ANALYZER_DECOMPOUND);

					if(filter.getDecompound().hasLocale()) {
						var locale = filter.getDecompound().getLocale();
						features.add(localeFeature(locale));
						features.add(decompoundFeature(locale));
					} else {
						collectDecompounding(localeTags, features);
					}
				}
				default -> {
					// A component from a newer version, which it will describe
				}
			}
		}
	}

	/**
	 * Get the features a definition asks for that this build does not have.
	 *
	 * @param definition
	 * @return
	 *   the names, empty when the definition can be opened here
	 */
	public static SetIterable<String> unsupportedIn(IndexDef definition) {
		var unsupported = Sets.mutable.<String>empty();

		for(var feature : definition.getRequiredFeaturesList()) {
			if(!SUPPORTED.contains(feature)) {
				unsupported.add(feature);
			}
		}

		return unsupported;
	}

	/**
	 * Get a definition with its required features filled in, ready to be
	 * stored.
	 *
	 * @param definition
	 * @return
	 */
	public static IndexDef describe(IndexDef definition) {
		return definition.toBuilder()
			.clearRequiredFeatures()
			.addAllRequiredFeatures(requiredBy(definition).toList())
			.build();
	}

	/**
	 * Get the names this build supports, for reporting what an index would
	 * need to be opened elsewhere.
	 *
	 * @return
	 */
	public static ListIterable<String> supported() {
		return Lists.immutable.ofAll(SUPPORTED).toSortedList().toImmutable();
	}
}
