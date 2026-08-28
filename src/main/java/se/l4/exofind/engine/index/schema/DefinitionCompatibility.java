package se.l4.exofind.engine.index.schema;

import java.util.Map;
import java.util.Objects;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MapIterable;

import se.l4.exofind.engine.errors.ErrorMessage;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;

/**
 * Tells whether replacing one index definition with another reaches the
 * documents already indexed under it.
 *
 * <p>Most of a definition is read where a search runs, so changing it changes
 * every result at once. The rest is read where a document is written, and
 * changing that reaches nothing already in the index: turning on {@code
 * matching} for a field writes a Lucene field no indexed document has, and a
 * different analysis chain, locale set or synonym set leaves the terms of
 * every earlier document as they were. What a result returns is decided the
 * same way: turning {@code stored} on, or the index starting to keep a copy of
 * its documents, points reads at values the documents already indexed were
 * stored without. Applied in place, such a change makes searches and reads
 * return less than they should with no error anywhere, which is the
 * one failure a caller cannot tell from a correct answer. A generation is how
 * it is rolled out instead - see {@code docs/explanation/generations.md}.
 *
 * <p>What this reports is that difference, per field, so a definition holding
 * one can be refused rather than stored. A generation holding no documents has
 * nothing to be stale, so the comparison is only worth making against one that
 * does; {@link se.l4.exofind.engine.index.Index#updateDefinition} is where that
 * is decided.
 *
 * <p>Three asymmetries are deliberate:
 *
 * <ul>
 * <li>A field the current definition does not have is not compared. No document
 * was indexed with it, so there is nothing for the new field to disagree with.
 * <li>A field the incoming definition drops is not compared. What was written
 * for it stays in the index and nothing reads it any more, which is narrower
 * rather than wrong.
 * <li>Turning a usage <em>off</em> is not reported, for the same reason -
 * only turning one on is, because that is what asks for something the earlier
 * documents were never given.
 * </ul>
 *
 * <p>Analysis is compared resolved rather than as it is written: a chain named
 * through {@code analyzer_ref} is looked up, and the stopword lists and synonym
 * sets it names are compared by their contents. Editing a synonym set is
 * therefore reported as the field that uses it changing, which is what it is -
 * the alternative is a definition that stores cleanly and changes nothing.
 *
 * <p>Every setting that decides what is written is named here, one at a time,
 * rather than derived from the definition. A setting added to the format
 * without a comparison here is therefore accepted over documents that were
 * never given it, which is the failure this class exists to prevent - the rule
 * is in {@code CLAUDE.md} beside the other things nothing checks.
 *
 * <p>Where a comparison is worth making at all, it errs toward refusing. A
 * false refusal is answerable - the caller rolls out a generation, or says
 * outright that the documents may go stale - while a false acceptance is
 * silent.
 */
public class DefinitionCompatibility {
	/**
	 * Sentence every message ends with. The code and the arguments say what
	 * changed; a caller reading only the message still has to be told that the
	 * change has a way through.
	 */
	private static final String ROLLOUT =
		" Roll the change out by creating a generation, filling it and promoting it.";

	private static final ErrorType USAGE_ADDED =
		ErrorType.withCode("index:definition:usage_added")
			.withArguments("field", "usage")
			.withMessage(
				"`{{usage}}` is turned on for field `{{field}}`, which writes something the"
					+ " documents already indexed do not have." + ROLLOUT
			);

	private static final ErrorType ANALYSIS_CHANGED =
		ErrorType.withCode("index:definition:analysis_changed")
			.withArguments("field", "usage")
			.withMessage(
				"How `{{usage}}` reads the values of field `{{field}}` has changed, which"
					+ " leaves the documents already indexed holding the terms the previous"
					+ " analysis gave them." + ROLLOUT
			);

	private static final ErrorType SETTING_CHANGED =
		ErrorType.withCode("index:definition:setting_changed")
			.withArguments("field", "setting")
			.withMessage(
				"`{{setting}}` has changed for field `{{field}}`, and decides what was"
					+ " written for the documents already indexed." + ROLLOUT
			);

	private static final ErrorType LOCALE_FALLBACK_CHANGED =
		ErrorType.withCode("index:definition:locale_fallback_changed")
			.withMessage(
				"The locale fallback of the index has changed, and decides which locales"
					+ " were filled in for the documents already indexed." + ROLLOUT
			);

