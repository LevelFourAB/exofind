package se.l4.exofind.engine.api.v1alpha1.admin;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.protobuf.UnknownFieldSet;

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
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.RankingConfig;
import se.l4.exofind.engine.index.schema.SortConfig;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.schema.VectorFieldTypeDef;

public class IndexDefinitionMapperTest {
	private static StringFieldDefinition string(
		StringFieldDefinition.Keyword keyword,
		StringFieldDefinition.TextUsage matching,
		StringFieldDefinition.TextUsage autocomplete
	) {
		return new StringFieldDefinition(
			null, null, null, null, null,
			null, null, null,
			keyword,
			matching,
			autocomplete,
			null
		);
	}

	private static IndexDefinition withFields(Map<String, FieldDefinition> fields) {
		return new IndexDefinition(null, null, fields, null, null, null);
	}

	@Test
	public void testEmptyDefinition() {
		var stored = IndexDefinitionMapper.toStored(
			new IndexDefinition(null, null, null, null, null, null)
		);

		assertThat(stored.getFieldsCount(), is(0));
		assertThat(stored.getMetadataCount(), is(0));
	}

	@Test
	public void testMetadataIsKept() {
		var stored = IndexDefinitionMapper.toStored(
			new IndexDefinition(null, Map.of("owner", "search"), Map.of(), null, null, null)
		);

		assertThat(stored.getMetadataMap(), is(Map.of("owner", "search")));

		var api = IndexDefinitionMapper.toApi(stored);
		assertThat(api.metadata(), is(Map.of("owner", "search")));
	}

	@Test
	public void testEmptyMetadataIsNotReturned() {
		var api = IndexDefinitionMapper.toApi(
			IndexDefinitionMapper.toStored(
				new IndexDefinition(null, Map.of(), Map.of(), null, null, null)
			)
		);

		assertThat(api.metadata(), is(nullValue()));
	}

	@Test
	public void testStringField() {
		var field = new StringFieldDefinition(
			true,
			null,
			null,
			true,
			null,
			new FieldDefinition.Filter(),
			null,
			null,
			new StringFieldDefinition.Keyword(false),
			new StringFieldDefinition.TextUsage(
				null,
				null,
				new StringFieldDefinition.TextUsage.Highlight(),
				null,
				null,
				null,
				null
			),
			null,
			null
		);

		var stored = IndexDefinitionMapper.toStored(withFields(Map.of("id", field)));

		var storedField = stored.getFieldsOrThrow("id");
		assertThat(storedField.getPrimaryKey(), is(true));
		assertThat(storedField.getStored(), is(true));
		assertThat(storedField.getType().hasString(), is(true));

		assertThat(storedField.hasFilter(), is(true));
		assertThat(storedField.hasSort(), is(false));
		assertThat(storedField.hasFacet(), is(false));

		var string = storedField.getType().getString();
		assertThat(string.hasKeyword(), is(true));
		assertThat(string.getKeyword().getCaseFolding(), is(false));
		assertThat(string.hasMatching(), is(true));
		assertThat(string.getMatching().hasHighlight(), is(true));
		assertThat(string.hasAutocomplete(), is(false));

		// Back to the API the field should be the one that was sent
		var api = IndexDefinitionMapper.toApi(stored);
		assertThat(api.fields().get("id"), is(field));
	}

	@Test
	public void testHierarchyRoundTrips() {
		var field = new StringFieldDefinition(
			null, null, null, null, null,
			new FieldDefinition.Filter(),
			null,
			new FieldDefinition.Facet(),
			null, null, null,
			new StringFieldDefinition.Hierarchy(" > ")
		);

		var stored = IndexDefinitionMapper.toStored(withFields(Map.of("category", field)));

		var string = stored.getFieldsOrThrow("category").getType().getString();
		assertThat(string.hasHierarchy(), is(true));
		assertThat(string.getHierarchy().getSeparator(), is(" > "));

		assertThat(IndexDefinitionMapper.toApi(stored).fields().get("category"), is(field));
	}

	@Test
	public void testHierarchyWithoutASeparatorRoundTrips() {
		var field = new StringFieldDefinition(
			null, null, null, null, null, null, null, null, null, null, null,
			new StringFieldDefinition.Hierarchy(null)
		);

		var stored = IndexDefinitionMapper.toStored(withFields(Map.of("category", field)));

		var string = stored.getFieldsOrThrow("category").getType().getString();
		assertThat(string.hasHierarchy(), is(true));
		assertThat(string.getHierarchy().hasSeparator(), is(false));

		assertThat(IndexDefinitionMapper.toApi(stored).fields().get("category"), is(field));
	}

	@Test
	public void testLocalesRoundTrip() {
		var field = new StringFieldDefinition(
			null, null, null, null,
			new FieldDefinition.Locales("en", null, null),
			null, null, null,
			null, null, null,
			null
		);

		var stored = IndexDefinitionMapper.toStored(withFields(Map.of("title", field)));

		var storedField = stored.getFieldsOrThrow("title");
		assertThat(storedField.hasLocales(), is(true));
		assertThat(storedField.getLocales().getDefaultLocale(), is("en"));

		var api = IndexDefinitionMapper.toApi(stored);
		assertThat(api.fields().get("title"), is(field));
	}

	@Test
	public void testDeclaredLocalesRoundTrip() {
		var field = new StringFieldDefinition(
			null, null, null, null,
			new FieldDefinition.Locales("en", List.of("sv", "de"), null),
			null, null, null,
			null, null, null,
			null
		);

		var stored = IndexDefinitionMapper.toStored(withFields(Map.of("title", field)));

		var storedField = stored.getFieldsOrThrow("title");
		assertThat(storedField.getLocales().getLocalesList(), is(List.of("sv", "de")));

		var api = IndexDefinitionMapper.toApi(stored);
		assertThat(api.fields().get("title"), is(field));
	}

