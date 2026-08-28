package se.l4.exofind.engine.api.v1alpha1.admin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import se.l4.exofind.engine.api.errors.UnrepresentableStateException;
import se.l4.exofind.engine.api.v1alpha1.admin.model.AnalyzerDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.BooleanFieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.DoubleFieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.FieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.FloatFieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.GeoPointFieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.Int32FieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.Int64FieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.ObjectFieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.StringFieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.TimestampFieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.VectorFieldDefinition;
import se.l4.exofind.engine.errors.EngineException;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.index.schema.AnalyzerDef;
import se.l4.exofind.engine.index.schema.BooleanFieldTypeDef;
import se.l4.exofind.engine.index.schema.CharFilterDef;
import se.l4.exofind.engine.index.schema.DoubleFieldTypeDef;
import se.l4.exofind.engine.index.schema.FacetConfig;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.FloatFieldTypeDef;
import se.l4.exofind.engine.index.schema.GeoPointFieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.Int32FieldTypeDef;
import se.l4.exofind.engine.index.schema.Int64FieldTypeDef;
import se.l4.exofind.engine.index.schema.ObjectFieldTypeDef;
import se.l4.exofind.engine.index.schema.ResourcesDef;
import se.l4.exofind.engine.index.schema.SortConfig;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.schema.TimestampFieldTypeDef;
import se.l4.exofind.engine.index.schema.TokenFilterDef;
import se.l4.exofind.engine.index.schema.TokenizerDef;
import se.l4.exofind.engine.index.schema.VectorFieldTypeDef;

/**
 * Translates between the definitions used by the admin API and the ones the
 * engine stores.
 *
 * The two are kept apart on purpose - the stored definition is a format that
 * has to stay readable for as long as an index exists, while the API is a
 * contract that is versioned and can be reshaped. This class is where the two
 * meet, and the only place that has to change when one of them moves.
 *
 * Values that were not set stay unset in both directions, so the engine
 * decides what the defaults are rather than the API baking them in. The one
 * deliberate exception is analyzer presets: a preset is expanded to the chain
 * it names on the way in, so that what a preset means can never shift under an
 * index that already exists. Reading a definition back therefore shows the
 * chain rather than the preset.
 *
 * {@link #toStored(IndexDefinition)} builds a whole stored definition from what
 * the API model holds, so anything this version can not describe would be
 * dropped by an update that merely sent back what was read.
 * {@link #checkRepresentable(IndexDef)} is what stops that, and it works by
 * comparing a stored definition against itself taken through both directions -
 * which holds only because the same API definition always maps to the same
 * stored one. A mapping that has more than one way to store the same thing
 * breaks the check rather than the round trip.
 */
public class IndexDefinitionMapper {
	/*
	 * Distinct from `index:field:unsupported_type`, which is what the engine
	 * reports for a type it can not index. This one is about the API being
	 * unable to describe a type the engine handles fine.
	 */
	private static final ErrorType UNREPRESENTABLE_TYPE =
		ErrorType.withCode("index:field:unrepresentable_type")
			.withArguments("name", "type")
			.withMessage(
				"Field `{{name}}` has type `{{type}}` which this version of the API can not represent"
			);

	/*
	 * Whole definition rather than a single field: what could not be described
	 * may be a field type, a setting inside one, or a part of the stored format
	 * this version has no name for at all.
	 */
	private static final ErrorType UNREPRESENTABLE_DEFINITION =
		ErrorType.withCode("index:definition:unrepresentable")
			.withMessage(
				"The stored definition holds settings this version of the API can not describe, and updating it here would drop them"
			);

	private static final ErrorType INVALID_ANALYZER =
		ErrorType.withCode("index:field:analyzer:invalid")
			.withArguments("name")
			.withMessage(
				"Field `{{name}}` has an analyzer that is not exactly one of a preset, a custom chain and a named one"
			);

	private static final ErrorType NAMED_CHAIN_IN_RESOURCES =
		ErrorType.withCode("index:resources:analyzers:named")
			.withArguments("name")
			.withMessage(
				"Analysis chain `{{name}}` in the resources can not be `named` - the resources are where names are defined"
			);

	private static final ErrorType INVALID_SYNONYM_RULE =
		ErrorType.withCode("index:resources:synonyms:invalid_rule")
			.withArguments("name")
			.withMessage(
				"Synonym set `{{name}}` has a rule that is not exactly one kind - equivalent words, or a one way mapping"
			);

	private static final ErrorType INVALID_ANALYZER_COMPONENT =
		ErrorType.withCode("index:field:analyzer:invalid_component")
			.withArguments("name")
			.withMessage(
				"Field `{{name}}` has an analysis chain component that is not exactly one kind"
			);

	private static final ErrorType DECOMPOUND_ON_GIVEN_CHAIN =
		ErrorType.withCode("index:field:analyzer:decompound_on_given_chain")
			.withArguments("name")
			.withMessage(
				"Field `{{name}}` sets `decompound` alongside a custom or named chain - a given chain says itself whether it splits, through a `decompound` component"
			);

	private IndexDefinitionMapper() {
	}

	/**
	 * Check that a stored definition can be described by this version of the
	 * API, and refuse it when it can not.
	 *
	 * Call this with the definition an index currently has before replacing it
	 * with one built by {@link #toStored(IndexDefinition)}. A definition written
	 * by a newer version may hold settings this one has no model for, and those
	 * settings would be gone from the replacement - the caller sent back what it
	 * was given and would be told nothing.
	 *
	 * @param stored
	 *   definition as it is stored now
	 * @throws UnrepresentableStateException
	 *   if any part of the definition would be lost by reading it and writing it
	 *   back
	 */
	public static void checkRepresentable(IndexDef stored) {
		IndexDef roundTripped;
		try {
			roundTripped = toStored(toApi(stored));
		} catch(UnrepresentableStateException e) {
			throw e;
		} catch(EngineException e) {
			/*
			 * A combination that is stored but that the API model can not hold
			 * without contradicting itself, such as a chain named alongside a
			 * setting only an expanded one takes. Describing it is the same
			 * failure as not having a name for it.
			 */
			throw new UnrepresentableStateException(UNREPRESENTABLE_DEFINITION, e);
		}

		/*
		 * The features and the highlight layout are the engine's to write
		 * rather than the caller's to lose - the features are worked out from
		 * the rest of the definition every time one is stored, and the layout
		 * is carried from the definition being replaced - so both are carried
		 * over instead of compared. A definition needing a feature this build
		 * does not have never reaches here, as the index carrying it is
		 * refused when it is opened.
		 *
		 * Built up from the definition this version can describe rather than
		 * taken apart from the stored one, because a builder made from a message
		 * holding a value only a newer version has a name for is refused by
		 * protobuf itself.
		 */
		var comparableBuilder = roundTripped.toBuilder()
			.addAllRequiredFeatures(stored.getRequiredFeaturesList());

		if(stored.hasHighlightLayout()) {
			comparableBuilder.setHighlightLayout(stored.getHighlightLayout());
		}

		var comparable = comparableBuilder.build();

		if(!comparable.equals(stored)) {
			throw new UnrepresentableStateException(UNREPRESENTABLE_DEFINITION);
		}
	}