	private static final ErrorType SOURCE_ADDED =
		ErrorType.withCode("index:definition:source_added")
			.withMessage(
				"The index now keeps a copy of each document, which the documents"
					+ " already indexed were stored without." + ROLLOUT
			);

	private DefinitionCompatibility() {
	}

	/**
	 * Compare a definition about to be stored against the one an index holds
	 * now, reporting every difference the documents already indexed would not
	 * carry.
	 *
	 * <p>An empty result means the change reaches every document, whether it is
	 * already indexed or not.
	 *
	 * @param current
	 *   the definition the documents in the index were indexed under
	 * @param incoming
	 *   the definition about to replace it
	 * @return
	 *   one message per difference, located at the field that carries it
	 */
	public static ListIterable<ErrorMessage> check(IndexDef current, IndexDef incoming) {
		var errors = Lists.mutable.<ErrorMessage>empty();

		checkSource(current, incoming, errors);
		checkLocaleFallback(current, incoming, errors);

		checkFields(
			ObjectLocation.root(),
			"",
			current.getFieldsMap(),
			current.getResources(),
			incoming.getFieldsMap(),
			incoming.getResources(),
			errors
		);

		return errors;
	}

	/**
	 * Compare how much of a document the index keeps. The copy is written when
	 * the document is indexed, so an index starting to keep one points every
	 * read that needs it - a document whole, a field inside an object, a field
	 * not stored on its own - at a copy the documents already indexed never
	 * got. Stopping is narrower rather than wrong: the copies that were written
	 * stand, and a read that needs one is refused outright rather than answered
	 * with less.
	 */
	private static void checkSource(
		IndexDef current,
		IndexDef incoming,
		MutableList<ErrorMessage> errors
	) {
		if(keepsSource(incoming) && !keepsSource(current)) {
			errors.add(
				SOURCE_ADDED.toMessage(ObjectLocation.root().forField("source"))
			);
		}
	}

	/**
	 * Whether a definition keeps the copy, with the default written out the
	 * same way {@link IndexSchema#isSourceStored()} reads it.
	 */
	private static boolean keepsSource(IndexDef def) {
		return def.getSource() != IndexDef.SourceMode.SOURCE_MODE_NONE;
	}

	/**
	 * Compare how the index fills the locales a document holds no value in.
	 * The copy is written when the document is indexed, so turning fallback on
	 * or changing where it takes values from reaches nothing already there.
	 * Turning it off leaves copies that were written standing, which answers
	 * more than the definition asks for rather than less, and is left alone the
	 * same way a usage being turned off is.
	 */
	private static void checkLocaleFallback(
		IndexDef current,
		IndexDef incoming,
		MutableList<ErrorMessage> errors
	) {
		if(!incoming.hasLocaleFallback()) {
			return;
		}

		if(!current.hasLocaleFallback()
			|| !current.getLocaleFallback().getChainList()
				.equals(incoming.getLocaleFallback().getChainList())) {
			errors.add(
				LOCALE_FALLBACK_CHANGED.toMessage(
					ObjectLocation.root().forField("localeFallback")
				)
			);
		}
	}

	/**
	 * Compare the fields two definitions share, by the name each is given to.
	 * The fields inside an object are compared the same way one level down, and
	 * named by the dotted path through it so an error points where a caller
	 * wrote the field.
	 *
	 * @param prefix
	 *   the path the fields sit under, empty at the root
	 */
	private static void checkFields(
		ObjectLocation location,
		String prefix,
		Map<String, FieldDef> current,
		ResourcesDef currentResources,
		Map<String, FieldDef> incoming,
		ResourcesDef incomingResources,
		MutableList<ErrorMessage> errors
	) {
		for(var entry : incoming.entrySet()) {
			var before = current.get(entry.getKey());
			if(before == null) {
				continue;
			}

			var name = prefix.isEmpty() ? entry.getKey() : prefix + '.' + entry.getKey();

			checkField(
				location.forField(entry.getKey()),
				name,
				before,
				currentResources,
				entry.getValue(),
				incomingResources,
				errors
			);
		}
	}