	@Test
	public void testLocaleFallbackRoundTrips() {
		var definition = new IndexDefinition(
			null, null, null, null, null,
			new IndexDefinition.LocaleFallback(List.of("da", "en"))
		);

		var stored = IndexDefinitionMapper.toStored(definition);

		assertThat(stored.hasLocaleFallback(), is(true));
		assertThat(stored.getLocaleFallback().getChainList(), is(List.of("da", "en")));

		var api = IndexDefinitionMapper.toApi(stored);
		assertThat(api.localeFallback(), is(definition.localeFallback()));
	}

	/**
	 * Falling back without naming a chain is a definition of its own - every
	 * field goes to its default locale - so the empty object has to survive
	 * being read back rather than reading as no fallback at all.
	 */
	@Test
	public void testLocaleFallbackWithoutAChainRoundTrips() {
		var definition = new IndexDefinition(
			null, null, null, null, null,
			new IndexDefinition.LocaleFallback(null)
		);

		var stored = IndexDefinitionMapper.toStored(definition);
		assertThat(stored.hasLocaleFallback(), is(true));

		var api = IndexDefinitionMapper.toApi(stored);
		assertThat(api.localeFallback(), is(new IndexDefinition.LocaleFallback(null)));
	}

	@Test
	public void testNoLocaleFallbackIsNotReturned() {
		var api = IndexDefinitionMapper.toApi(
			IndexDefinitionMapper.toStored(
				new IndexDefinition(null, null, null, null, null, null)
			)
		);

		assertThat(api.localeFallback(), is(nullValue()));
	}

	@Test
	public void testFieldOptingOutOfLocaleFallbackRoundTrips() {
		var field = new StringFieldDefinition(
			null, null, null, null,
			new FieldDefinition.Locales(
				"en",
				List.of("sv"),
				FieldDefinition.Locales.Fallback.DISABLED
			),
			null, null, null,
			null, null, null,
			null
		);

		var stored = IndexDefinitionMapper.toStored(withFields(Map.of("title", field)));

		assertThat(
			stored.getFieldsOrThrow("title").getLocales().getFallback(),
			is(FieldDef.LocaleConfig.Fallback.FALLBACK_DISABLED)
		);

		var api = IndexDefinitionMapper.toApi(stored);
		assertThat(api.fields().get("title"), is(field));
	}

	@Test
	public void testSortAndFacetRoundTrip() {
		var field = new StringFieldDefinition(
			null,
			null,
			null,
			null,
			null,
			null,
			new FieldDefinition.Sort(
				FieldDefinition.Sort.Collation.LOCALE,
				FieldDefinition.Sort.Missing.FIRST
			),
			new FieldDefinition.Facet(),
			null,
			null,
			null,
			null
		);

		var stored = IndexDefinitionMapper.toStored(withFields(Map.of("title", field)));

		var storedField = stored.getFieldsOrThrow("title");
		assertThat(storedField.hasSort(), is(true));
		assertThat(
			storedField.getSort().getCollation(),
			is(SortConfig.Collation.COLLATION_LOCALE)
		);
		assertThat(storedField.getSort().getMissing(), is(SortConfig.Missing.MISSING_FIRST));
		assertThat(storedField.hasFacet(), is(true));

		var api = IndexDefinitionMapper.toApi(stored);
		assertThat(api.fields().get("title"), is(field));
	}

	@Test
	public void testBooleanField() {
		var field = new BooleanFieldDefinition(
			null, true, null, null, null,
			new FieldDefinition.Filter(),
			null,
			null
		);

		var stored = IndexDefinitionMapper.toStored(withFields(Map.of("published", field)));

		var storedField = stored.getFieldsOrThrow("published");
		assertThat(storedField.getRequired(), is(true));
		assertThat(storedField.getType().hasBoolean(), is(true));
		assertThat(storedField.hasFilter(), is(true));

		var api = IndexDefinitionMapper.toApi(stored);
		assertThat(api.fields().get("published"), is(field));
	}

	@Test
	public void testNumberFieldsRoundTrip() {
		var pages = new Int32FieldDefinition(
			null, null, null, true, null,
			new FieldDefinition.Filter(),
			null, null,
			new Int32FieldDefinition.Validation(1, 10_000)
		);
		var isbn = new Int64FieldDefinition(
			null, null, null, null, null,
			new FieldDefinition.Filter(),
			null, null,
			null
		);
		var weight = new FloatFieldDefinition(
			null, null, null, null, null,
			null,
			new FieldDefinition.Sort(null, null),
			null,
			new FloatFieldDefinition.Validation(0f, null)
		);
		var price = new DoubleFieldDefinition(
			null, null, null, null, null,
			null, null,
			new FieldDefinition.Facet(),
			new DoubleFieldDefinition.Validation(null, 100.0)
		);

		var stored = IndexDefinitionMapper.toStored(
			withFields(Map.of("pages", pages, "isbn", isbn, "weight", weight, "price", price))
		);

		var storedPages = stored.getFieldsOrThrow("pages");
		assertThat(storedPages.getType().hasInt32(), is(true));
		assertThat(storedPages.getType().getInt32().getValidation().getMin(), is(1));
		assertThat(storedPages.getType().getInt32().getValidation().getMax(), is(10_000));

		var storedIsbn = stored.getFieldsOrThrow("isbn");
		assertThat(storedIsbn.getType().hasInt64(), is(true));
		assertThat(storedIsbn.getType().getInt64().hasValidation(), is(false));

		var storedWeight = stored.getFieldsOrThrow("weight");
		assertThat(storedWeight.getType().hasFloat(), is(true));
		assertThat(storedWeight.getType().getFloat().getValidation().getMin(), is(0f));
		assertThat(storedWeight.getType().getFloat().getValidation().hasMax(), is(false));

		var storedPrice = stored.getFieldsOrThrow("price");
		assertThat(storedPrice.getType().hasDouble(), is(true));
		assertThat(storedPrice.getType().getDouble().getValidation().getMax(), is(100.0));

		var api = IndexDefinitionMapper.toApi(stored);
		assertThat(api.fields().get("pages"), is(pages));
		assertThat(api.fields().get("isbn"), is(isbn));
		assertThat(api.fields().get("weight"), is(weight));
		assertThat(api.fields().get("price"), is(price));
	}