	/**
	 * Convert a definition received over the API into one that can be stored.
	 *
	 * @param definition
	 * @return
	 */
	public static IndexDef toStored(IndexDefinition definition) {
		var builder = IndexDef.newBuilder();

		if(definition.source() != null) {
			builder.setSource(
				switch(definition.source()) {
					case FULL -> IndexDef.SourceMode.SOURCE_MODE_FULL;
					case NONE -> IndexDef.SourceMode.SOURCE_MODE_NONE;
				}
			);
		}

		if(definition.metadata() != null) {
			builder.putAllMetadata(definition.metadata());
		}

		if(definition.fields() != null) {
			for(var entry : definition.fields().entrySet()) {
				builder.putFields(entry.getKey(), toStored(entry.getKey(), entry.getValue()));
			}
		}

		if(definition.resources() != null) {
			/*
			 * Resources holding nothing are left unset rather than stored empty,
			 * because that is how they read back - the two would otherwise be
			 * the same index stored two ways.
			 */
			var resources = toStored(definition.resources());
			if(!resources.equals(ResourcesDef.getDefaultInstance())) {
				builder.setResources(resources);
			}
		}

		if(definition.localeFallback() != null) {
			var fallback = IndexDef.LocaleFallbackConfig.newBuilder();
			if(definition.localeFallback().chain() != null) {
				fallback.addAllChain(definition.localeFallback().chain());
			}
			builder.setLocaleFallback(fallback);
		}

		if(definition.ranking() != null) {
			builder.setRanking(RankingMapper.toStored(definition.ranking()));
		}

		return builder.build();
	}

	private static FieldDef toStored(String name, FieldDefinition field) {
		var builder = FieldDef.newBuilder();

		if(field.primaryKey() != null) {
			builder.setPrimaryKey(field.primaryKey());
		}

		if(field.required() != null) {
			builder.setRequired(field.required());
		}

		if(field.multiple() != null) {
			builder.setMultiple(field.multiple());
		}

		if(field.stored() != null) {
			builder.setStored(field.stored());
		}

		if(field.locales() != null) {
			var locales = FieldDef.LocaleConfig.newBuilder();
			if(field.locales().defaultLocale() != null) {
				locales.setDefaultLocale(field.locales().defaultLocale());
			}
			if(field.locales().locales() != null) {
				locales.addAllLocales(field.locales().locales());
			}
			if(field.locales().fallback() != null) {
				locales.setFallback(
					switch(field.locales().fallback()) {
						case ENABLED -> FieldDef.LocaleConfig.Fallback.FALLBACK_ENABLED;
						case DISABLED -> FieldDef.LocaleConfig.Fallback.FALLBACK_DISABLED;
					}
				);
			}
			builder.setLocales(locales);
		}

		if(field.filter() != null) {
			builder.setFilter(FilterConfig.getDefaultInstance());
		}

		if(field.sort() != null) {
			var sort = SortConfig.newBuilder();
			if(field.sort().collation() != null) {
				sort.setCollation(
					switch(field.sort().collation()) {
						case BINARY -> SortConfig.Collation.COLLATION_BINARY;
						case LOCALE -> SortConfig.Collation.COLLATION_LOCALE;
					}
				);
			}
			if(field.sort().missing() != null) {
				sort.setMissing(
					switch(field.sort().missing()) {
						case FIRST -> SortConfig.Missing.MISSING_FIRST;
						case LAST -> SortConfig.Missing.MISSING_LAST;
					}
				);
			}
			builder.setSort(sort);
		}

		if(field.facet() != null) {
			builder.setFacet(FacetConfig.getDefaultInstance());
		}

		builder.setType(
			switch(field) {
				case StringFieldDefinition string -> FieldTypeDef.newBuilder()
					.setString(toStored(name, string))
					.build();
				case BooleanFieldDefinition ignored -> FieldTypeDef.newBuilder()
					.setBoolean(BooleanFieldTypeDef.getDefaultInstance())
					.build();
				case VectorFieldDefinition vector -> FieldTypeDef.newBuilder()
					.setVector(toStored(vector))
					.build();
				case Int32FieldDefinition int32 -> FieldTypeDef.newBuilder()
					.setInt32(toStored(int32))
					.build();
				case Int64FieldDefinition int64 -> FieldTypeDef.newBuilder()
					.setInt64(toStored(int64))
					.build();
				case FloatFieldDefinition floatField -> FieldTypeDef.newBuilder()
					.setFloat(toStored(floatField))
					.build();
				case DoubleFieldDefinition doubleField -> FieldTypeDef.newBuilder()
					.setDouble(toStored(doubleField))
					.build();
				case TimestampFieldDefinition ignored -> FieldTypeDef.newBuilder()
					.setTimestamp(TimestampFieldTypeDef.getDefaultInstance())
					.build();
				case GeoPointFieldDefinition ignored -> FieldTypeDef.newBuilder()
					.setGeoPoint(GeoPointFieldTypeDef.getDefaultInstance())
					.build();
				case ObjectFieldDefinition object -> FieldTypeDef.newBuilder()
					.setObject(toStored(object))
					.build();
			}
		);

		return builder.build();
	}

	private static ObjectFieldTypeDef toStored(ObjectFieldDefinition field) {
		var builder = ObjectFieldTypeDef.newBuilder();

		if(field.fields() != null) {
			for(var entry : field.fields().entrySet()) {
				builder.putFields(entry.getKey(), toStored(entry.getKey(), entry.getValue()));
			}
		}

		if(field.mode() != null) {
			builder.setMode(
				switch(field.mode()) {
					case NESTED -> ObjectFieldTypeDef.Mode.MODE_NESTED;
					case FLATTENED -> ObjectFieldTypeDef.Mode.MODE_FLATTENED;
				}
			);
		}

		return builder.build();
	}

	private static Int32FieldTypeDef toStored(Int32FieldDefinition field) {
		var builder = Int32FieldTypeDef.newBuilder();

		if(field.validation() != null) {
			var validation = Int32FieldTypeDef.ValidationConfig.newBuilder();
			if(field.validation().min() != null) {
				validation.setMin(field.validation().min());
			}
			if(field.validation().max() != null) {
				validation.setMax(field.validation().max());
			}
			builder.setValidation(validation);
		}

		return builder.build();
	}