	private static void checkField(
		ObjectLocation location,
		String name,
		FieldDef before,
		ResourcesDef currentResources,
		FieldDef after,
		ResourcesDef incomingResources,
		MutableList<ErrorMessage> errors
	) {
		if(before.getType().getTypeCase() != after.getType().getTypeCase()) {
			/*
			 * Nothing below is comparable across two types, and the type is the
			 * whole of what was written, so this is the only thing worth saying
			 * about the field.
			 */
			errors.add(setting(location, name, "type"));
			return;
		}

		if(before.getPrimaryKey() != after.getPrimaryKey()) {
			// Which field names a document is what its key term was written from
			errors.add(setting(location, name, "primaryKey"));
		}

		if(before.getMultiple() != after.getMultiple()) {
			// Decides whether values were written as one unit or as several
			errors.add(setting(location, name, "multiple"));
		}

		if(after.getStored() && !before.getStored()) {
			/*
			 * The stored value is written with the document, and once the flag
			 * is on a read of the field alone no longer falls back to the copy
			 * of the document - see DocumentReader.sourceNeeded.
			 */
			errors.add(usage(location, name, "stored"));
		}

		checkLocales(location, name, before, after, errors);

		if(after.hasFilter() && !before.hasFilter()) {
			errors.add(usage(location, name, "filter"));
		}

		if(after.hasFacet() && !before.hasFacet()) {
			errors.add(usage(location, name, "facet"));
		}

		if(after.hasSort()) {
			if(!before.hasSort()) {
				errors.add(usage(location, name, "sort"));
			} else if(before.getType().hasString()
				&& collation(before.getSort()) != collation(after.getSort())) {
				/*
				 * Collation keys are written per document, so ordering by other
				 * rules means writing them again. Where documents with no value
				 * end up is decided by the search and is left alone.
				 */
				errors.add(setting(location, name, "sort.collation"));
			}
		}

		switch(after.getType().getTypeCase()) {
			case STRING -> checkString(
				location,
				name,
				before.getType().getString(),
				currentResources,
				after.getType().getString(),
				incomingResources,
				errors
			);
			case VECTOR -> checkVector(
				location,
				name,
				before.getType().getVector(),
				after.getType().getVector(),
				errors
			);
			case OBJECT -> checkObject(
				location,
				name,
				before.getType().getObject(),
				currentResources,
				after.getType().getObject(),
				incomingResources,
				errors
			);
			default -> {
				/*
				 * The remaining types carry only validation, which decides what
				 * a document has to hold to be accepted rather than what is
				 * written for one that was.
				 */
			}
		}
	}

	/**
	 * Compare what locales a field holds values in. A field gaining locales
	 * needs every value written again under the locale it belongs to; one
	 * losing them keeps variants nothing asks for. Only the first is reported,
	 * and so is a locale being added to a field that already had some, because
	 * no document was ever written under it.
	 */
	private static void checkLocales(
		ObjectLocation location,
		String name,
		FieldDef before,
		FieldDef after,
		MutableList<ErrorMessage> errors
	) {
		if(!after.hasLocales()) {
			return;
		}

		if(!before.hasLocales()) {
			errors.add(usage(location, name, "locales"));
			return;
		}

		var currentLocales = before.getLocales();
		var incomingLocales = after.getLocales();

		if(!currentLocales.getDefaultLocale().equals(incomingLocales.getDefaultLocale())) {
			errors.add(setting(location, name, "locales.defaultLocale"));
		}

		if(!currentLocales.getLocalesList().containsAll(incomingLocales.getLocalesList())) {
			errors.add(setting(location, name, "locales.locales"));
		}

		var wasDisabled = currentLocales.getFallback()
			== FieldDef.LocaleConfig.Fallback.FALLBACK_DISABLED;
		var isDisabled = incomingLocales.getFallback()
			== FieldDef.LocaleConfig.Fallback.FALLBACK_DISABLED;

		if(wasDisabled && !isDisabled) {
			errors.add(usage(location, name, "locales.fallback"));
		}
	}

	private static void checkString(
		ObjectLocation location,
		String name,
		StringFieldTypeDef before,
		ResourcesDef currentResources,
		StringFieldTypeDef after,
		ResourcesDef incomingResources,
		MutableList<ErrorMessage> errors
	) {
		if(caseFolding(before) != caseFolding(after)) {
			/*
			 * How a value is normalized before it is compared exactly is part of
			 * the term that was written, so a filter on the index as it is would
			 * meet values folded the other way.
			 */
			errors.add(setting(location, name, "keyword.caseFolding"));
		}

		checkTextUsage(
			location,
			name,
			"matching",
			before.hasMatching() ? before.getMatching() : null,
			currentResources,
			after.hasMatching() ? after.getMatching() : null,
			incomingResources,
			errors
		);

		checkTextUsage(
			location,
			name,
			"autocomplete",
			before.hasAutocomplete() ? before.getAutocomplete() : null,
			currentResources,
			after.hasAutocomplete() ? after.getAutocomplete() : null,
			incomingResources,
			errors
		);

		if(after.hasHierarchy()) {
			if(!before.hasHierarchy()) {
				errors.add(usage(location, name, "hierarchy"));
			} else if(!separator(before.getHierarchy()).equals(separator(after.getHierarchy()))) {
				// Each value was written once per level the old separator found
				errors.add(setting(location, name, "hierarchy.separator"));
			}
		}
	}