	@Test
	public void testTimestampFieldRoundTrip() {
		var field = new TimestampFieldDefinition(
			null, null, null, true, null,
			new FieldDefinition.Filter(),
			new FieldDefinition.Sort(null, null),
			null
		);

		var stored = IndexDefinitionMapper.toStored(withFields(Map.of("published", field)));

		var storedField = stored.getFieldsOrThrow("published");
		assertThat(storedField.getType().hasTimestamp(), is(true));

		var api = IndexDefinitionMapper.toApi(stored);
		assertThat(api.fields().get("published"), is(field));
	}

	@Test
	public void testGeoPointFieldRoundTrip() {
		var field = new GeoPointFieldDefinition(
			null, null, null, null, null,
			new FieldDefinition.Filter(),
			new FieldDefinition.Sort(null, null),
			null
		);

		var stored = IndexDefinitionMapper.toStored(withFields(Map.of("location", field)));

		var storedField = stored.getFieldsOrThrow("location");
		assertThat(storedField.getType().hasGeoPoint(), is(true));

		var api = IndexDefinitionMapper.toApi(stored);
		assertThat(api.fields().get("location"), is(field));
	}

	@Test
	public void testObjectFieldRoundTrip() {
		var field = new ObjectFieldDefinition(
			null, null, true, null, null,
			null, null, null,
			Map.of(
				"color", new StringFieldDefinition(
					null, true, null, null, null,
					new FieldDefinition.Filter(), null, null,
					null, null, null,
					null
				),
				"price", new DoubleFieldDefinition(
					null, null, null, null, null,
					new FieldDefinition.Filter(), null, null,
					null
				)
			)
		);

		var stored = IndexDefinitionMapper.toStored(withFields(Map.of("variants", field)));

		var storedField = stored.getFieldsOrThrow("variants");
		assertThat(storedField.getMultiple(), is(true));
		assertThat(storedField.getType().hasObject(), is(true));

		var inner = storedField.getType().getObject().getFieldsMap();
		assertThat(inner.get("color").getType().hasString(), is(true));
		assertThat(inner.get("color").getRequired(), is(true));
		assertThat(inner.get("price").getType().hasDouble(), is(true));

		var api = IndexDefinitionMapper.toApi(stored);
		assertThat(api.fields().get("variants"), is(field));
	}

	@Test
	public void testVectorField() {
		var field = new VectorFieldDefinition(
			null, null, null, true, null,
			null, null, null,
			1536,
			VectorFieldDefinition.Similarity.DOT_PRODUCT,
			new VectorFieldDefinition.Hnsw(32, 200),
			VectorFieldDefinition.Quantization.INT8
		);

		var stored = IndexDefinitionMapper.toStored(withFields(Map.of("embedding", field)));

		var storedField = stored.getFieldsOrThrow("embedding");
		assertThat(storedField.getStored(), is(true));
		assertThat(storedField.getType().hasVector(), is(true));

		var vector = storedField.getType().getVector();
		assertThat(vector.getDimensions(), is(1536));
		assertThat(
			vector.getSimilarity(),
			is(VectorFieldTypeDef.SimilarityMetric.SIMILARITY_METRIC_DOT_PRODUCT)
		);
		assertThat(vector.getHnsw().getM(), is(32));
		assertThat(vector.getHnsw().getEfConstruction(), is(200));
		assertThat(
			vector.getQuantization(),
			is(VectorFieldTypeDef.Quantization.QUANTIZATION_INT8)
		);

		var api = IndexDefinitionMapper.toApi(stored);
		assertThat(api.fields().get("embedding"), is(field));
	}

	@Test
	public void testVectorFieldUnsetPropertiesStayUnset() {
		var field = new VectorFieldDefinition(
			null, null, null, null, null,
			null, null, null,
			4,
			null,
			null,
			null
		);

		var stored = IndexDefinitionMapper.toStored(withFields(Map.of("embedding", field)));

		var storedField = stored.getFieldsOrThrow("embedding");
		var vector = storedField.getType().getVector();
		assertThat(vector.hasSimilarity(), is(false));
		assertThat(vector.hasHnsw(), is(false));
		assertThat(vector.hasQuantization(), is(false));

		var api = IndexDefinitionMapper.toApi(stored);
		assertThat(api.fields().get("embedding"), is(field));
	}