	private static Int64FieldTypeDef toStored(Int64FieldDefinition field) {
		var builder = Int64FieldTypeDef.newBuilder();

		if(field.validation() != null) {
			var validation = Int64FieldTypeDef.ValidationConfig.newBuilder();
			if(field.validation().min() != null) {
				validation.setMin(field.validation().min());
			}
			if(field.validation().max() != null) {
				validation.setMax(field.validation().max());
			}
			builder.setValidation(validation);
		}

		return builder.build();
	}

	private static FloatFieldTypeDef toStored(FloatFieldDefinition field) {
		var builder = FloatFieldTypeDef.newBuilder();

		if(field.validation() != null) {
			var validation = FloatFieldTypeDef.ValidationConfig.newBuilder();
			if(field.validation().min() != null) {
				validation.setMin(field.validation().min());
			}
			if(field.validation().max() != null) {
				validation.setMax(field.validation().max());
			}
			builder.setValidation(validation);
		}

		return builder.build();
	}

	private static DoubleFieldTypeDef toStored(DoubleFieldDefinition field) {
		var builder = DoubleFieldTypeDef.newBuilder();

		if(field.validation() != null) {
			var validation = DoubleFieldTypeDef.ValidationConfig.newBuilder();
			if(field.validation().min() != null) {
				validation.setMin(field.validation().min());
			}
			if(field.validation().max() != null) {
				validation.setMax(field.validation().max());
			}
			builder.setValidation(validation);
		}

		return builder.build();
	}

	private static VectorFieldTypeDef toStored(VectorFieldDefinition field) {
		var builder = VectorFieldTypeDef.newBuilder();

		if(field.dimensions() != null) {
			builder.setDimensions(field.dimensions());
		}

		if(field.similarity() != null) {
			builder.setSimilarity(
				switch(field.similarity()) {
					case COSINE -> VectorFieldTypeDef.SimilarityMetric.SIMILARITY_METRIC_COSINE;
					case DOT_PRODUCT ->
						VectorFieldTypeDef.SimilarityMetric.SIMILARITY_METRIC_DOT_PRODUCT;
					case EUCLIDEAN ->
						VectorFieldTypeDef.SimilarityMetric.SIMILARITY_METRIC_EUCLIDEAN;
				}
			);
		}

		if(field.hnsw() != null) {
			var hnsw = VectorFieldTypeDef.HNSWConfig.newBuilder();
			if(field.hnsw().m() != null) {
				hnsw.setM(field.hnsw().m());
			}
			if(field.hnsw().efConstruction() != null) {
				hnsw.setEfConstruction(field.hnsw().efConstruction());
			}
			builder.setHnsw(hnsw);
		}

		if(field.quantization() != null) {
			builder.setQuantization(
				switch(field.quantization()) {
					case NONE -> VectorFieldTypeDef.Quantization.QUANTIZATION_NONE;
					case INT8 -> VectorFieldTypeDef.Quantization.QUANTIZATION_INT8;
					case INT4 -> VectorFieldTypeDef.Quantization.QUANTIZATION_INT4;
				}
			);
		}

		return builder.build();
	}

	private static StringFieldTypeDef toStored(String name, StringFieldDefinition field) {
		var builder = StringFieldTypeDef.newBuilder();

		if(field.keyword() != null) {
			var keyword = StringFieldTypeDef.KeywordConfig.newBuilder();
			if(field.keyword().caseFolding() != null) {
				keyword.setCaseFolding(field.keyword().caseFolding());
			}
			builder.setKeyword(keyword);
		}

		if(field.matching() != null) {
			builder.setMatching(toStored(name, field.matching()));
		}

		if(field.autocomplete() != null) {
			builder.setAutocomplete(toStored(name, field.autocomplete()));
		}

		if(field.hierarchy() != null) {
			var hierarchy = StringFieldTypeDef.HierarchyConfig.newBuilder();
			if(field.hierarchy().separator() != null) {
				hierarchy.setSeparator(field.hierarchy().separator());
			}
			builder.setHierarchy(hierarchy);
		}

		return builder.build();
	}

	private static StringFieldTypeDef.TextUsageConfig toStored(
		String name,
		StringFieldDefinition.TextUsage usage
	) {
		var builder = StringFieldTypeDef.TextUsageConfig.newBuilder();

		var decompound = usage.decompound() != StringFieldDefinition.TextUsage.Decompound.NONE;

		if(usage.analyzer() != null) {
			if(usage.decompound() != null && usage.analyzer().preset() == null) {
				throw new EngineException(DECOMPOUND_ON_GIVEN_CHAIN, "name", name);
			}

			/*
			 * A named analyzer is a reference into the resources rather than a
			 * chain of its own, so it is stored as one.
			 */
			if(usage.analyzer().named() != null) {
				if(usage.analyzer().preset() != null || usage.analyzer().custom() != null) {
					throw new EngineException(INVALID_ANALYZER, "name", name);
				}
				builder.setAnalyzerRef(usage.analyzer().named());
			} else {
				/*
				 * A preset expands honouring the setting, so it is folded into
				 * the stored chain rather than stored beside it.
				 */
				builder.setAnalyzer(toStored(name, usage.analyzer(), decompound));
			}
		} else if(!decompound) {
			builder.setDecompound(
				StringFieldTypeDef.TextUsageConfig.DecompoundMode.DECOMPOUND_MODE_NONE
			);
		}

		if(usage.weight() != null) {
			builder.setWeight(usage.weight());
		}

		if(usage.highlight() != null) {
			builder.setHighlight(
				StringFieldTypeDef.TextUsageConfig.HighlightConfig.getDefaultInstance()
			);
		}

		if(usage.typoTolerance() != null) {
			var typoTolerance = StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig
				.newBuilder();
			if(usage.typoTolerance().minLengthOneTypo() != null) {
				typoTolerance.setMinLengthOneTypo(usage.typoTolerance().minLengthOneTypo());
			}
			if(usage.typoTolerance().minLengthTwoTypos() != null) {
				typoTolerance.setMinLengthTwoTypos(usage.typoTolerance().minLengthTwoTypos());
			}
			if(usage.typoTolerance().prefixLength() != null) {
				typoTolerance.setPrefixLength(usage.typoTolerance().prefixLength());
			}
			if(usage.typoTolerance().numbers() != null) {
				typoTolerance.setNumbers(
					StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig.NumbersConfig
						.getDefaultInstance()
				);
			}
			builder.setTypoTolerance(typoTolerance);
		}

		if(usage.exact() != null) {
			var exact = StringFieldTypeDef.TextUsageConfig.ExactConfig.newBuilder();
			if(usage.exact().boost() != null) {
				exact.setBoost(usage.exact().boost());
			}
			builder.setExact(exact);
		}

		if(usage.lengthNormalization() != null) {
			builder.setLengthNormalization(switch(usage.lengthNormalization()) {
				case NONE ->
					StringFieldTypeDef.TextUsageConfig.LengthNormalization
						.LENGTH_NORMALIZATION_NONE;
				case MODERATE ->
					StringFieldTypeDef.TextUsageConfig.LengthNormalization
						.LENGTH_NORMALIZATION_MODERATE;
				case STRONG ->
					StringFieldTypeDef.TextUsageConfig.LengthNormalization
						.LENGTH_NORMALIZATION_STRONG;
			});
		}

		return builder.build();
	}