	/**
	 * Compare one text usage of a string field. The usage appearing where there
	 * was none writes Lucene fields no document has; the usage staying and
	 * reading values differently leaves every document holding the terms the
	 * previous chain gave it.
	 *
	 * @param before
	 *   the usage as the index has it, or {@code null} when it had none
	 * @param after
	 *   the usage as it is about to be stored, or {@code null} when the
	 *   definition drops it
	 */
	private static void checkTextUsage(
		ObjectLocation location,
		String name,
		String usage,
		StringFieldTypeDef.TextUsageConfig before,
		ResourcesDef currentResources,
		StringFieldTypeDef.TextUsageConfig after,
		ResourcesDef incomingResources,
		MutableList<ErrorMessage> errors
	) {
		if(after == null) {
			return;
		}

		if(before == null) {
			errors.add(usage(location, name, usage));
			return;
		}

		if(after.hasHighlight() && !before.hasHighlight()) {
			// Highlighting reads offsets that are written with the document
			errors.add(usage(location, name, usage + ".highlight"));
		}

		if(after.hasExact() && !before.hasExact()) {
			// The whole value is written a second time as a single term
			errors.add(usage(location, name, usage + ".exact"));
		}

		if(!analysis(before, currentResources).equals(analysis(after, incomingResources))) {
			errors.add(
				ANALYSIS_CHANGED.toMessage(location, "field", name, "usage", usage)
			);
		}
	}

	private static void checkVector(
		ObjectLocation location,
		String name,
		VectorFieldTypeDef before,
		VectorFieldTypeDef after,
		MutableList<ErrorMessage> errors
	) {
		if(before.getDimensions() != after.getDimensions()) {
			errors.add(setting(location, name, "dimensions"));
		}

		if(similarity(before) != similarity(after)) {
			// The metric is what the stored graph was built for
			errors.add(setting(location, name, "similarity"));
		}

		if(hnswM(before) != hnswM(after)
			|| hnswEfConstruction(before) != hnswEfConstruction(after)) {
			errors.add(setting(location, name, "hnsw"));
		}

		if(quantization(before) != quantization(after)) {
			// How much precision the stored vectors gave up is already given up
			errors.add(setting(location, name, "quantization"));
		}
	}

	private static void checkObject(
		ObjectLocation location,
		String name,
		ObjectFieldTypeDef before,
		ResourcesDef currentResources,
		ObjectFieldTypeDef after,
		ResourcesDef incomingResources,
		MutableList<ErrorMessage> errors
	) {
		if(before.getMode() != after.getMode()) {
			// Nested keeps every value as its own Lucene document, flattened does not
			errors.add(setting(location, name, "mode"));
		}

		checkFields(
			location,
			name,
			before.getFieldsMap(),
			currentResources,
			after.getFieldsMap(),
			incomingResources,
			errors
		);
	}

	/**
	 * Everything about a text usage that decides the terms a value is written
	 * as, resolved so that two of these compare equal exactly when they analyze
	 * the same way.
	 *
	 * <p>A chain named through {@code analyzer_ref} is resolved to the chain
	 * itself, and every stopword list and synonym set the chain names is
	 * resolved to its contents, so editing a shared resource compares as the
	 * fields that use it changing. A usage that names no chain is analyzed by
	 * the chain the engine builds, which is decided by the locale of the value
	 * and by {@code decompound}.
	 *
	 * @param chain
	 *   the chain the usage analyzes with, or {@code null} where the engine
	 *   builds one
	 * @param decompound
	 *   how the engine-built chain treats compound words. Carried whether or
	 *   not a chain is named, since a chain says for itself whether it splits
	 * @param stopwords
	 *   contents of the stopword lists the chain names, by name
	 * @param synonyms
	 *   contents of the synonym sets the chain names, by name
	 */
	private record Analysis(
		AnalyzerDef chain,
		StringFieldTypeDef.TextUsageConfig.DecompoundMode decompound,
		MapIterable<String, ResourcesDef.StopwordsResource> stopwords,
		MapIterable<String, ResourcesDef.SynonymsResource> synonyms
	) {
		@Override
		public boolean equals(Object obj) {
			return obj instanceof Analysis other
				&& Objects.equals(chain, other.chain)
				&& decompound == other.decompound
				&& stopwords.equals(other.stopwords)
				&& synonyms.equals(other.synonyms);
		}

		@Override
		public int hashCode() {
			return Objects.hash(chain, decompound, stopwords, synonyms);
		}
	}