	@Test
	public void testUnsetPropertiesStayUnset() {
		var stored = IndexDefinitionMapper.toStored(
			withFields(
				Map.of(
					"title",
					string(
						null,
						new StringFieldDefinition.TextUsage(null, null, null, null, null, null, null),
						null
					)
				)
			)
		);

		var storedField = stored.getFieldsOrThrow("title");
		assertThat(storedField.hasStored(), is(false));
		assertThat(storedField.hasRequired(), is(false));
		assertThat(storedField.hasLocales(), is(false));
		assertThat(storedField.hasFilter(), is(false));
		assertThat(storedField.hasSort(), is(false));
		assertThat(storedField.hasFacet(), is(false));

		var string = storedField.getType().getString();
		assertThat(string.hasKeyword(), is(false));
		assertThat(string.getMatching().hasAnalyzer(), is(false));
		assertThat(string.getMatching().hasHighlight(), is(false));
		assertThat(string.getMatching().hasWeight(), is(false));

		var api = IndexDefinitionMapper.toApi(stored);
		var field = api.fields().get("title");
		assertThat(field, instanceOf(StringFieldDefinition.class));
		assertThat(field.stored(), is(nullValue()));
		assertThat(field.required(), is(nullValue()));
		assertThat(field.locales(), is(nullValue()));
		assertThat(field.filter(), is(nullValue()));
		assertThat(field.sort(), is(nullValue()));
		assertThat(field.facet(), is(nullValue()));

		var matching = ((StringFieldDefinition) field).matching();
		assertThat(
			matching,
			is(new StringFieldDefinition.TextUsage(null, null, null, null, null, null, null))
		);
	}

	@Test
	public void testWeightAndTypoToleranceRoundTrip() {
		var field = string(
			null,
			new StringFieldDefinition.TextUsage(
				null,
				3f,
				null,
				new StringFieldDefinition.TextUsage.TypoTolerance(4, null, 2),
				null,
				null,
				null
			),
			new StringFieldDefinition.TextUsage(null, 0.5f, null, null, null, null, null)
		);

		var stored = IndexDefinitionMapper.toStored(withFields(Map.of("title", field)));

		var matching = stored.getFieldsOrThrow("title").getType().getString().getMatching();
		assertThat(matching.getWeight(), is(3f));
		assertThat(matching.hasTypoTolerance(), is(true));
		assertThat(matching.getTypoTolerance().getMinLengthOneTypo(), is(4));
		assertThat(matching.getTypoTolerance().hasMinLengthTwoTypos(), is(false));
		assertThat(matching.getTypoTolerance().getPrefixLength(), is(2));

		var autocomplete = stored.getFieldsOrThrow("title").getType().getString()
			.getAutocomplete();
		assertThat(autocomplete.getWeight(), is(0.5f));

		var api = IndexDefinitionMapper.toApi(stored);
		assertThat(api.fields().get("title"), is(field));
	}

	/**
	 * A preset is a convenience of the API, not something stored - it expands
	 * to the chain it names, and reading the definition back shows the chain.
	 */
	@Test
	public void testPresetIsExpandedToItsChain() {
		var field = string(
			null,
			new StringFieldDefinition.TextUsage(
				new AnalyzerDefinition(AnalyzerDefinition.Preset.FULL_TEXT, null, null),
				null,
				null,
				null,
				null,
				null,
				null
			),
			null
		);

		var stored = IndexDefinitionMapper.toStored(withFields(Map.of("title", field)));

		var matching = stored.getFieldsOrThrow("title").getType().getString().getMatching();
		assertThat(matching.hasAnalyzer(), is(true));

		var chain = matching.getAnalyzer();
		assertThat(chain.getFiltersCount(), is(4));
		assertThat(chain.getFilters(0).hasNormalize(), is(true));
		assertThat(chain.getFilters(1).hasStopwords(), is(true));
		assertThat(chain.getFilters(2).hasDecompound(), is(true));
		assertThat(chain.getFilters(3).hasStemming(), is(true));

		var api = (StringFieldDefinition) IndexDefinitionMapper.toApi(stored)
			.fields().get("title");
		assertThat(api.matching().analyzer().preset(), is(nullValue()));
		assertThat(api.matching().analyzer().custom().filters().size(), is(4));
	}

	@Test
	public void testCustomChainRoundTrips() {
		var field = string(
			null,
			new StringFieldDefinition.TextUsage(
				new AnalyzerDefinition(
					null,
					new AnalyzerDefinition.Custom(
						List.of(
							new AnalyzerDefinition.CharFilter(
								null,
								new AnalyzerDefinition.CharFilter.Mapping(Map.of("-", "")),
								null
							)
						),
						new AnalyzerDefinition.Tokenizer(
							null,
							new AnalyzerDefinition.Tokenizer.Whitespace(),
							null,
							null
						),
						List.of(
							new AnalyzerDefinition.TokenFilter(
								new AnalyzerDefinition.TokenFilter.Normalize(null),
								null, null, null, null, null, null, null
							),
							new AnalyzerDefinition.TokenFilter(
								null,
								new AnalyzerDefinition.TokenFilter.Stopwords(
									null, List.of("spring"), null
								),
								null, null, null, null, null, null
							)
						)
					),
					null
				),
				null,
				null,
				null,
				null,
				null,
				null
			),
			null
		);

		var stored = IndexDefinitionMapper.toStored(withFields(Map.of("title", field)));

		var chain = stored.getFieldsOrThrow("title").getType().getString().getMatching()
			.getAnalyzer();
		assertThat(chain.getCharFilters(0).getMapping().getMappingsMap(), is(Map.of("-", "")));
		assertThat(chain.getTokenizer().hasWhitespace(), is(true));
		assertThat(chain.getFilters(0).hasNormalize(), is(true));
		assertThat(
			chain.getFilters(1).getStopwords().getCustom().getWordsList(),
			is(List.of("spring"))
		);

		var api = IndexDefinitionMapper.toApi(stored);
		assertThat(api.fields().get("title"), is(field));
	}

