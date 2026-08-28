package se.l4.exofind.engine.index.schema;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import java.util.List;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.errors.ErrorMessage;

/**
 * What {@link DefinitionCompatibility} counts as reaching the documents an
 * index already holds, and what it leaves alone.
 */
public class DefinitionCompatibilityTest {
	/**
	 * A definition with one text field, as the starting point every case
	 * changes one thing about.
	 */
	private static IndexDef.Builder base() {
		return IndexDef.newBuilder()
			.putFields(
				"id",
				FieldDef.newBuilder()
					.setPrimaryKey(true)
					.setType(
						FieldTypeDef.newBuilder()
							.setString(StringFieldTypeDef.getDefaultInstance())
					)
					.build()
			)
			.putFields(
				"title",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder()
							.setString(StringFieldTypeDef.getDefaultInstance())
					)
					.build()
			);
	}

	/**
	 * Change the {@code title} field of a definition, leaving the rest as it is.
	 */
	private static IndexDef withTitle(UnaryOperator<StringFieldTypeDef.Builder> change) {
		return base()
			.putFields(
				"title",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder()
							.setString(change.apply(StringFieldTypeDef.newBuilder()))
					)
					.build()
			)
			.build();
	}

	private static List<String> codes(IndexDef current, IndexDef incoming) {
		return DefinitionCompatibility.check(current, incoming)
			.collect(ErrorMessage::getCode)
			.toList();
	}

	private static List<String> paths(IndexDef current, IndexDef incoming) {
		return DefinitionCompatibility.check(current, incoming)
			.collect(m -> m.getLocation().describe())
			.toList();
	}

	@Test
	public void unchangedDefinitionReportsNothing() {
		assertThat(
			DefinitionCompatibility.check(base().build(), base().build()).toList(),
			is(empty())
		);
	}

	@Test
	public void addingAFieldIsCompatible() {
		var incoming = base()
			.putFields(
				"brand",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder()
							.setString(StringFieldTypeDef.getDefaultInstance())
					)
					.setFacet(FacetConfig.getDefaultInstance())
					.build()
			)
			.build();

		assertThat(codes(base().build(), incoming), is(empty()));
	}

	@Test
	public void removingAFieldIsCompatible() {
		var incoming = base().removeFields("title").build();

		assertThat(codes(base().build(), incoming), is(empty()));
	}

	@Test
	public void turningOnStoredIsIncompatible() {
		var incoming = base()
			.putFields(
				"title",
				base().getFieldsOrThrow("title").toBuilder().setStored(true).build()
			)
			.build();

		assertThat(codes(base().build(), incoming), contains("index:definition:usage_added"));
		assertThat(paths(base().build(), incoming), contains("title"));
	}

	/**
	 * What was stored for the documents already indexed stands however much
	 * less the definition asks to keep, so narrowing is not a change that
	 * reaches them.
	 */
	@Test
	public void turningOffStoredAndSourceIsCompatible() {
		var current = base()
			.putFields(
				"title",
				base().getFieldsOrThrow("title").toBuilder().setStored(true).build()
			)
			.build();

		var incoming = base()
			.setSource(IndexDef.SourceMode.SOURCE_MODE_NONE)
			.build();

		assertThat(codes(current, incoming), is(empty()));
	}

	/**
	 * A source left unset means the index keeps its documents, so an index
	 * that was not keeping them starting to is a change however the incoming
	 * definition spells it.
	 */
	@Test
	public void keepingTheSourceIsIncompatible() {
		var current = base()
			.setSource(IndexDef.SourceMode.SOURCE_MODE_NONE)
			.build();

		assertThat(codes(current, base().build()), contains("index:definition:source_added"));
		assertThat(paths(current, base().build()), contains("source"));
	}

	@Test
	public void turningOnFilterSortAndFacetIsIncompatible() {
		var incoming = base()
			.putFields(
				"title",
				base().getFieldsOrThrow("title").toBuilder()
					.setFilter(FilterConfig.getDefaultInstance())
					.setSort(SortConfig.getDefaultInstance())
					.setFacet(FacetConfig.getDefaultInstance())
					.build()
			)
			.build();

		assertThat(
			codes(base().build(), incoming),
			contains(
				"index:definition:usage_added",
				"index:definition:usage_added",
				"index:definition:usage_added"
			)
		);
		assertThat(paths(base().build(), incoming), contains("title", "title", "title"));
	}

	/**
	 * A usage going away leaves what was written for it standing and nothing
	 * reading it, which answers with less rather than with something wrong.
	 */
	@Test
	public void turningOffAUsageIsCompatible() {
		var current = base()
			.putFields(
				"title",
				base().getFieldsOrThrow("title").toBuilder()
					.setFacet(FacetConfig.getDefaultInstance())
					.build()
			)
			.build();

		assertThat(codes(current, base().build()), is(empty()));
	}

	@Test
	public void turningOnMatchingIsIncompatible() {
		var incoming = withTitle(
			title -> title.setMatching(StringFieldTypeDef.TextUsageConfig.getDefaultInstance())
		);

		assertThat(codes(base().build(), incoming), contains("index:definition:usage_added"));
	}

	@Test
	public void changingTheAnalyzerOfAUsageIsIncompatible() {
		var current = withTitle(
			title -> title.setMatching(
				StringFieldTypeDef.TextUsageConfig.newBuilder()
					.setAnalyzer(
						AnalyzerDef.newBuilder()
							.setTokenizer(
								TokenizerDef.newBuilder()
									.setWhitespace(
										TokenizerDef.WhitespaceTokenizer.getDefaultInstance()
									)
							)
					)
			)
		);

		var incoming = withTitle(
			title -> title.setMatching(
				StringFieldTypeDef.TextUsageConfig.newBuilder()
					.setAnalyzer(
						AnalyzerDef.newBuilder()
							.setTokenizer(
								TokenizerDef.newBuilder()
									.setKeyword(TokenizerDef.KeywordTokenizer.getDefaultInstance())
							)
					)
			)
		);

		assertThat(codes(current, incoming), contains("index:definition:analysis_changed"));
	}

	/**
	 * Weight, typo tolerance and length normalization are read where a search
	 * runs, so a definition changing only those reaches every document already
	 * indexed.
	 */
	@Test
	public void changingQueryTimeSettingsOfAUsageIsCompatible() {
		var current = withTitle(
			title -> title.setMatching(
				StringFieldTypeDef.TextUsageConfig.newBuilder().setWeight(1f)
			)
		);

		var incoming = withTitle(
			title -> title.setMatching(
				StringFieldTypeDef.TextUsageConfig.newBuilder()
					.setWeight(4f)
					.setTypoTolerance(
						StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig.newBuilder()
							.setMinLengthOneTypo(3)
					)
					.setLengthNormalization(
						StringFieldTypeDef.TextUsageConfig.LengthNormalization
							.LENGTH_NORMALIZATION_STRONG
					)
			)
		);

		assertThat(codes(current, incoming), is(empty()));
	}

	@Test
	public void turningOnHighlightingAndExactIsIncompatible() {
		var current = withTitle(
			title -> title.setMatching(
				StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
			)
		);

		var incoming = withTitle(
			title -> title.setMatching(
				StringFieldTypeDef.TextUsageConfig.newBuilder()
					.setHighlight(
						StringFieldTypeDef.TextUsageConfig.HighlightConfig.getDefaultInstance()
					)
					.setExact(
						StringFieldTypeDef.TextUsageConfig.ExactConfig.getDefaultInstance()
					)
			)
		);

		assertThat(
			codes(current, incoming),
			contains("index:definition:usage_added", "index:definition:usage_added")
		);
	}

	/**
	 * A synonym set is applied while indexing, so editing one changes nothing
	 * for the documents that are already there. Reported as the field that uses
	 * it changing, which is where the effect is.
	 */
	@Test
	public void editingASynonymSetAFieldUsesIsIncompatible() {
		UnaryOperator<IndexDef.Builder> withSynonymUsingField = def -> def
			.putFields(
				"title",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder()
							.setString(
								StringFieldTypeDef.newBuilder()
									.setMatching(
										StringFieldTypeDef.TextUsageConfig.newBuilder()
											.setAnalyzerRef("text")
									)
							)
					)
					.build()
			);

		var analyzer = AnalyzerDef.newBuilder()
			.setTokenizer(
				TokenizerDef.newBuilder()
					.setWhitespace(TokenizerDef.WhitespaceTokenizer.getDefaultInstance())
			)
			.addFilters(
				TokenFilterDef.newBuilder()
					.setSynonyms(TokenFilterDef.Synonyms.newBuilder().setName("marketing"))
			);

		var current = withSynonymUsingField.apply(base())
			.setResources(
				ResourcesDef.newBuilder()
					.putAnalyzers("text", analyzer.build())
					.putSynonyms("marketing", ResourcesDef.SynonymsResource.getDefaultInstance())
			)
			.build();

		var incoming = withSynonymUsingField.apply(base())
			.setResources(
				ResourcesDef.newBuilder()
					.putAnalyzers("text", analyzer.build())
					.putSynonyms(
						"marketing",
						ResourcesDef.SynonymsResource.newBuilder()
							.addRules(
								ResourcesDef.SynonymsResource.Rule.newBuilder()
									.setEquivalent(
										ResourcesDef.SynonymsResource.Rule.Equivalent.newBuilder()
											.addTerms("trainers")
											.addTerms("sneakers")
									)
							)
							.build()
					)
			)
			.build();

		assertThat(codes(current, incoming), contains("index:definition:analysis_changed"));
	}

	/**
	 * A resource no retained field reaches decides nothing about what is in the
	 * index.
	 */
	@Test
	public void editingASynonymSetNoFieldUsesIsCompatible() {
		var current = base()
			.setResources(
				ResourcesDef.newBuilder()
					.putSynonyms("unused", ResourcesDef.SynonymsResource.getDefaultInstance())
			)
			.build();

		var incoming = base()
			.setResources(
				ResourcesDef.newBuilder()
					.putSynonyms(
						"unused",
						ResourcesDef.SynonymsResource.newBuilder()
							.addRules(
								ResourcesDef.SynonymsResource.Rule.newBuilder()
									.setEquivalent(
										ResourcesDef.SynonymsResource.Rule.Equivalent.newBuilder()
											.addTerms("trainers")
											.addTerms("sneakers")
									)
							)
							.build()
					)
			)
			.build();

		assertThat(codes(current, incoming), is(empty()));
	}

	@Test
	public void changingTheTypeOfAFieldIsIncompatible() {
		var incoming = base()
			.putFields(
				"title",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder()
							.setInt32(Int32FieldTypeDef.getDefaultInstance())
					)
					.build()
			)
			.build();

		assertThat(codes(base().build(), incoming), contains("index:definition:setting_changed"));
	}

	@Test
	public void changingVectorDimensionsIsIncompatible() {
		var current = base()
			.putFields(
				"embedding",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder()
							.setVector(VectorFieldTypeDef.newBuilder().setDimensions(384))
					)
					.build()
			)
			.build();

		var incoming = base()
			.putFields(
				"embedding",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder()
							.setVector(VectorFieldTypeDef.newBuilder().setDimensions(768))
					)
					.build()
			)
			.build();

		assertThat(codes(current, incoming), contains("index:definition:setting_changed"));
		assertThat(paths(current, incoming), contains("embedding"));
	}

	@Test
	public void changingHnswParametersIsIncompatible() {
		var current = base()
			.putFields(
				"embedding",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder()
							.setVector(VectorFieldTypeDef.newBuilder().setDimensions(384))
					)
					.build()
			)
			.build();

		var incoming = base()
			.putFields(
				"embedding",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder()
							.setVector(
								VectorFieldTypeDef.newBuilder()
									.setDimensions(384)
									.setHnsw(
										VectorFieldTypeDef.HNSWConfig.newBuilder().setM(32)
									)
							)
					)
					.build()
			)
			.build();

		assertThat(codes(current, incoming), contains("index:definition:setting_changed"));
		assertThat(paths(current, incoming), contains("embedding"));
	}

	/**
	 * Saying what the engine would have chosen is not a change, so a definition
	 * written out in full replaces one that left the defaults unset.
	 */
	@Test
	public void writingOutADefaultIsCompatible() {
		var current = base()
			.putFields(
				"embedding",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder()
							.setVector(VectorFieldTypeDef.newBuilder().setDimensions(384))
					)
					.build()
			)
			.build();

		var incoming = base()
			.putFields(
				"embedding",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder()
							.setVector(
								VectorFieldTypeDef.newBuilder()
									.setDimensions(384)
									.setSimilarity(
										VectorFieldTypeDef.SimilarityMetric.SIMILARITY_METRIC_COSINE
									)
									.setQuantization(
										VectorFieldTypeDef.Quantization.QUANTIZATION_NONE
									)
									.setHnsw(
										VectorFieldTypeDef.HNSWConfig.newBuilder()
											.setM(16)
											.setEfConstruction(100)
									)
							)
					)
					.build()
			)
			.build();

		assertThat(codes(current, incoming), is(empty()));
	}

	@Test
	public void givingAFieldLocalesIsIncompatible() {
		var incoming = base()
			.putFields(
				"title",
				base().getFieldsOrThrow("title").toBuilder()
					.setLocales(
						FieldDef.LocaleConfig.newBuilder()
							.setDefaultLocale("en")
							.addLocales("sv")
					)
					.build()
			)
			.build();

		assertThat(codes(base().build(), incoming), contains("index:definition:usage_added"));
	}

	@Test
	public void addingALocaleToAFieldThatHasSomeIsIncompatible() {
		var current = base()
			.putFields(
				"title",
				base().getFieldsOrThrow("title").toBuilder()
					.setLocales(FieldDef.LocaleConfig.newBuilder().setDefaultLocale("en"))
					.build()
			)
			.build();

		var incoming = base()
			.putFields(
				"title",
				base().getFieldsOrThrow("title").toBuilder()
					.setLocales(
						FieldDef.LocaleConfig.newBuilder()
							.setDefaultLocale("en")
							.addLocales("sv")
					)
					.build()
			)
			.build();

		assertThat(codes(current, incoming), contains("index:definition:setting_changed"));
	}

	@Test
	public void turningOnLocaleFallbackIsIncompatible() {
		var incoming = base()
			.setLocaleFallback(IndexDef.LocaleFallbackConfig.newBuilder().addChain("en"))
			.build();

		assertThat(
			codes(base().build(), incoming),
			contains("index:definition:locale_fallback_changed")
		);
		assertThat(paths(base().build(), incoming), contains("localeFallback"));
	}

	/**
	 * A field inside an object is compared the same way, and named by the path
	 * a caller wrote it at.
	 */
	@Test
	public void aFieldInsideAnObjectIsComparedAndNamedByItsPath() {
		UnaryOperator<FieldDef.Builder> variant = variantField -> FieldDef.newBuilder()
			.setMultiple(true)
			.setType(
				FieldTypeDef.newBuilder()
					.setObject(
						ObjectFieldTypeDef.newBuilder()
							.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
							.putFields("sku", variantField.build())
					)
			);

		var current = base()
			.putFields(
				"variants",
				variant.apply(
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setString(StringFieldTypeDef.getDefaultInstance())
						)
				).build()
			)
			.build();

		var incoming = base()
			.putFields(
				"variants",
				variant.apply(
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setString(StringFieldTypeDef.getDefaultInstance())
						)
						.setFilter(FilterConfig.getDefaultInstance())
				).build()
			)
			.build();

		assertThat(codes(current, incoming), contains("index:definition:usage_added"));
		assertThat(paths(current, incoming), contains("variants.sku"));
	}

	@Test
	public void changingHowAnObjectKeepsItsValuesIsIncompatible() {
		var current = base()
			.putFields("variants", object(ObjectFieldTypeDef.Mode.MODE_NESTED))
			.build();

		var incoming = base()
			.putFields("variants", object(ObjectFieldTypeDef.Mode.MODE_FLATTENED))
			.build();

		assertThat(codes(current, incoming), contains("index:definition:setting_changed"));
	}

	private static FieldDef object(ObjectFieldTypeDef.Mode mode) {
		return FieldDef.newBuilder()
			.setMultiple(true)
			.setType(
				FieldTypeDef.newBuilder()
					.setObject(ObjectFieldTypeDef.newBuilder().setMode(mode))
			)
			.build();
	}

	@Test
	public void changingRankingAndMetadataIsCompatible() {
		var current = base()
			.putFields(
				"popularity",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder()
							.setInt32(Int32FieldTypeDef.getDefaultInstance())
					)
					.setSort(SortConfig.getDefaultInstance())
					.build()
			)
			.build();

		var incoming = current.toBuilder()
			.putMetadata("owner", "search")
			.setRanking(
				RankingConfig.newBuilder()
					.addSignals(
						RankingConfig.Signal.newBuilder()
							.setField("popularity")
							.setSaturation(
								RankingConfig.Signal.Saturation.newBuilder().setPivot(10)
							)
					)
			)
			.build();

		assertThat(codes(current, incoming), is(empty()));
	}
}