	/**
	 * Convert an analyzer into the chain that is stored. A preset expands to
	 * the chain it names, so the stored definition is always explicit;
	 * {@code decompound} is whether an expansion may split compounds.
	 */
	private static AnalyzerDef toStored(
		String name,
		AnalyzerDefinition analyzer,
		boolean decompound
	) {
		if((analyzer.preset() != null) == (analyzer.custom() != null)) {
			throw new EngineException(INVALID_ANALYZER, "name", name);
		}

		if(analyzer.preset() != null) {
			return switch(analyzer.preset()) {
				case PRESERVE_TERMS -> AnalyzerDef.newBuilder()
					.addFilters(
						TokenFilterDef.newBuilder()
							.setNormalize(TokenFilterDef.Normalize.getDefaultInstance())
					)
					.build();
				case FULL_TEXT -> {
					var fullText = AnalyzerDef.newBuilder()
						.addFilters(
							TokenFilterDef.newBuilder()
								.setNormalize(TokenFilterDef.Normalize.getDefaultInstance())
						)
						.addFilters(
							TokenFilterDef.newBuilder()
								.setStopwords(TokenFilterDef.Stopwords.getDefaultInstance())
						);

					/*
					 * After stopwords, so the parts of a function word never
					 * reach the index - the same order the engine-built
					 * matching chain uses.
					 */
					if(decompound) {
						fullText.addFilters(
							TokenFilterDef.newBuilder()
								.setDecompound(TokenFilterDef.Decompound.getDefaultInstance())
						);
					}

					yield fullText
						.addFilters(
							TokenFilterDef.newBuilder()
								.setStemming(TokenFilterDef.Stemming.getDefaultInstance())
						)
						.build();
				}
			};
		}

		var custom = analyzer.custom();
		var builder = AnalyzerDef.newBuilder();

		if(custom.charFilters() != null) {
			for(var filter : custom.charFilters()) {
				builder.addCharFilters(toStored(name, filter));
			}
		}

		if(custom.tokenizer() != null) {
			builder.setTokenizer(toStored(name, custom.tokenizer()));
		}

		if(custom.filters() != null) {
			for(var filter : custom.filters()) {
				builder.addFilters(toStored(name, filter));
			}
		}

		return builder.build();
	}

	/**
	 * Convert the resources of an index. The chains here are where names are
	 * defined, so a chain can not itself be {@code named}; presets expand the
	 * same way they do on a field.
	 */
	private static ResourcesDef toStored(IndexDefinition.Resources resources) {
		var builder = ResourcesDef.newBuilder();

		if(resources.analyzers() != null) {
			for(var entry : resources.analyzers().entrySet()) {
				if(entry.getValue().named() != null) {
					throw new EngineException(NAMED_CHAIN_IN_RESOURCES, "name", entry.getKey());
				}
				builder.putAnalyzers(
					entry.getKey(),
					toStored(entry.getKey(), entry.getValue(), true)
				);
			}
		}

		if(resources.stopwords() != null) {
			for(var entry : resources.stopwords().entrySet()) {
				builder.putStopwords(
					entry.getKey(),
					ResourcesDef.StopwordsResource.newBuilder()
						.addAllWords(entry.getValue())
						.build()
				);
			}
		}

		if(resources.synonyms() != null) {
			for(var entry : resources.synonyms().entrySet()) {
				builder.putSynonyms(entry.getKey(), toStored(entry.getKey(), entry.getValue()));
			}
		}

		return builder.build();
	}

	private static ResourcesDef.SynonymsResource toStored(
		String name,
		IndexDefinition.Resources.Synonyms synonyms
	) {
		var builder = ResourcesDef.SynonymsResource.newBuilder();

		if(synonyms.rules() != null) {
			for(var rule : synonyms.rules()) {
				if(countGiven(rule.equivalent(), rule.mapping()) != 1) {
					throw new EngineException(INVALID_SYNONYM_RULE, "name", name);
				}

				var stored = ResourcesDef.SynonymsResource.Rule.newBuilder();
				if(rule.equivalent() != null) {
					stored.setEquivalent(
						ResourcesDef.SynonymsResource.Rule.Equivalent.newBuilder()
							.addAllTerms(rule.equivalent())
					);
				}
				if(rule.mapping() != null) {
					var mapping = ResourcesDef.SynonymsResource.Rule.Mapping.newBuilder();
					if(rule.mapping().from() != null) {
						mapping.addAllFrom(rule.mapping().from());
					}
					if(rule.mapping().to() != null) {
						mapping.addAllTo(rule.mapping().to());
					}
					stored.setMapping(mapping);
				}

				builder.addRules(stored);
			}
		}

		return builder.build();
	}

	private static TokenizerDef toStored(String name, AnalyzerDefinition.Tokenizer tokenizer) {
		var builder = TokenizerDef.newBuilder();

		if(tokenizer.icu() != null) {
			builder.setIcu(TokenizerDef.IcuTokenizer.getDefaultInstance());
		}
		if(tokenizer.whitespace() != null) {
			builder.setWhitespace(TokenizerDef.WhitespaceTokenizer.getDefaultInstance());
		}
		if(tokenizer.keyword() != null) {
			builder.setKeyword(TokenizerDef.KeywordTokenizer.getDefaultInstance());
		}
		if(tokenizer.letter() != null) {
			builder.setLetter(TokenizerDef.LetterTokenizer.getDefaultInstance());
		}

		if(countGiven(tokenizer.icu(), tokenizer.whitespace(), tokenizer.keyword(), tokenizer.letter()) != 1) {
			throw new EngineException(INVALID_ANALYZER_COMPONENT, "name", name);
		}

		return builder.build();
	}

	private static CharFilterDef toStored(String name, AnalyzerDefinition.CharFilter filter) {
		if(countGiven(filter.htmlStrip(), filter.mapping(), filter.patternReplace()) != 1) {
			throw new EngineException(INVALID_ANALYZER_COMPONENT, "name", name);
		}

		var builder = CharFilterDef.newBuilder();

		if(filter.htmlStrip() != null) {
			builder.setHtmlStrip(CharFilterDef.HtmlStrip.getDefaultInstance());
		}

		if(filter.mapping() != null) {
			var mapping = CharFilterDef.Mapping.newBuilder();
			if(filter.mapping().mappings() != null) {
				mapping.putAllMappings(filter.mapping().mappings());
			}
			builder.setMapping(mapping);
		}

		if(filter.patternReplace() != null) {
			var patternReplace = CharFilterDef.PatternReplace.newBuilder();
			if(filter.patternReplace().pattern() != null) {
				patternReplace.setPattern(filter.patternReplace().pattern());
			}
			if(filter.patternReplace().replacement() != null) {
				patternReplace.setReplacement(filter.patternReplace().replacement());
			}
			builder.setPatternReplace(patternReplace);
		}

		return builder.build();
	}