	/**
	 * A named analyzer is stored as a reference into the resources rather than
	 * as a chain of its own, and reads back as the name.
	 */
	@Test
	public void testNamedAnalyzerRoundTrips() {
		var field = string(
			null,
			new StringFieldDefinition.TextUsage(
				new AnalyzerDefinition(null, null, "prose"),
				null,
				null,
				null,
				null,
				null,
				null
			),
			null
		);

		var stored = IndexDefinitionMapper.toStored(withFields(Map.of("title", field)));

		var matching = stored.getFieldsOrThrow("title").getType().getString().getMatching();
		assertThat(matching.hasAnalyzer(), is(false));
		assertThat(matching.getAnalyzerRef(), is("prose"));

		var api = IndexDefinitionMapper.toApi(stored);
		assertThat(api.fields().get("title"), is(field));
	}

	@Test
	public void testResourcesRoundTrip() {
		var resources = new IndexDefinition.Resources(
			Map.of(
				"prose",
				new AnalyzerDefinition(
					null,
					new AnalyzerDefinition.Custom(
						null,
						null,
						List.of(
							new AnalyzerDefinition.TokenFilter(
								new AnalyzerDefinition.TokenFilter.Normalize(null),
								null, null, null, null, null, null, null
							),
							new AnalyzerDefinition.TokenFilter(
								null,
								new AnalyzerDefinition.TokenFilter.Stopwords(
									null, null, "brands"
								),
								null, null, null, null, null, null
							),
							new AnalyzerDefinition.TokenFilter(
								null, null, null, null, null, null,
								new AnalyzerDefinition.TokenFilter.Synonyms("cars"),
								null
							)
						)
					),
					null
				)
			),
			Map.of("brands", List.of("acme")),
			Map.of(
				"cars",
				new IndexDefinition.Resources.Synonyms(
					List.of(
						new IndexDefinition.Resources.Synonyms.Rule(
							List.of("car", "automobile"),
							null
						),
						new IndexDefinition.Resources.Synonyms.Rule(
							null,
							new IndexDefinition.Resources.Synonyms.Rule.Mapping(
								List.of("ny"),
								List.of("new york")
							)
						)
					)
				)
			)
		);

		var stored = IndexDefinitionMapper.toStored(
			new IndexDefinition(null, null, null, null, resources, null)
		);

		assertThat(stored.hasResources(), is(true));
		var storedResources = stored.getResources();

		var chain = storedResources.getAnalyzersOrThrow("prose");
		assertThat(chain.getFilters(1).getStopwords().getNamed().getName(), is("brands"));
		assertThat(chain.getFilters(2).getSynonyms().getName(), is("cars"));

		assertThat(
			storedResources.getStopwordsOrThrow("brands").getWordsList(),
			is(List.of("acme"))
		);

		var synonyms = storedResources.getSynonymsOrThrow("cars");
		assertThat(
			synonyms.getRules(0).getEquivalent().getTermsList(),
			is(List.of("car", "automobile"))
		);
		assertThat(synonyms.getRules(1).getMapping().getFromList(), is(List.of("ny")));
		assertThat(synonyms.getRules(1).getMapping().getToList(), is(List.of("new york")));

		var api = IndexDefinitionMapper.toApi(stored);
		assertThat(api.resources(), is(resources));
	}

	@Test
	public void testEmptyResourcesAreNotReturned() {
		var api = IndexDefinitionMapper.toApi(
			IndexDefinitionMapper.toStored(
				new IndexDefinition(
					null,
					null,
					null,
					null,
					new IndexDefinition.Resources(null, null, null),
					null
				)
			)
		);

		assertThat(api.resources(), is(nullValue()));
	}

	/**
	 * The resources are where names are defined, so a chain there naming
	 * another chain would be a reference with nothing behind it.
	 */
	@Test
	public void testNamedChainInResourcesIsRefused() {
		var resources = new IndexDefinition.Resources(
			Map.of("prose", new AnalyzerDefinition(null, null, "other")),
			null,
			null
		);

		assertThrows(
			EngineException.class,
			() -> IndexDefinitionMapper.toStored(
				new IndexDefinition(null, null, null, null, resources, null)
			)
		);
	}

	@Test
	public void testSynonymRuleHasToBeExactlyOneKind() {
		var resources = new IndexDefinition.Resources(
			null,
			null,
			Map.of(
				"cars",
				new IndexDefinition.Resources.Synonyms(
					List.of(new IndexDefinition.Resources.Synonyms.Rule(null, null))
				)
			)
		);

		assertThrows(
			EngineException.class,
			() -> IndexDefinitionMapper.toStored(
				new IndexDefinition(null, null, null, null, resources, null)
			)
		);
	}

