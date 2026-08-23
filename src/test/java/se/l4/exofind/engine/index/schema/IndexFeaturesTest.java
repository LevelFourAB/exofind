package se.l4.exofind.engine.index.schema;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.eclipse.collections.api.factory.Lists;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.locales.LocaleSupport;
import se.l4.exofind.engine.index.locales.Locales;

public class IndexFeaturesTest {
	private IndexDef.Builder withField(FieldDef.Builder field) {
		return IndexDef.newBuilder().putFields("field", field.build());
	}

	private FieldDef.Builder string() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder()
					.setString(StringFieldTypeDef.getDefaultInstance())
			);
	}

	@Test
	public void testEmptyDefinitionNeedsNothing() {
		assertThat(
			IndexFeatures.requiredBy(IndexDef.getDefaultInstance()).toList(),
			is(emptyIterable())
		);
	}

	@Test
	public void testKeepingDocumentsIsListed() {
		var definition = IndexDef.newBuilder()
			.setSource(IndexDef.SourceMode.SOURCE_MODE_FULL)
			.build();

		assertThat(
			IndexFeatures.requiredBy(definition).toList(),
			contains("index.source")
		);
	}

	/**
	 * An index that keeps nothing asks for nothing a node without this would
	 * fail to do, so it stays readable by one.
	 */
	@Test
	public void testNotKeepingDocumentsIsNotListed() {
		var definition = IndexDef.newBuilder()
			.setSource(IndexDef.SourceMode.SOURCE_MODE_NONE)
			.build();

		assertThat(
			IndexFeatures.requiredBy(definition).toList(),
			is(emptyIterable())
		);
	}

	@Test
	public void testUsagesAreListed() {
		var definition = withField(
			string()
				.setFilter(FilterConfig.getDefaultInstance())
				.setSort(SortConfig.getDefaultInstance())
				.setFacet(FacetConfig.getDefaultInstance())
		).build();

		assertThat(
			IndexFeatures.requiredBy(definition).toList(),
			containsInAnyOrder("type.string", "field.filter", "field.sort", "field.facet")
		);
	}

	@Test
	public void testNumberTypesAreListed() {
		var definition = IndexDef.newBuilder()
			.putFields(
				"pages",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder().setInt32(Int32FieldTypeDef.getDefaultInstance())
					)
					.build()
			)
			.putFields(
				"isbn",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder().setInt64(Int64FieldTypeDef.getDefaultInstance())
					)
					.build()
			)
			.putFields(
				"weight",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder().setFloat(FloatFieldTypeDef.getDefaultInstance())
					)
					.build()
			)
			.putFields(
				"price",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder()
							.setDouble(DoubleFieldTypeDef.getDefaultInstance())
					)
					.build()
			)
			.build();

		assertThat(
			IndexFeatures.requiredBy(definition).toList(),
			containsInAnyOrder("type.int32", "type.int64", "type.float", "type.double")
		);
	}

	@Test
	public void testTimestampAndGeoPointTypesAreListed() {
		var definition = IndexDef.newBuilder()
			.putFields(
				"published",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder()
							.setTimestamp(TimestampFieldTypeDef.getDefaultInstance())
					)
					.build()
			)
			.putFields(
				"location",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder()
							.setGeoPoint(GeoPointFieldTypeDef.getDefaultInstance())
					)
					.build()
			)
			.build();

		assertThat(
			IndexFeatures.requiredBy(definition).toList(),
			containsInAnyOrder("type.timestamp", "type.geo_point")
		);
	}

	@Test
	public void testStringUsagesOnTheTypeAreListed() {
		var definition = withField(
			FieldDef.newBuilder()
				.setType(
					FieldTypeDef.newBuilder()
						.setString(
							StringFieldTypeDef.newBuilder()
								.setMatching(
									StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
								)
								.setAutocomplete(
									StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
								)
						)
				)
		).build();

		assertThat(
			IndexFeatures.requiredBy(definition).toList(),
			containsInAnyOrder("type.string", "field.matching", "field.autocomplete")
		);
	}

	@Test
	public void testWeightIsListed() {
		var definition = withField(
			FieldDef.newBuilder()
				.setType(
					FieldTypeDef.newBuilder()
						.setString(
							StringFieldTypeDef.newBuilder()
								.setMatching(
									StringFieldTypeDef.TextUsageConfig.newBuilder()
										.setWeight(3f)
								)
						)
				)
		).build();

		assertThat(
			IndexFeatures.requiredBy(definition).toList(),
			containsInAnyOrder("type.string", "field.matching", "field.weight")
		);
	}

	@Test
	public void testTypoToleranceIsListed() {
		var definition = withField(
			FieldDef.newBuilder()
				.setType(
					FieldTypeDef.newBuilder()
						.setString(
							StringFieldTypeDef.newBuilder()
								.setMatching(
									StringFieldTypeDef.TextUsageConfig.newBuilder()
										.setTypoTolerance(
											StringFieldTypeDef.TextUsageConfig
												.TypoToleranceConfig.getDefaultInstance()
										)
								)
						)
				)
		).build();

		assertThat(
			IndexFeatures.requiredBy(definition).toList(),
			containsInAnyOrder(
				"type.string",
				"field.matching",
				"field.typo_tolerance",
				"field.typo_tolerance.numbers"
			)
		);
	}

	/**
	 * Forgiving a mistake in a word somebody is still typing is named besides
	 * the tolerance itself, because a node knowing one may not know the other.
	 */
	@Test
	public void testTypoToleranceWhileCompletingIsListed() {
		var definition = withField(
			FieldDef.newBuilder()
				.setType(
					FieldTypeDef.newBuilder()
						.setString(
							StringFieldTypeDef.newBuilder()
								.setAutocomplete(
									StringFieldTypeDef.TextUsageConfig.newBuilder()
										.setTypoTolerance(
											StringFieldTypeDef.TextUsageConfig
												.TypoToleranceConfig.getDefaultInstance()
										)
								)
						)
				)
		).build();

		assertThat(
			IndexFeatures.requiredBy(definition).toList(),
			containsInAnyOrder(
				"type.string",
				"field.autocomplete",
				"field.typo_tolerance",
				"field.autocomplete.typo_tolerance",
				"field.typo_tolerance.numbers"
			)
		);
	}

	@Test
	public void testHighlightIsListed() {
		var definition = withField(
			FieldDef.newBuilder()
				.setType(
					FieldTypeDef.newBuilder()
						.setString(
							StringFieldTypeDef.newBuilder()
								.setMatching(
									StringFieldTypeDef.TextUsageConfig.newBuilder()
										.setHighlight(
											StringFieldTypeDef.TextUsageConfig
												.HighlightConfig.getDefaultInstance()
										)
								)
						)
				)
		).build();

		assertThat(
			IndexFeatures.requiredBy(definition).toList(),
			containsInAnyOrder("type.string", "field.matching", "field.highlight")
		);
	}

	/**
	 * Named because a node knowing only highlighting would look for term
	 * vectors a postings-layout index does not hold and answer every
	 * highlight empty - and would write new documents with a layout Lucene
	 * refuses to mix into the index.
	 */
	@Test
	public void testHighlightingInPostingsIsListed() {
		var definition = withField(highlightedString())
			.setHighlightLayout(IndexDef.HighlightLayout.HIGHLIGHT_LAYOUT_POSTINGS)
			.build();

		assertThat(
			IndexFeatures.requiredBy(definition).toList(),
			containsInAnyOrder(
				"type.string",
				"field.matching",
				"field.highlight",
				"field.highlight.postings"
			)
		);
	}

	/**
	 * The layout only matters once something highlights, so an index that
	 * highlights nothing carries no layout name and stays openable by a node
	 * that knows neither layout.
	 */
	@Test
	public void testPostingsLayoutWithoutHighlightingNeedsNoName() {
		var definition = withField(string())
			.setHighlightLayout(IndexDef.HighlightLayout.HIGHLIGHT_LAYOUT_POSTINGS)
			.build();

		assertThat(
			IndexFeatures.requiredBy(definition).toList(),
			not(hasItem("field.highlight.postings"))
		);
	}

	@Test
	public void testTermVectorLayoutNeedsNoName() {
		var definition = withField(highlightedString())
			.setHighlightLayout(IndexDef.HighlightLayout.HIGHLIGHT_LAYOUT_TERM_VECTORS)
			.build();

		assertThat(
			IndexFeatures.requiredBy(definition).toList(),
			not(hasItem("field.highlight.postings"))
		);
	}

	private static FieldDef.Builder highlightedString() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder()
					.setString(
						StringFieldTypeDef.newBuilder()
							.setMatching(
								StringFieldTypeDef.TextUsageConfig.newBuilder()
									.setHighlight(
										StringFieldTypeDef.TextUsageConfig
											.HighlightConfig.getDefaultInstance()
									)
							)
					)
			);
	}

	/**
	 * Named because the whole-value term only exists because the field was
	 * written with it, so a node without it would index the field bare and
	 * quietly go back to ranking a mention as highly as a name.
	 */
	@Test
	public void testWholeValueMatchIsListed() {
		var definition = withField(
			FieldDef.newBuilder()
				.setType(
					FieldTypeDef.newBuilder()
						.setString(
							StringFieldTypeDef.newBuilder()
								.setMatching(
									StringFieldTypeDef.TextUsageConfig.newBuilder()
										.setExact(
											StringFieldTypeDef.TextUsageConfig
												.ExactConfig.getDefaultInstance()
										)
								)
						)
				)
		).build();

		assertThat(
			IndexFeatures.requiredBy(definition).toList(),
			containsInAnyOrder("type.string", "field.matching", "field.exact")
		);
	}

	@Test
	public void testLengthNormalizationIsListed() {
		var definition = withField(
			FieldDef.newBuilder()
				.setType(
					FieldTypeDef.newBuilder()
						.setString(
							StringFieldTypeDef.newBuilder()
								.setMatching(
									StringFieldTypeDef.TextUsageConfig.newBuilder()
										.setLengthNormalization(
											StringFieldTypeDef.TextUsageConfig
												.LengthNormalization
												.LENGTH_NORMALIZATION_NONE
										)
								)
						)
				)
		).build();

		assertThat(
			IndexFeatures.requiredBy(definition).toList(),
			containsInAnyOrder(
				"type.string",
				"field.matching",
				"field.length_normalization"
			)
		);
	}

	@Test
	public void testVectorTypeIsListed() {
		var definition = withField(
			FieldDef.newBuilder()
				.setType(
					FieldTypeDef.newBuilder()
						.setVector(VectorFieldTypeDef.newBuilder().setDimensions(4))
				)
		).build();

		assertThat(
			IndexFeatures.requiredBy(definition).toList(),
			contains("type.vector")
		);
	}

	@Test
	public void testValuesReadAsPathsAreListed() {
		var definition = withField(
			FieldDef.newBuilder()
				.setType(
					FieldTypeDef.newBuilder()
						.setString(
							StringFieldTypeDef.newBuilder()
								.setHierarchy(
									StringFieldTypeDef.HierarchyConfig.getDefaultInstance()
								)
						)
				)
		).build();

		assertThat(
			IndexFeatures.requiredBy(definition).toList(),
			containsInAnyOrder("type.string", "field.hierarchy")
		);
	}

	@Test
	public void testKeywordNormalizationIsListed() {
		var definition = withField(
			FieldDef.newBuilder()
				.setType(
					FieldTypeDef.newBuilder()
						.setString(
							StringFieldTypeDef.newBuilder()
								.setKeyword(
									StringFieldTypeDef.KeywordConfig.newBuilder()
										.setCaseFolding(false)
								)
						)
				)
		).build();

		assertThat(
			IndexFeatures.requiredBy(definition).toList(),
			containsInAnyOrder("type.string", "field.keyword")
		);
	}

	@Test
	public void testLocaleSpecificFieldsAreListedWithTheirDefaultLocale() {
		var definition = withField(
			string()
				.setLocales(FieldDef.LocaleConfig.newBuilder().setDefaultLocale("en"))
		).build();

		assertThat(
			IndexFeatures.requiredBy(definition).toList(),
			containsInAnyOrder("type.string", "field.locales", "locale.en")
		);
	}

	/**
	 * Every locale a field holds values in is a set of rules a node has to
	 * have, so each declared one is named alongside the default.
	 */
	@Test
	public void testDeclaredLocalesAreListed() {
		var definition = withField(
			string()
				.setLocales(
					FieldDef.LocaleConfig.newBuilder()
						.setDefaultLocale("en")
						.addLocales("sv")
						.addLocales("de")
				)
		).build();

		assertThat(
			IndexFeatures.requiredBy(definition).toList(),
			containsInAnyOrder(
				"type.string",
				"field.locales",
				"locale.en",
				"locale.sv",
				"locale.de"
			)
		);
	}

	/**
	 * What is referenced through the resources is named the same way inline
	 * configuration is, and a chain among the resources describes its
	 * components - a node lacking any of it refuses the index.
	 */
	@Test
	public void testResourcesAreListed() {
		var definition = IndexDef.newBuilder()
			.setResources(
				ResourcesDef.newBuilder()
					.putAnalyzers(
						"prose",
						AnalyzerDef.newBuilder()
							.addFilters(
								TokenFilterDef.newBuilder()
									.setNormalize(TokenFilterDef.Normalize.getDefaultInstance())
							)
							.addFilters(
								TokenFilterDef.newBuilder()
									.setStopwords(
										TokenFilterDef.Stopwords.newBuilder()
											.setNamed(
												TokenFilterDef.Stopwords.NamedWords
													.newBuilder()
													.setName("brands")
											)
									)
							)
							.addFilters(
								TokenFilterDef.newBuilder()
									.setSynonyms(
										TokenFilterDef.Synonyms.newBuilder().setName("cars")
									)
							)
							.build()
					)
			)
			.putFields(
				"title",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder()
							.setString(
								StringFieldTypeDef.newBuilder()
									.setMatching(
										StringFieldTypeDef.TextUsageConfig.newBuilder()
											.setAnalyzerRef("prose")
									)
							)
					)
					.build()
			)
			.build();

		assertThat(
			IndexFeatures.requiredBy(definition).toList(),
			containsInAnyOrder(
				"type.string",
				"field.matching",
				"resource.analyzer",
				"analyzer.normalize",
				"analyzer.stopwords",
				"resource.stopwords",
				"analyzer.synonyms",
				"resource.synonyms"
			)
		);
	}

	/**
	 * A chain is only as portable as its components, so each one is named -
	 * a node that knows chains in general but lacks a component still refuses
	 * the index.
	 */
	@Test
	public void testAnalyzerComponentsAreListed() {
		var definition = withField(
			FieldDef.newBuilder()
				.setType(
					FieldTypeDef.newBuilder()
						.setString(
							StringFieldTypeDef.newBuilder()
								.setMatching(
									StringFieldTypeDef.TextUsageConfig.newBuilder()
										.setAnalyzer(
											AnalyzerDef.newBuilder()
												.setTokenizer(
													TokenizerDef.newBuilder()
														.setWhitespace(
															TokenizerDef.WhitespaceTokenizer
																.getDefaultInstance()
														)
												)
												.addFilters(
													TokenFilterDef.newBuilder()
														.setNormalize(
															TokenFilterDef.Normalize
																.getDefaultInstance()
														)
												)
												.addFilters(
													TokenFilterDef.newBuilder()
														.setStemming(
															TokenFilterDef.Stemming.newBuilder()
																.setLocale("en")
														)
												)
										)
								)
						)
				)
		).build();

		assertThat(
			IndexFeatures.requiredBy(definition).toList(),
			containsInAnyOrder(
				"type.string",
				"field.matching",
				"field.analyzer",
				"analyzer.whitespace",
				"analyzer.normalize",
				"analyzer.stemming",
				"locale.en"
			)
		);
	}

	/**
	 * The component and the named locale's data are separate needs - a node
	 * can know how to split without carrying the words to split with, and
	 * either gap refuses the index.
	 */
	@Test
	public void testDecompoundComponentWithNamedLocaleIsListed() {
		var definition = withField(
			FieldDef.newBuilder()
				.setType(
					FieldTypeDef.newBuilder()
						.setString(
							StringFieldTypeDef.newBuilder()
								.setMatching(
									StringFieldTypeDef.TextUsageConfig.newBuilder()
										.setAnalyzer(
											AnalyzerDef.newBuilder()
												.addFilters(
													TokenFilterDef.newBuilder()
														.setDecompound(
															TokenFilterDef.Decompound.newBuilder()
																.setLocale("de")
														)
												)
										)
								)
						)
				)
		).build();

		assertThat(
			IndexFeatures.requiredBy(definition).toList(),
			containsInAnyOrder(
				"type.string",
				"field.matching",
				"field.analyzer",
				"analyzer.decompound",
				"locale.de",
				"decompound.de"
			)
		);
	}

	/**
	 * A component without a named locale follows the locale of the value, so
	 * what it needs is the data of the locales the field declares - the ones
	 * this build can split, since the others pass through and ask for
	 * nothing. The engine-built matching chain splits by default, so a field
	 * left to it is described the same way.
	 */
	@Test
	public void testEngineBuiltMatchingListsTheDecompoundingDataOfDeclaredLocales() {
		var definition = withField(
			string()
				.setLocales(
					FieldDef.LocaleConfig.newBuilder()
						.setDefaultLocale("de")
						.addLocales("sv")
				)
				.setType(
					FieldTypeDef.newBuilder()
						.setString(
							StringFieldTypeDef.newBuilder()
								.setMatching(
									StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
								)
						)
				)
		).build();

		var expected = Lists.mutable.of(
			"type.string",
			"field.matching",
			"field.locales",
			"locale.de",
			"locale.sv"
		);
		for(var tag : List.of("de", "sv")) {
			if(Locales.get(tag).map(LocaleSupport::isDecompoundingSupported).orElse(false)) {
				expected.add("decompound." + tag);
			}
		}

		assertThat(
			IndexFeatures.requiredBy(definition).toList(),
			containsInAnyOrder(expected.toArray())
		);
	}

	/**
	 * Turning splitting off takes the need for the data with it, so the
	 * definition stays openable on a node that never had it.
	 */
	@Test
	public void testDecompoundingTurnedOffListsNoData() {
		var definition = withField(
			string()
				.setLocales(
					FieldDef.LocaleConfig.newBuilder()
						.setDefaultLocale("de")
						.addLocales("sv")
				)
				.setType(
					FieldTypeDef.newBuilder()
						.setString(
							StringFieldTypeDef.newBuilder()
								.setMatching(
									StringFieldTypeDef.TextUsageConfig.newBuilder()
										.setDecompound(
											StringFieldTypeDef.TextUsageConfig.DecompoundMode
												.DECOMPOUND_MODE_NONE
										)
								)
						)
				)
		).build();

		assertThat(
			IndexFeatures.requiredBy(definition).toList(),
			containsInAnyOrder(
				"type.string",
				"field.matching",
				"field.locales",
				"locale.de",
				"locale.sv"
			)
		);
	}

	@Test
	public void testRankingIsListed() {
		var definition = IndexDef.newBuilder()
			.setRanking(RankingConfig.getDefaultInstance())
			.build();

		assertThat(
			IndexFeatures.requiredBy(definition).toList(),
			contains("index.ranking")
		);
	}

	/**
	 * Named besides the ranking itself, as a node knowing how to break ties may
	 * not know how to read a value into a score.
	 */
	@Test
	public void testRankingSignalsAreListed() {
		var definition = IndexDef.newBuilder()
			.setRanking(
				RankingConfig.newBuilder()
					.addSignals(
						RankingConfig.Signal.newBuilder()
							.setField("purchases")
							.setSaturation(
								RankingConfig.Signal.Saturation.newBuilder().setPivot(50)
							)
					)
			)
			.build();

		assertThat(
			IndexFeatures.requiredBy(definition).toList(),
			containsInAnyOrder("index.ranking", "index.ranking_signals")
		);
	}

	/**
	 * The locales of the chain are listed besides the fallback itself, as a
	 * filled value is analyzed and collated as the locale it fills.
	 */
	@Test
	public void testLocaleFallbackIsListed() {
		var definition = IndexDef.newBuilder()
			.setLocaleFallback(
				IndexDef.LocaleFallbackConfig.newBuilder()
					.addChain("da")
					.addChain("en")
			)
			.build();

		assertThat(
			IndexFeatures.requiredBy(definition).toList(),
			containsInAnyOrder("index.locale_fallback", "locale.da", "locale.en")
		);
	}

	/**
	 * An index that fills nothing asks for nothing a node without the fallback
	 * would fail to do, so it stays readable by an older one.
	 */
	@Test
	public void testNoLocaleFallbackIsNotListed() {
		var definition = IndexDef.newBuilder().build();

		assertThat(
			IndexFeatures.requiredBy(definition).toList(),
			not(hasItem("index.locale_fallback"))
		);
	}

	/**
	 * The list ends up in the stored definition, which the version of an index
	 * is a hash of, so it has to come out the same way every time.
	 */
	@Test
	public void testTheListIsSorted() {
		var definition = withField(
			string()
				.setSort(SortConfig.getDefaultInstance())
				.setFilter(FilterConfig.getDefaultInstance())
		).build();

		assertThat(
			IndexFeatures.requiredBy(definition).toList(),
			contains("field.filter", "field.sort", "type.string")
		);
	}

	@Test
	public void testDescribeFillsInWhatIsNeeded() {
		var described = IndexFeatures.describe(
			withField(string().setFilter(FilterConfig.getDefaultInstance())).build()
		);

		assertThat(
			described.getRequiredFeaturesList(),
			containsInAnyOrder("type.string", "field.filter")
		);
	}

	@Test
	public void testDescribeReplacesWhatWasThereBefore() {
		var described = IndexFeatures.describe(
			withField(string()).addRequiredFeatures("field.filter").build()
		);

		assertThat(described.getRequiredFeaturesList(), contains("type.string"));
	}

	@Test
	public void testEverythingThisBuildWritesIsSupported() {
		var definition = withField(
			string()
				.setFilter(FilterConfig.getDefaultInstance())
				.setSort(SortConfig.getDefaultInstance())
				.setFacet(FacetConfig.getDefaultInstance())
		).build();

		assertThat(
			IndexFeatures.unsupportedIn(IndexFeatures.describe(definition)),
			is(emptyIterable())
		);
	}

	/**
	 * The case the whole mechanism exists for - a definition written by a
	 * newer version. The parts this build has no code for survive parsing and
	 * would otherwise be quietly ignored.
	 */
	@Test
	public void testDefinitionFromANewerVersionIsRefused() {
		var fromTheFuture = withField(string())
			.addRequiredFeatures("type.string")
			.addRequiredFeatures("field.geo")
			.build();

		assertThat(IndexFeatures.unsupportedIn(fromTheFuture), contains("field.geo"));

		var schema = new IndexSchema();
		var e = assertThrows(ValidationException.class, () -> schema.setDefinition(fromTheFuture));

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:schema:unsupported_features"));
	}
}