	private static TokenFilterDef toStored(String name, AnalyzerDefinition.TokenFilter filter) {
		if(countGiven(
			filter.normalize(),
			filter.stopwords(),
			filter.stemming(),
			filter.asciiFolding(),
			filter.edgeNgram(),
			filter.ngram(),
			filter.synonyms(),
			filter.decompound()
		) != 1) {
			throw new EngineException(INVALID_ANALYZER_COMPONENT, "name", name);
		}

		var builder = TokenFilterDef.newBuilder();

		if(filter.normalize() != null) {
			var normalize = TokenFilterDef.Normalize.newBuilder();
			if(filter.normalize().caseFolding() != null) {
				normalize.setCaseFolding(filter.normalize().caseFolding());
			}
			builder.setNormalize(normalize);
		}

		if(filter.stopwords() != null) {
			builder.setStopwords(toStored(name, filter.stopwords()));
		}

		if(filter.stemming() != null) {
			var stemming = TokenFilterDef.Stemming.newBuilder();
			if(filter.stemming().locale() != null) {
				stemming.setLocale(filter.stemming().locale());
			}
			builder.setStemming(stemming);
		}

		if(filter.asciiFolding() != null) {
			var asciiFolding = TokenFilterDef.AsciiFolding.newBuilder();
			if(filter.asciiFolding().preserveOriginal() != null) {
				asciiFolding.setPreserveOriginal(filter.asciiFolding().preserveOriginal());
			}
			builder.setAsciiFolding(asciiFolding);
		}

		if(filter.edgeNgram() != null) {
			var edgeNgram = TokenFilterDef.EdgeNgram.newBuilder();
			if(filter.edgeNgram().minGram() != null) {
				edgeNgram.setMinGram(filter.edgeNgram().minGram());
			}
			if(filter.edgeNgram().maxGram() != null) {
				edgeNgram.setMaxGram(filter.edgeNgram().maxGram());
			}
			builder.setEdgeNgram(edgeNgram);
		}

		if(filter.ngram() != null) {
			var ngram = TokenFilterDef.Ngram.newBuilder();
			if(filter.ngram().minGram() != null) {
				ngram.setMinGram(filter.ngram().minGram());
			}
			if(filter.ngram().maxGram() != null) {
				ngram.setMaxGram(filter.ngram().maxGram());
			}
			builder.setNgram(ngram);
		}

		if(filter.synonyms() != null) {
			var synonyms = TokenFilterDef.Synonyms.newBuilder();
			if(filter.synonyms().named() != null) {
				synonyms.setName(filter.synonyms().named());
			}
			builder.setSynonyms(synonyms);
		}

		if(filter.decompound() != null) {
			var decompound = TokenFilterDef.Decompound.newBuilder();
			if(filter.decompound().locale() != null) {
				decompound.setLocale(filter.decompound().locale());
			}
			builder.setDecompound(decompound);
		}

		return builder.build();
	}

	private static TokenFilterDef.Stopwords toStored(
		String name,
		AnalyzerDefinition.TokenFilter.Stopwords stopwords
	) {
		if(countGiven(stopwords.locale(), stopwords.words(), stopwords.named()) > 1) {
			throw new EngineException(INVALID_ANALYZER_COMPONENT, "name", name);
		}

		var builder = TokenFilterDef.Stopwords.newBuilder();

		if(stopwords.locale() != null) {
			builder.setLocale(
				TokenFilterDef.Stopwords.LocaleWords.newBuilder()
					.setLocale(stopwords.locale())
			);
		}

		if(stopwords.words() != null) {
			builder.setCustom(
				TokenFilterDef.Stopwords.CustomWords.newBuilder()
					.addAllWords(stopwords.words())
			);
		}

		if(stopwords.named() != null) {
			builder.setNamed(
				TokenFilterDef.Stopwords.NamedWords.newBuilder()
					.setName(stopwords.named())
			);
		}

		return builder.build();
	}

	/**
	 * Convert a ranking signal into the form it is stored as.
	 *
	 * Which shape a signal has is what the stored form keeps in a oneof, so
	 * being given two of them is refused here rather than quietly stored as
	 * whichever was written last.
	 *
	 * @param signal
	 * @return
	 */
	private static int countGiven(Object... values) {
		var count = 0;
		for(var value : values) {
			if(value != null) {
				count++;
			}
		}
		return count;
	}

	/**
	 * Convert a stored definition into the form used by the API.
	 *
	 * @param definition
	 * @return
	 * @throws UnrepresentableStateException
	 *   if the definition holds a field type this version of the API has no
	 *   model for
	 */
	public static IndexDefinition toApi(IndexDef definition) {
		Map<String, String> metadata = null;
		if(!definition.getMetadataMap().isEmpty()) {
			metadata = new LinkedHashMap<>(definition.getMetadataMap());
		}

		// Sorted so that the same definition always renders the same way
		var fields = new TreeMap<String, FieldDefinition>();
		for(var entry : definition.getFieldsMap().entrySet()) {
			fields.put(entry.getKey(), toApi(entry.getKey(), entry.getValue()));
		}

		IndexDefinition.Ranking ranking = null;
		if(definition.hasRanking()) {
			ranking = RankingMapper.toApi(definition.getRanking());
		}

		IndexDefinition.LocaleFallback localeFallback = null;
		if(definition.hasLocaleFallback()) {
			var chain = definition.getLocaleFallback().getChainList();
			localeFallback = new IndexDefinition.LocaleFallback(
				chain.isEmpty() ? null : List.copyOf(chain)
			);
		}

		return new IndexDefinition(
			definition.hasSource() ? toApi(definition.getSource()) : null,
			metadata,
			fields,
			ranking,
			toApi(definition.getResources()),
			localeFallback
		);
	}