	/**
	 * An analyzer that picks both a preset and a chain, or neither, is asking
	 * two different things at once and is refused.
	 */
	@Test
	public void testAnalyzerHasToBeExactlyOneKind() {
		var both = string(
			null,
			new StringFieldDefinition.TextUsage(
				new AnalyzerDefinition(
					AnalyzerDefinition.Preset.FULL_TEXT,
					new AnalyzerDefinition.Custom(null, null, null),
					null
				),
				null,
				null,
				null,
				null,
				null,
				null
			),
			null
		);

		assertThrows(
			EngineException.class,
			() -> IndexDefinitionMapper.toStored(withFields(Map.of("title", both)))
		);

		var neither = string(
			null,
			new StringFieldDefinition.TextUsage(
				new AnalyzerDefinition(null, null, null),
				null,
				null,
				null,
				null,
				null,
				null
			),
			null
		);

		assertThrows(
			EngineException.class,
			() -> IndexDefinitionMapper.toStored(withFields(Map.of("title", neither)))
		);

		var presetAndNamed = string(
			null,
			new StringFieldDefinition.TextUsage(
				new AnalyzerDefinition(AnalyzerDefinition.Preset.FULL_TEXT, null, "prose"),
				null,
				null,
				null,
				null,
				null,
				null
			),
			null
		);

		assertThrows(
			EngineException.class,
			() -> IndexDefinitionMapper.toStored(withFields(Map.of("title", presetAndNamed)))
		);
	}

	/**
	 * The setting rides along with the engine-built chain and reads back as
	 * it was sent.
	 */
	@Test
	public void testDecompoundNoneRoundTrips() {
		var field = string(
			null,
			new StringFieldDefinition.TextUsage(
				null,
				null,
				null,
				null,
				StringFieldDefinition.TextUsage.Decompound.NONE,
				null,
				null
			),
			null
		);

		var stored = IndexDefinitionMapper.toStored(withFields(Map.of("title", field)));

		var matching = stored.getFieldsOrThrow("title").getType().getString().getMatching();
		assertThat(matching.hasAnalyzer(), is(false));
		assertThat(
			matching.getDecompound(),
			is(StringFieldTypeDef.TextUsageConfig.DecompoundMode.DECOMPOUND_MODE_NONE)
		);

		var api = IndexDefinitionMapper.toApi(stored);
		assertThat(api.fields().get("title"), is(field));
	}

	/**
	 * A preset expands honouring the setting, so what is stored is a chain
	 * without the component rather than a chain and a setting fighting over
	 * it.
	 */
	@Test
	public void testDecompoundNoneIsFoldedIntoThePreset() {
		var field = string(
			null,
			new StringFieldDefinition.TextUsage(
				new AnalyzerDefinition(AnalyzerDefinition.Preset.FULL_TEXT, null, null),
				null,
				null,
				null,
				StringFieldDefinition.TextUsage.Decompound.NONE,
				null,
				null
			),
			null
		);

		var stored = IndexDefinitionMapper.toStored(withFields(Map.of("title", field)));

		var matching = stored.getFieldsOrThrow("title").getType().getString().getMatching();
		assertThat(matching.hasDecompound(), is(false));

		var chain = matching.getAnalyzer();
		assertThat(chain.getFiltersCount(), is(3));
		assertThat(chain.getFilters(0).hasNormalize(), is(true));
		assertThat(chain.getFilters(1).hasStopwords(), is(true));
		assertThat(chain.getFilters(2).hasStemming(), is(true));
	}

	/**
	 * A given chain says itself whether it splits, so the setting alongside
	 * one is two answers to the same question and is refused.
	 */
	@Test
	public void testDecompoundIsRefusedOnAGivenChain() {
		var withCustom = string(
			null,
			new StringFieldDefinition.TextUsage(
				new AnalyzerDefinition(
					null,
					new AnalyzerDefinition.Custom(null, null, null),
					null
				),
				null,
				null,
				null,
				StringFieldDefinition.TextUsage.Decompound.NONE,
				null,
				null
			),
			null
		);

		assertThrows(
			EngineException.class,
			() -> IndexDefinitionMapper.toStored(withFields(Map.of("title", withCustom)))
		);

		var withNamed = string(
			null,
			new StringFieldDefinition.TextUsage(
				new AnalyzerDefinition(null, null, "prose"),
				null,
				null,
				null,
				StringFieldDefinition.TextUsage.Decompound.NONE,
				null,
				null
			),
			null
		);

		assertThrows(
			EngineException.class,
			() -> IndexDefinitionMapper.toStored(withFields(Map.of("title", withNamed)))
		);
	}

	@Test
	public void testDecompoundComponentRoundTrips() {
		var field = string(
			null,
			new StringFieldDefinition.TextUsage(
				new AnalyzerDefinition(
					null,
					new AnalyzerDefinition.Custom(
						null,
						null,
						List.of(
							new AnalyzerDefinition.TokenFilter(
								null, null, null, null, null, null, null,
								new AnalyzerDefinition.TokenFilter.Decompound("de")
							)
						)
					),
					null
				),
				null,
				null,
				null,
				null,
				null,
				null
			),
			null
		);

		var stored = IndexDefinitionMapper.toStored(withFields(Map.of("title", field)));

		var chain = stored.getFieldsOrThrow("title").getType().getString().getMatching()
			.getAnalyzer();
		assertThat(chain.getFilters(0).hasDecompound(), is(true));
		assertThat(chain.getFilters(0).getDecompound().getLocale(), is("de"));

		var api = IndexDefinitionMapper.toApi(stored);
		assertThat(api.fields().get("title"), is(field));
	}

	@Test
	public void testChainComponentHasToBeExactlyOneKind() {
		var field = string(
			null,
			new StringFieldDefinition.TextUsage(
				new AnalyzerDefinition(
					null,
					new AnalyzerDefinition.Custom(
						null,
						null,
						List.of(
							new AnalyzerDefinition.TokenFilter(
								new AnalyzerDefinition.TokenFilter.Normalize(null),
								new AnalyzerDefinition.TokenFilter.Stopwords(
									null, null, null
								),
								null, null, null, null, null, null
							)
						)
					),
					null
				),
				null,
				null,
				null,
				null,
				null,
				null
			),
			null
		);

		assertThrows(
			EngineException.class,
			() -> IndexDefinitionMapper.toStored(withFields(Map.of("title", field)))
		);
	}