	private static Analysis analysis(
		StringFieldTypeDef.TextUsageConfig usage,
		ResourcesDef resources
	) {
		AnalyzerDef chain = null;
		if(usage.hasAnalyzer()) {
			chain = usage.getAnalyzer();
		} else if(usage.hasAnalyzerRef()) {
			/*
			 * A reference to a chain the index does not hold is left as no chain
			 * rather than refused - the definition is validated separately, and
			 * two definitions that both name a missing chain analyze the same
			 * way, which is nothing.
			 */
			chain = resources.getAnalyzersMap().get(usage.getAnalyzerRef());
		}

		var stopwords = Maps.mutable.<String, ResourcesDef.StopwordsResource>empty();
		var synonyms = Maps.mutable.<String, ResourcesDef.SynonymsResource>empty();

		if(chain != null) {
			for(var filter : chain.getFiltersList()) {
				switch(filter.getFilterCase()) {
					case STOPWORDS -> {
						if(filter.getStopwords().hasNamed()) {
							var named = filter.getStopwords().getNamed().getName();
							stopwords.put(
								named,
								resources.getStopwordsMap()
									.getOrDefault(
										named,
										ResourcesDef.StopwordsResource.getDefaultInstance()
									)
							);
						}
					}
					case SYNONYMS -> {
						var named = filter.getSynonyms().getName();
						synonyms.put(
							named,
							resources.getSynonymsMap()
								.getOrDefault(
									named,
									ResourcesDef.SynonymsResource.getDefaultInstance()
								)
						);
					}
					default -> {
						// Nothing else in a chain reaches outside itself
					}
				}
			}
		}

		return new Analysis(chain, usage.getDecompound(), stopwords, synonyms);
	}

	private static ErrorMessage usage(ObjectLocation location, String field, String usage) {
		return USAGE_ADDED.toMessage(location, "field", field, "usage", usage);
	}

	private static ErrorMessage setting(ObjectLocation location, String field, String setting) {
		return SETTING_CHANGED.toMessage(location, "field", field, "setting", setting);
	}

	/**
	 * The collation a sort config orders by, with the default written out so
	 * that saying nothing and saying what the engine would have chosen compare
	 * the same.
	 */
	private static SortConfig.Collation collation(SortConfig sort) {
		return sort.getCollation() == SortConfig.Collation.COLLATION_UNSPECIFIED
			? SortConfig.Collation.COLLATION_LOCALE
			: sort.getCollation();
	}

	private static boolean caseFolding(StringFieldTypeDef def) {
		return !def.getKeyword().hasCaseFolding() || def.getKeyword().getCaseFolding();
	}

	private static String separator(StringFieldTypeDef.HierarchyConfig hierarchy) {
		return hierarchy.hasSeparator() ? hierarchy.getSeparator() : "/";
	}

	/**
	 * The HNSW parameters a vector field's graphs are built with, with the
	 * defaults written out. What they default to is decided where they reach
	 * Lucene - {@code IndexCodec}.
	 */
	private static int hnswM(VectorFieldTypeDef def) {
		return def.getHnsw().hasM() ? def.getHnsw().getM() : 16;
	}

	private static int hnswEfConstruction(VectorFieldTypeDef def) {
		return def.getHnsw().hasEfConstruction()
			? def.getHnsw().getEfConstruction()
			: 100;
	}

	private static VectorFieldTypeDef.SimilarityMetric similarity(VectorFieldTypeDef def) {
		return def.getSimilarity() == VectorFieldTypeDef.SimilarityMetric.SIMILARITY_METRIC_UNSPECIFIED
			? VectorFieldTypeDef.SimilarityMetric.SIMILARITY_METRIC_COSINE
			: def.getSimilarity();
	}

	private static VectorFieldTypeDef.Quantization quantization(VectorFieldTypeDef def) {
		return def.getQuantization() == VectorFieldTypeDef.Quantization.QUANTIZATION_UNSPECIFIED
			? VectorFieldTypeDef.Quantization.QUANTIZATION_NONE
			: def.getQuantization();
	}
}