	/**
	 * Convert stored resources back to the API, leaving out what holds
	 * nothing so an index without resources reads without them.
	 */
	private static IndexDefinition.Resources toApi(ResourcesDef resources) {
		Map<String, AnalyzerDefinition> analyzers = null;
		if(!resources.getAnalyzersMap().isEmpty()) {
			analyzers = new TreeMap<>();
			for(var entry : resources.getAnalyzersMap().entrySet()) {
				analyzers.put(entry.getKey(), toApi(entry.getValue()));
			}
		}

		Map<String, List<String>> stopwords = null;
		if(!resources.getStopwordsMap().isEmpty()) {
			stopwords = new TreeMap<>();
			for(var entry : resources.getStopwordsMap().entrySet()) {
				stopwords.put(entry.getKey(), List.copyOf(entry.getValue().getWordsList()));
			}
		}

		Map<String, IndexDefinition.Resources.Synonyms> synonyms = null;
		if(!resources.getSynonymsMap().isEmpty()) {
			synonyms = new TreeMap<>();
			for(var entry : resources.getSynonymsMap().entrySet()) {
				synonyms.put(entry.getKey(), toApi(entry.getValue()));
			}
		}

		if(analyzers == null && stopwords == null && synonyms == null) {
			return null;
		}

		return new IndexDefinition.Resources(analyzers, stopwords, synonyms);
	}

	private static IndexDefinition.Resources.Synonyms toApi(
		ResourcesDef.SynonymsResource resource
	) {
		var rules = new ArrayList<IndexDefinition.Resources.Synonyms.Rule>();

		for(var rule : resource.getRulesList()) {
			List<String> equivalent = null;
			if(rule.hasEquivalent()) {
				equivalent = List.copyOf(rule.getEquivalent().getTermsList());
			}

			IndexDefinition.Resources.Synonyms.Rule.Mapping mapping = null;
			if(rule.hasMapping()) {
				mapping = new IndexDefinition.Resources.Synonyms.Rule.Mapping(
					List.copyOf(rule.getMapping().getFromList()),
					List.copyOf(rule.getMapping().getToList())
				);
			}

			rules.add(new IndexDefinition.Resources.Synonyms.Rule(equivalent, mapping));
		}

		return new IndexDefinition.Resources.Synonyms(rules);
	}

	/**
	 * Convert whether a field takes part in the locale fallback of its index,
	 * treating a setting this version does not know as unset so that the rest
	 * of the definition still reads.
	 *
	 * @param fallback
	 * @return
	 */
	private static FieldDefinition.Locales.Fallback toApi(
		FieldDef.LocaleConfig.Fallback fallback
	) {
		return switch(fallback) {
			case FALLBACK_ENABLED -> FieldDefinition.Locales.Fallback.ENABLED;
			case FALLBACK_DISABLED -> FieldDefinition.Locales.Fallback.DISABLED;
			default -> null;
		};
	}

	/**
	 * Convert a stored source mode, treating one this version does not know as
	 * unset so that the rest of the definition still reads.
	 *
	 * @param source
	 * @return
	 */
	private static IndexDefinition.Source toApi(IndexDef.SourceMode source) {
		return switch(source) {
			case SOURCE_MODE_FULL -> IndexDefinition.Source.FULL;
			case SOURCE_MODE_NONE -> IndexDefinition.Source.NONE;
			default -> null;
		};
	}

	private static FieldDefinition toApi(String name, FieldDef field) {
		var primaryKey = field.hasPrimaryKey() ? field.getPrimaryKey() : null;
		var required = field.hasRequired() ? field.getRequired() : null;
		var multiple = field.hasMultiple() ? field.getMultiple() : null;
		var stored = field.hasStored() ? field.getStored() : null;

		FieldDefinition.Locales locales = null;
		if(field.hasLocales()) {
			var config = field.getLocales();
			locales = new FieldDefinition.Locales(
				config.hasDefaultLocale() ? config.getDefaultLocale() : null,
				config.getLocalesCount() > 0 ? List.copyOf(config.getLocalesList()) : null,
				toApi(config.getFallback())
			);
		}

		FieldDefinition.Filter filter = field.hasFilter() ? new FieldDefinition.Filter() : null;

		FieldDefinition.Sort sort = null;
		if(field.hasSort()) {
			var config = field.getSort();
			sort = new FieldDefinition.Sort(
				config.hasCollation() ? toApi(config.getCollation()) : null,
				config.hasMissing() ? toApi(config.getMissing()) : null
			);
		}

		FieldDefinition.Facet facet = field.hasFacet() ? new FieldDefinition.Facet() : null;

		var type = field.getType();
		return switch(type.getTypeCase()) {
			case STRING -> {
				var string = type.getString();

				StringFieldDefinition.Keyword keyword = null;
				if(string.hasKeyword()) {
					var config = string.getKeyword();
					keyword = new StringFieldDefinition.Keyword(
						config.hasCaseFolding() ? config.getCaseFolding() : null
					);
				}

				StringFieldDefinition.Hierarchy hierarchy = null;
				if(string.hasHierarchy()) {
					var config = string.getHierarchy();
					hierarchy = new StringFieldDefinition.Hierarchy(
						config.hasSeparator() ? config.getSeparator() : null
					);
				}

				yield new StringFieldDefinition(
					primaryKey,
					required,
					multiple,
					stored,
					locales,
					filter,
					sort,
					facet,
					keyword,
					string.hasMatching() ? toApi(string.getMatching()) : null,
					string.hasAutocomplete() ? toApi(string.getAutocomplete()) : null,
					hierarchy
				);
			}
			case BOOLEAN -> new BooleanFieldDefinition(
				primaryKey,
				required,
				multiple,
				stored,
				locales,
				filter,
				sort,
				facet
			);
			case TIMESTAMP -> new TimestampFieldDefinition(
				primaryKey,
				required,
				multiple,
				stored,
				locales,
				filter,
				sort,
				facet
			);
			case GEO_POINT -> new GeoPointFieldDefinition(
				primaryKey,
				required,
				multiple,
				stored,
				locales,
				filter,
				sort,
				facet
			);
			case INT32 -> {
				var int32 = type.getInt32();

				Int32FieldDefinition.Validation validation = null;
				if(int32.hasValidation()) {
					var config = int32.getValidation();
					validation = new Int32FieldDefinition.Validation(
						config.hasMin() ? config.getMin() : null,
						config.hasMax() ? config.getMax() : null
					);
				}

				yield new Int32FieldDefinition(
					primaryKey,
					required,
					multiple,
					stored,
					locales,
					filter,
					sort,
					facet,
					validation
				);
			}
			case INT64 -> {
				var int64 = type.getInt64();

				Int64FieldDefinition.Validation validation = null;
				if(int64.hasValidation()) {
					var config = int64.getValidation();
					validation = new Int64FieldDefinition.Validation(
						config.hasMin() ? config.getMin() : null,
						config.hasMax() ? config.getMax() : null
					);
				}

				yield new Int64FieldDefinition(
					primaryKey,
					required,
					multiple,
					stored,
					locales,
					filter,
					sort,
					facet,
					validation
				);
			}
			case FLOAT -> {
				var floatType = type.getFloat();

				FloatFieldDefinition.Validation validation = null;
				if(floatType.hasValidation()) {
					var config = floatType.getValidation();
					validation = new FloatFieldDefinition.Validation(
						config.hasMin() ? config.getMin() : null,
						config.hasMax() ? config.getMax() : null
					);
				}

				yield new FloatFieldDefinition(
					primaryKey,
					required,
					multiple,
					stored,
					locales,
					filter,
					sort,
					facet,
					validation
				);
			}
			case DOUBLE -> {
				var doubleType = type.getDouble();

				DoubleFieldDefinition.Validation validation = null;
				if(doubleType.hasValidation()) {
					var config = doubleType.getValidation();
					validation = new DoubleFieldDefinition.Validation(
						config.hasMin() ? config.getMin() : null,
						config.hasMax() ? config.getMax() : null
					);
				}

				yield new DoubleFieldDefinition(
					primaryKey,
					required,
					multiple,
					stored,
					locales,
					filter,
					sort,
					facet,
					validation
				);
			}
			case VECTOR -> {
				var vector = type.getVector();

				VectorFieldDefinition.Hnsw hnsw = null;
				if(vector.hasHnsw()) {
					var config = vector.getHnsw();
					hnsw = new VectorFieldDefinition.Hnsw(
						config.hasM() ? config.getM() : null,
						config.hasEfConstruction() ? config.getEfConstruction() : null
					);
				}

				yield new VectorFieldDefinition(
					primaryKey,
					required,
					multiple,
					stored,
					locales,
					filter,
					sort,
					facet,
					vector.hasDimensions() ? vector.getDimensions() : null,
					vector.hasSimilarity() ? toApi(vector.getSimilarity()) : null,
					hnsw,
					vector.hasQuantization() ? toApi(vector.getQuantization()) : null
				);
			}
			case OBJECT -> {
				var fields = new TreeMap<String, FieldDefinition>();
				for(var entry : type.getObject().getFieldsMap().entrySet()) {
					fields.put(entry.getKey(), toApi(entry.getKey(), entry.getValue()));
				}

				ObjectFieldDefinition.Mode mode = null;
				if(type.getObject().hasMode()) {
					mode = switch(type.getObject().getMode()) {
						case MODE_NESTED -> ObjectFieldDefinition.Mode.NESTED;
						case MODE_FLATTENED -> ObjectFieldDefinition.Mode.FLATTENED;
						default -> null;
					};
				}

				yield new ObjectFieldDefinition(
					primaryKey,
					required,
					multiple,
					stored,
					locales,
					filter,
					sort,
					facet,
					mode,
					fields
				);
			}
			default -> throw new UnrepresentableStateException(
				UNREPRESENTABLE_TYPE,
				"name", name,
				"type", type.getTypeCase()
			);
		};
	}