	@Test
	public void testStopwordsSourceHasToBeAtMostOneKind() {
		var field = string(
			null,
			new StringFieldDefinition.TextUsage(
				new AnalyzerDefinition(
					null,
					new AnalyzerDefinition.Custom(
						null,
						null,
						List.of(
							new AnalyzerDefinition.TokenFilter(
								null,
								new AnalyzerDefinition.TokenFilter.Stopwords(
									"en", null, "brands"
								),
								null, null, null, null, null, null
							)
						)
					),
					null
				),
				null,
				null,
				null,
				null,
				null,
				null
			),
			null
		);

		assertThrows(
			EngineException.class,
			() -> IndexDefinitionMapper.toStored(withFields(Map.of("title", field)))
		);
	}

	@Test
	public void testRankingRoundTrip() {
		var ranking = new IndexDefinition.Ranking(
			List.of(
				new IndexDefinition.Ranking.TieBreaker(
					"popularity",
					IndexDefinition.Ranking.TieBreaker.Direction.DESCENDING
				),
				new IndexDefinition.Ranking.TieBreaker("name", null)
			),
			null
		);

		var stored = IndexDefinitionMapper.toStored(
			new IndexDefinition(null, null, null, ranking, null, null)
		);

		assertThat(stored.hasRanking(), is(true));
		assertThat(stored.getRanking().getTieBreakersCount(), is(2));
		assertThat(stored.getRanking().getTieBreakers(0).getField(), is("popularity"));
		assertThat(
			stored.getRanking().getTieBreakers(0).getDirection(),
			is(RankingConfig.TieBreaker.Direction.DIRECTION_DESCENDING)
		);
		assertThat(stored.getRanking().getTieBreakers(1).hasDirection(), is(false));

		var api = IndexDefinitionMapper.toApi(stored);
		assertThat(api.ranking(), is(ranking));
	}

	@Test
	public void testRankingSignalsRoundTrip() {
		var ranking = new IndexDefinition.Ranking(
			List.of(),
			List.of(
				new IndexDefinition.Ranking.Signal(
					"purchases",
					new IndexDefinition.Ranking.Signal.Saturation(50.0),
					null,
					0.5f
				),
				new IndexDefinition.Ranking.Signal(
					"published",
					null,
					new IndexDefinition.Ranking.Signal.Decay(604800L),
					null
				)
			)
		);

		var stored = IndexDefinitionMapper.toStored(
			new IndexDefinition(null, null, null, ranking, null, null)
		);

		assertThat(stored.getRanking().getSignalsCount(), is(2));
		assertThat(stored.getRanking().getSignals(0).getField(), is("purchases"));
		assertThat(stored.getRanking().getSignals(0).getSaturation().getPivot(), is(50.0));
		assertThat(stored.getRanking().getSignals(0).getWeight(), is(0.5f));
		assertThat(stored.getRanking().getSignals(1).getDecay().getHalfLifeSeconds(), is(604800L));
		assertThat(stored.getRanking().getSignals(1).hasWeight(), is(false));

		var api = IndexDefinitionMapper.toApi(stored);
		assertThat(api.ranking(), is(ranking));
	}

	@Test
	public void testSignalOfTwoShapesIsRefused() {
		var ranking = new IndexDefinition.Ranking(
			null,
			List.of(
				new IndexDefinition.Ranking.Signal(
					"purchases",
					new IndexDefinition.Ranking.Signal.Saturation(50.0),
					new IndexDefinition.Ranking.Signal.Decay(604800L),
					null
				)
			)
		);

		assertThrows(
			EngineException.class,
			() -> IndexDefinitionMapper.toStored(
				new IndexDefinition(null, null, null, ranking, null, null)
			)
		);
	}

	@Test
	public void testSignalOfNoShapeIsRefused() {
		var ranking = new IndexDefinition.Ranking(
			null,
			List.of(new IndexDefinition.Ranking.Signal("purchases", null, null, null))
		);

		assertThrows(
			EngineException.class,
			() -> IndexDefinitionMapper.toStored(
				new IndexDefinition(null, null, null, ranking, null, null)
			)
		);
	}

	/**
	 * Resources holding nothing read back as no resources, so they are not
	 * stored as an empty message either - the same index would otherwise be
	 * stored two ways.
	 */
	@Test
	public void testEmptyResourcesAreNotStored() {
		var stored = IndexDefinitionMapper.toStored(
			new IndexDefinition(
				null,
				null,
				null,
				null,
				new IndexDefinition.Resources(null, null, null),
				null
			)
		);

		assertThat(stored.hasResources(), is(false));
		IndexDefinitionMapper.checkRepresentable(stored);
	}

	/**
	 * Everything this version of the API can describe has to survive being read
	 * and written back, or an update would refuse definitions it wrote itself.
	 */
	@Test
	public void testRepresentableDefinitionIsAccepted() {
		var definition = new IndexDefinition(
			IndexDefinition.Source.FULL,
			Map.of("owner", "search"),
			Map.of(
				"title",
				string(
					new StringFieldDefinition.Keyword(true),
					new StringFieldDefinition.TextUsage(
						new AnalyzerDefinition(AnalyzerDefinition.Preset.FULL_TEXT, null, null),
						2.0f,
						new StringFieldDefinition.TextUsage.Highlight(),
						new StringFieldDefinition.TextUsage.TypoTolerance(4, 8, 1),
						null,
						new StringFieldDefinition.TextUsage.Exact(1.5f),
						StringFieldDefinition.TextUsage.LengthNormalization.MODERATE
					),
					new StringFieldDefinition.TextUsage(
						new AnalyzerDefinition(null, null, "prose"),
						null, null, null, null, null, null
					)
				),
				"summary",
				new StringFieldDefinition(
					null,
					null,
					null,
					true,
					new FieldDefinition.Locales(
						"en",
						List.of("en", "sv"),
						FieldDefinition.Locales.Fallback.DISABLED
					),
					null,
					new FieldDefinition.Sort(
						FieldDefinition.Sort.Collation.LOCALE,
						FieldDefinition.Sort.Missing.LAST
					),
					null,
					null,
					new StringFieldDefinition.TextUsage(
						null,
						null,
						null,
						null,
						StringFieldDefinition.TextUsage.Decompound.NONE,
						null,
						null
					),
					null,
					null
				),
				"published",
				new TimestampFieldDefinition(
					null, null, null, null, null,
					new FieldDefinition.Filter(),
					null,
					new FieldDefinition.Facet()
				),
				"purchases",
				new Int64FieldDefinition(
					null, null, null, null, null, null, null, null,
					new Int64FieldDefinition.Validation(0L, null)
				),
				"embedding",
				new VectorFieldDefinition(
					null, null, null, null, null, null, null, null,
					384,
					VectorFieldDefinition.Similarity.COSINE,
					new VectorFieldDefinition.Hnsw(16, 100),
					VectorFieldDefinition.Quantization.INT8
				),
				"author",
				new ObjectFieldDefinition(
					null, null, null, null, null, null, null, null,
					Map.of("name", string(null, null, null))
				)
			),
			new IndexDefinition.Ranking(
				List.of(
					new IndexDefinition.Ranking.TieBreaker(
						"published",
						IndexDefinition.Ranking.TieBreaker.Direction.DESCENDING
					)
				),
				List.of(
					new IndexDefinition.Ranking.Signal(
						"purchases",
						new IndexDefinition.Ranking.Signal.Saturation(50.0),
						null,
						0.5f
					)
				)
			),
			new IndexDefinition.Resources(
				Map.of("prose", new AnalyzerDefinition(AnalyzerDefinition.Preset.FULL_TEXT, null, null)),
				Map.of("brands", List.of("acme")),
				Map.of(
					"cars",
					new IndexDefinition.Resources.Synonyms(
						List.of(
							new IndexDefinition.Resources.Synonyms.Rule(
								List.of("car", "automobile"),
								null
							)
						)
					)
				)
			),
			new IndexDefinition.LocaleFallback(List.of("en"))
		);

		IndexDefinitionMapper.checkRepresentable(IndexDefinitionMapper.toStored(definition));
	}

	/**
	 * The features are worked out again every time a definition is stored, so
	 * they are not something an update drops.
	 */
	@Test
	public void testRequiredFeaturesAreNotState() {
		var stored = IndexDefinitionMapper
			.toStored(withFields(Map.of("title", string(null, null, null))))
			.toBuilder()
			.addRequiredFeatures("field.string")
			.build();

		IndexDefinitionMapper.checkRepresentable(stored);
	}

	/**
	 * A field type only a newer version has a name for. The type is what carries
	 * the values, so there is nothing to describe the field as at all.
	 */
	@Test
	public void testUnknownFieldTypeIsRefused() {
		var stored = IndexDef.newBuilder()
			.putFields("title", FieldDef.getDefaultInstance())
			.build();

		var e = assertThrows(
			UnrepresentableStateException.class,
			() -> IndexDefinitionMapper.checkRepresentable(stored)
		);

		assertThat(e.getCode(), is("index:field:unrepresentable_type"));
	}

	/**
	 * A setting whose value comes from a newer version reads as unset, which is
	 * exactly the value an update would store over it.
	 */
	@Test
	public void testUnknownSettingValueIsRefused() {
		var stored = IndexDef.newBuilder()
			.setSourceValue(4242)
			.build();

		var e = assertThrows(
			UnrepresentableStateException.class,
			() -> IndexDefinitionMapper.checkRepresentable(stored)
		);

		assertThat(e.getCode(), is("index:definition:unrepresentable"));
	}

	/**
	 * A part of the stored format this version has no name for. Protobuf keeps
	 * it, and building a fresh definition is what would drop it.
	 */
	@Test
	public void testUnknownStoredPartIsRefused() {
		var stored = IndexDef.newBuilder()
			.setUnknownFields(
				UnknownFieldSet.newBuilder()
					.addField(
						4242,
						UnknownFieldSet.Field.newBuilder().addVarint(1).build()
					)
					.build()
			)
			.build();

		var e = assertThrows(
			UnrepresentableStateException.class,
			() -> IndexDefinitionMapper.checkRepresentable(stored)
		);

		assertThat(e.getCode(), is("index:definition:unrepresentable"));
	}

	/**
	 * A combination the API model can not hold, here a chain named alongside a
	 * setting that only an expanded chain takes.
	 */
	@Test
	public void testUnrepresentableCombinationIsRefused() {
		var stored = IndexDef.newBuilder()
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
											.setDecompound(
												StringFieldTypeDef.TextUsageConfig
													.DecompoundMode.DECOMPOUND_MODE_NONE
											)
									)
							)
					)
					.build()
			)
			.build();

		var e = assertThrows(
			UnrepresentableStateException.class,
			() -> IndexDefinitionMapper.checkRepresentable(stored)
		);

		assertThat(e.getCode(), is("index:definition:unrepresentable"));
	}
}