	private static StringFieldDefinition.TextUsage toApi(
		StringFieldTypeDef.TextUsageConfig usage
	) {
		StringFieldDefinition.TextUsage.TypoTolerance typoTolerance = null;
		if(usage.hasTypoTolerance()) {
			var typos = usage.getTypoTolerance();
			typoTolerance = new StringFieldDefinition.TextUsage.TypoTolerance(
				typos.hasMinLengthOneTypo() ? typos.getMinLengthOneTypo() : null,
				typos.hasMinLengthTwoTypos() ? typos.getMinLengthTwoTypos() : null,
				typos.hasPrefixLength() ? typos.getPrefixLength() : null,
				typos.hasNumbers()
					? new StringFieldDefinition.TextUsage.TypoTolerance.Numbers()
					: null
			);
		}

		AnalyzerDefinition analyzer = null;
		if(usage.hasAnalyzer()) {
			analyzer = toApi(usage.getAnalyzer());
		} else if(usage.hasAnalyzerRef()) {
			analyzer = new AnalyzerDefinition(null, null, usage.getAnalyzerRef());
		}

		StringFieldDefinition.TextUsage.Exact exact = null;
		if(usage.hasExact()) {
			exact = new StringFieldDefinition.TextUsage.Exact(
				usage.getExact().hasBoost() ? usage.getExact().getBoost() : null
			);
		}

		StringFieldDefinition.TextUsage.LengthNormalization lengthNormalization = null;
		if(usage.hasLengthNormalization()) {
			lengthNormalization = switch(usage.getLengthNormalization()) {
				case LENGTH_NORMALIZATION_NONE ->
					StringFieldDefinition.TextUsage.LengthNormalization.NONE;
				case LENGTH_NORMALIZATION_MODERATE ->
					StringFieldDefinition.TextUsage.LengthNormalization.MODERATE;
				case LENGTH_NORMALIZATION_STRONG ->
					StringFieldDefinition.TextUsage.LengthNormalization.STRONG;
				/*
				 * A value a newer version wrote, which this one has no name
				 * for. Read as the engine deciding rather than refused - the
				 * definition is only ever read here after its required
				 * features were found to be ones this build has.
				 */
				default -> null;
			};
		}

		return new StringFieldDefinition.TextUsage(
			analyzer,
			usage.hasWeight() ? usage.getWeight() : null,
			usage.hasHighlight() ? new StringFieldDefinition.TextUsage.Highlight() : null,
			typoTolerance,
			usage.getDecompound()
				== StringFieldTypeDef.TextUsageConfig.DecompoundMode.DECOMPOUND_MODE_NONE
					? StringFieldDefinition.TextUsage.Decompound.NONE
					: null,
			exact,
			lengthNormalization
		);
	}

	/**
	 * Convert a stored chain back to the API, always as a custom chain - the
	 * preset a caller may have sent was expanded on the way in.
	 */
	private static AnalyzerDefinition toApi(AnalyzerDef analyzer) {
		List<AnalyzerDefinition.CharFilter> charFilters = null;
		if(analyzer.getCharFiltersCount() > 0) {
			charFilters = new ArrayList<>();
			for(var filter : analyzer.getCharFiltersList()) {
				charFilters.add(toApi(filter));
			}
		}

		AnalyzerDefinition.Tokenizer tokenizer = null;
		if(analyzer.hasTokenizer()) {
			tokenizer = toApi(analyzer.getTokenizer());
		}

		List<AnalyzerDefinition.TokenFilter> filters = null;
		if(analyzer.getFiltersCount() > 0) {
			filters = new ArrayList<>();
			for(var filter : analyzer.getFiltersList()) {
				filters.add(toApi(filter));
			}
		}

		return new AnalyzerDefinition(
			null,
			new AnalyzerDefinition.Custom(charFilters, tokenizer, filters),
			null
		);
	}

	private static AnalyzerDefinition.Tokenizer toApi(TokenizerDef tokenizer) {
		return new AnalyzerDefinition.Tokenizer(
			tokenizer.hasIcu() ? new AnalyzerDefinition.Tokenizer.Icu() : null,
			tokenizer.hasWhitespace() ? new AnalyzerDefinition.Tokenizer.Whitespace() : null,
			tokenizer.hasKeyword() ? new AnalyzerDefinition.Tokenizer.Keyword() : null,
			tokenizer.hasLetter() ? new AnalyzerDefinition.Tokenizer.Letter() : null
		);
	}

	private static AnalyzerDefinition.CharFilter toApi(CharFilterDef filter) {
		AnalyzerDefinition.CharFilter.Mapping mapping = null;
		if(filter.hasMapping()) {
			mapping = new AnalyzerDefinition.CharFilter.Mapping(
				new LinkedHashMap<>(filter.getMapping().getMappingsMap())
			);
		}

		AnalyzerDefinition.CharFilter.PatternReplace patternReplace = null;
		if(filter.hasPatternReplace()) {
			var config = filter.getPatternReplace();
			patternReplace = new AnalyzerDefinition.CharFilter.PatternReplace(
				config.hasPattern() ? config.getPattern() : null,
				config.hasReplacement() ? config.getReplacement() : null
			);
		}

		return new AnalyzerDefinition.CharFilter(
			filter.hasHtmlStrip() ? new AnalyzerDefinition.CharFilter.HtmlStrip() : null,
			mapping,
			patternReplace
		);
	}

	private static AnalyzerDefinition.TokenFilter toApi(TokenFilterDef filter) {
		AnalyzerDefinition.TokenFilter.Normalize normalize = null;
		if(filter.hasNormalize()) {
			var config = filter.getNormalize();
			normalize = new AnalyzerDefinition.TokenFilter.Normalize(
				config.hasCaseFolding() ? config.getCaseFolding() : null
			);
		}

		AnalyzerDefinition.TokenFilter.Stopwords stopwords = null;
		if(filter.hasStopwords()) {
			var config = filter.getStopwords();
			stopwords = new AnalyzerDefinition.TokenFilter.Stopwords(
				config.hasLocale() && config.getLocale().hasLocale()
					? config.getLocale().getLocale()
					: null,
				config.hasCustom() ? List.copyOf(config.getCustom().getWordsList()) : null,
				config.hasNamed() && config.getNamed().hasName()
					? config.getNamed().getName()
					: null
			);
		}

		AnalyzerDefinition.TokenFilter.Stemming stemming = null;
		if(filter.hasStemming()) {
			var config = filter.getStemming();
			stemming = new AnalyzerDefinition.TokenFilter.Stemming(
				config.hasLocale() ? config.getLocale() : null
			);
		}

		AnalyzerDefinition.TokenFilter.AsciiFolding asciiFolding = null;
		if(filter.hasAsciiFolding()) {
			var config = filter.getAsciiFolding();
			asciiFolding = new AnalyzerDefinition.TokenFilter.AsciiFolding(
				config.hasPreserveOriginal() ? config.getPreserveOriginal() : null
			);
		}

		AnalyzerDefinition.TokenFilter.EdgeNgram edgeNgram = null;
		if(filter.hasEdgeNgram()) {
			var config = filter.getEdgeNgram();
			edgeNgram = new AnalyzerDefinition.TokenFilter.EdgeNgram(
				config.hasMinGram() ? config.getMinGram() : null,
				config.hasMaxGram() ? config.getMaxGram() : null
			);
		}

		AnalyzerDefinition.TokenFilter.Ngram ngram = null;
		if(filter.hasNgram()) {
			var config = filter.getNgram();
			ngram = new AnalyzerDefinition.TokenFilter.Ngram(
				config.hasMinGram() ? config.getMinGram() : null,
				config.hasMaxGram() ? config.getMaxGram() : null
			);
		}

		AnalyzerDefinition.TokenFilter.Synonyms synonyms = null;
		if(filter.hasSynonyms()) {
			var config = filter.getSynonyms();
			synonyms = new AnalyzerDefinition.TokenFilter.Synonyms(
				config.hasName() ? config.getName() : null
			);
		}

		AnalyzerDefinition.TokenFilter.Decompound decompound = null;
		if(filter.hasDecompound()) {
			var config = filter.getDecompound();
			decompound = new AnalyzerDefinition.TokenFilter.Decompound(
				config.hasLocale() ? config.getLocale() : null
			);
		}

		return new AnalyzerDefinition.TokenFilter(
			normalize,
			stopwords,
			stemming,
			asciiFolding,
			edgeNgram,
			ngram,
			synonyms,
			decompound
		);
	}

	/**
	 * Convert a stored similarity metric, treating one this version does not
	 * know as unset so that the rest of the definition still reads.
	 *
	 * @param similarity
	 * @return
	 */
	private static VectorFieldDefinition.Similarity toApi(
		VectorFieldTypeDef.SimilarityMetric similarity
	) {
		return switch(similarity) {
			case SIMILARITY_METRIC_COSINE -> VectorFieldDefinition.Similarity.COSINE;
			case SIMILARITY_METRIC_DOT_PRODUCT -> VectorFieldDefinition.Similarity.DOT_PRODUCT;
			case SIMILARITY_METRIC_EUCLIDEAN -> VectorFieldDefinition.Similarity.EUCLIDEAN;
			default -> null;
		};
	}

	/**
	 * Convert a stored quantization, treating one this version does not know
	 * as unset so that the rest of the definition still reads.
	 *
	 * @param quantization
	 * @return
	 */
	private static VectorFieldDefinition.Quantization toApi(
		VectorFieldTypeDef.Quantization quantization
	) {
		return switch(quantization) {
			case QUANTIZATION_NONE -> VectorFieldDefinition.Quantization.NONE;
			case QUANTIZATION_INT8 -> VectorFieldDefinition.Quantization.INT8;
			case QUANTIZATION_INT4 -> VectorFieldDefinition.Quantization.INT4;
			default -> null;
		};
	}

	/**
	 * Convert a stored collation, treating one this version does not know as
	 * unset so that the rest of the definition still reads.
	 *
	 * @param collation
	 * @return
	 */
	private static FieldDefinition.Sort.Collation toApi(SortConfig.Collation collation) {
		return switch(collation) {
			case COLLATION_BINARY -> FieldDefinition.Sort.Collation.BINARY;
			case COLLATION_LOCALE -> FieldDefinition.Sort.Collation.LOCALE;
			default -> null;
		};
	}

	/**
	 * Convert a stored missing placement, treating one this version does not
	 * know as unset so that the rest of the definition still reads.
	 *
	 * @param missing
	 * @return
	 */
	private static FieldDefinition.Sort.Missing toApi(SortConfig.Missing missing) {
		return switch(missing) {
			case MISSING_FIRST -> FieldDefinition.Sort.Missing.FIRST;
			case MISSING_LAST -> FieldDefinition.Sort.Missing.LAST;
			default -> null;
		};
	}
}
