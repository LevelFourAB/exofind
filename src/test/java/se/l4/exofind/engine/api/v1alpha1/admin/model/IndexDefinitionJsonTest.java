package se.l4.exofind.engine.api.v1alpha1.admin.model;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.datatype.eclipsecollections.EclipseCollectionsModule;

import se.l4.exofind.engine.index.IndexState;
import se.l4.exofind.engine.index.LuceneCompatibility;
import org.junit.jupiter.api.Assertions;

/**
 * Tests for how definitions look on the wire, using the same mapper as the
 * API.
 */
public class IndexDefinitionJsonTest {
	private final ObjectMapper mapper = new ObjectMapper()
		.registerModule(new EclipseCollectionsModule());

	@Test
	public void testReadStringField() throws Exception {
		var json = """
			{
				"metadata": { "owner": "search" },
				"fields": {
					"id": {
						"type": "string",
						"primaryKey": true,
						"filter": {}
					},
					"name": {
						"type": "string",
						"locales": { "defaultLocale": "en" },
						"keyword": { "caseFolding": false },
						"matching": {
							"highlight": {}
						},
						"autocomplete": {},
						"sort": { "collation": "locale", "missing": "first" },
						"facet": {}
					}
				}
			}
			""";

		var definition = mapper.readValue(json, IndexDefinition.class);

		assertThat(definition.metadata(), is(Map.of("owner", "search")));

		var id = (StringFieldDefinition) definition.fields().get("id");
		assertThat(id.primaryKey(), is(true));
		assertThat(id.filter(), is(new FieldDefinition.Filter()));
		assertThat(id.matching(), is(nullValue()));

		var name = (StringFieldDefinition) definition.fields().get("name");
		assertThat(name.locales(), is(new FieldDefinition.Locales("en", null, null)));
		assertThat(name.keyword(), is(new StringFieldDefinition.Keyword(false)));
		assertThat(
			name.matching(),
			is(
				new StringFieldDefinition.TextUsage(
					null,
					null,
					new StringFieldDefinition.TextUsage.Highlight(),
					null,
					null,
					null,
					null
				)
			)
		);
		assertThat(
			name.autocomplete(),
			is(new StringFieldDefinition.TextUsage(null, null, null, null, null, null, null))
		);
		assertThat(
			name.sort(),
			is(
				new FieldDefinition.Sort(
					FieldDefinition.Sort.Collation.LOCALE,
					FieldDefinition.Sort.Missing.FIRST
				)
			)
		);
		assertThat(name.facet(), is(new FieldDefinition.Facet()));
	}

	@Test
	public void testReadAnalyzer() throws Exception {
		var json = """
			{
				"fields": {
					"sku": {
						"type": "string",
						"matching": {
							"analyzer": { "preset": "preserve_terms" }
						}
					},
					"model": {
						"type": "string",
						"matching": {
							"analyzer": {
								"custom": {
									"charFilters": [ { "mapping": { "mappings": { "-": "" } } } ],
									"tokenizer": { "whitespace": {} },
									"filters": [
										{ "normalize": {} },
										{ "stopwords": { "words": ["spare"] } },
										{ "stemming": { "locale": "en" } }
									]
								}
							}
						}
					}
				}
			}
			""";

		var definition = mapper.readValue(json, IndexDefinition.class);

		var sku = (StringFieldDefinition) definition.fields().get("sku");
		assertThat(
			sku.matching().analyzer().preset(),
			is(AnalyzerDefinition.Preset.PRESERVE_TERMS)
		);
		assertThat(sku.matching().analyzer().custom(), is(nullValue()));

		var model = (StringFieldDefinition) definition.fields().get("model");
		var custom = model.matching().analyzer().custom();
		assertThat(
			custom.charFilters(),
			is(
				List.of(
					new AnalyzerDefinition.CharFilter(
						null,
						new AnalyzerDefinition.CharFilter.Mapping(Map.of("-", "")),
						null
					)
				)
			)
		);
		assertThat(
			custom.tokenizer(),
			is(
				new AnalyzerDefinition.Tokenizer(
					null,
					new AnalyzerDefinition.Tokenizer.Whitespace(),
					null,
					null
				)
			)
		);
		assertThat(
			custom.filters(),
			is(
				List.of(
					new AnalyzerDefinition.TokenFilter(
						new AnalyzerDefinition.TokenFilter.Normalize(null),
						null, null, null, null, null, null, null
					),
					new AnalyzerDefinition.TokenFilter(
						null,
						new AnalyzerDefinition.TokenFilter.Stopwords(
							null, List.of("spare"), null
						),
						null, null, null, null, null, null
					),
					new AnalyzerDefinition.TokenFilter(
						null, null,
						new AnalyzerDefinition.TokenFilter.Stemming("en"),
						null, null, null, null, null
					)
				)
			)
		);
	}

	@Test
	public void testReadLocales() throws Exception {
		var json = """
			{
				"fields": {
					"name": {
						"type": "string",
						"locales": { "defaultLocale": "en", "locales": ["sv", "de"] }
					}
				}
			}
			""";

		var definition = mapper.readValue(json, IndexDefinition.class);

		var name = (StringFieldDefinition) definition.fields().get("name");
		assertThat(
			name.locales(),
			is(new FieldDefinition.Locales("en", List.of("sv", "de"), null))
		);
	}

	@Test
	public void testReadLocaleFallback() throws Exception {
		var json = """
			{
				"localeFallback": { "chain": ["da", "en"] },
				"fields": {
					"name": {
						"type": "string",
						"locales": {
							"defaultLocale": "en",
							"locales": ["da", "no"],
							"fallback": "disabled"
						}
					}
				}
			}
			""";

		var definition = mapper.readValue(json, IndexDefinition.class);

		assertThat(
			definition.localeFallback(),
			is(new IndexDefinition.LocaleFallback(List.of("da", "en")))
		);

		var name = (StringFieldDefinition) definition.fields().get("name");
		assertThat(
			name.locales().fallback(),
			is(FieldDefinition.Locales.Fallback.DISABLED)
		);
	}

	@Test
	public void testWriteLocaleFallback() throws Exception {
		var definition = new IndexDefinition(
			null,
			null,
			null,
			null,
			null,
			new IndexDefinition.LocaleFallback(List.of("da", "en"))
		);

		assertThat(
			mapper.writeValueAsString(definition),
			is("{\"localeFallback\":{\"chain\":[\"da\",\"en\"]}}")
		);
	}

	@Test
	public void testReadResources() throws Exception {
		var json = """
			{
				"resources": {
					"analyzers": {
						"prose": {
							"custom": {
								"filters": [
									{ "normalize": {} },
									{ "stopwords": { "named": "brands" } },
									{ "synonyms": { "named": "cars" } }
								]
							}
						}
					},
					"stopwords": { "brands": [ "acme" ] },
					"synonyms": {
						"cars": {
							"rules": [
								{ "equivalent": [ "car", "automobile" ] },
								{ "mapping": { "from": [ "ny" ], "to": [ "new york" ] } }
							]
						}
					}
				},
				"fields": {
					"name": {
						"type": "string",
						"matching": { "analyzer": { "named": "prose" } }
					}
				}
			}
			""";

		var definition = mapper.readValue(json, IndexDefinition.class);

		var chain = definition.resources().analyzers().get("prose").custom();
		assertThat(
			chain.filters().get(1).stopwords(),
			is(new AnalyzerDefinition.TokenFilter.Stopwords(null, null, "brands"))
		);
		assertThat(
			chain.filters().get(2).synonyms(),
			is(new AnalyzerDefinition.TokenFilter.Synonyms("cars"))
		);

		assertThat(definition.resources().stopwords(), is(Map.of("brands", List.of("acme"))));

		assertThat(
			definition.resources().synonyms().get("cars").rules(),
			is(
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
		);

		var name = (StringFieldDefinition) definition.fields().get("name");
		assertThat(name.matching().analyzer().named(), is("prose"));
	}

	@Test
	public void testWriteResources() throws Exception {
		var definition = new IndexDefinition(
			null,
			null,
			null,
			null,
			new IndexDefinition.Resources(
				null,
				Map.of("brands", List.of("acme")),
				null
			),
			null
		);

		assertThat(
			mapper.writeValueAsString(definition),
			is("{\"resources\":{\"stopwords\":{\"brands\":[\"acme\"]}}}")
		);
	}

	@Test
	public void testReadBooleanField() throws Exception {
		var json = """
			{
				"fields": {
					"published": { "type": "boolean", "filter": {} }
				}
			}
			""";

		var definition = mapper.readValue(json, IndexDefinition.class);

		var published = definition.fields().get("published");
		assertThat(published, instanceOf(BooleanFieldDefinition.class));
		assertThat(published.filter(), is(new FieldDefinition.Filter()));
	}

	@Test
	public void testWriteBooleanField() throws Exception {
		var definition = new IndexDefinition(
			null,
			null,
			Map.of(
				"published",
				new BooleanFieldDefinition(
					null, null, null, null, null,
					new FieldDefinition.Filter(),
					null,
					null
				)
			),
			null,
			null,
			null
		);

		assertThat(
			mapper.writeValueAsString(definition),
			is("{\"fields\":{\"published\":{\"type\":\"boolean\",\"filter\":{}}}}")
		);
	}

	@Test
	public void testReadVectorField() throws Exception {
		var json = """
			{
				"fields": {
					"embedding": {
						"type": "vector",
						"dimensions": 1536,
						"similarity": "dot_product",
						"hnsw": { "m": 32, "efConstruction": 200 },
						"quantization": "int8"
					}
				}
			}
			""";

		var definition = mapper.readValue(json, IndexDefinition.class);

		var embedding = definition.fields().get("embedding");
		assertThat(embedding, instanceOf(VectorFieldDefinition.class));

		var vector = (VectorFieldDefinition) embedding;
		assertThat(vector.dimensions(), is(1536));
		assertThat(vector.similarity(), is(VectorFieldDefinition.Similarity.DOT_PRODUCT));
		assertThat(vector.hnsw(), is(new VectorFieldDefinition.Hnsw(32, 200)));
		assertThat(vector.quantization(), is(VectorFieldDefinition.Quantization.INT8));
	}

	@Test
	public void testWriteVectorField() throws Exception {
		var definition = new IndexDefinition(
			null,
			null,
			Map.of(
				"embedding",
				new VectorFieldDefinition(
					null, null, null, null, null,
					null, null, null,
					4,
					VectorFieldDefinition.Similarity.COSINE,
					null,
					VectorFieldDefinition.Quantization.INT4
				)
			),
			null,
			null,
			null
		);

		assertThat(
			mapper.writeValueAsString(definition),
			is(
				"{\"fields\":{\"embedding\":{\"type\":\"vector\",\"dimensions\":4,"
					+ "\"similarity\":\"cosine\",\"quantization\":\"int4\"}}}"
			)
		);
	}

	/**
	 * The names of the enums are part of the contract, so they are written the
	 * way they are read rather than as the Java constant.
	 */
	@Test
	public void testWriteSort() throws Exception {
		var definition = new IndexDefinition(
			null,
			null,
			Map.of(
				"title",
				new StringFieldDefinition(
					null, null, null, null, null,
					null,
					new FieldDefinition.Sort(
						FieldDefinition.Sort.Collation.BINARY,
						FieldDefinition.Sort.Missing.LAST
					),
					null,
					null,
					null,
					null,
					null
				)
			),
			null,
			null,
			null
		);

		assertThat(
			mapper.writeValueAsString(definition),
			is(
				"{\"fields\":{\"title\":{\"type\":\"string\","
					+ "\"sort\":{\"collation\":\"binary\",\"missing\":\"last\"}}}}"
			)
		);
	}

	@Test
	public void testReadRankingAndTypoTolerance() throws Exception {
		var json = """
			{
				"fields": {
					"name": {
						"type": "string",
						"matching": {
							"weight": 3,
							"typoTolerance": { "minLengthOneTypo": 4 }
						}
					}
				},
				"ranking": {
					"tieBreakers": [
						{ "field": "popularity", "direction": "descending" },
						{ "field": "name" }
					]
				}
			}
			""";

		var definition = mapper.readValue(json, IndexDefinition.class);

		var name = (StringFieldDefinition) definition.fields().get("name");
		assertThat(
			name.matching(),
			is(
				new StringFieldDefinition.TextUsage(
					null,
					3f,
					null,
					new StringFieldDefinition.TextUsage.TypoTolerance(4, null, null),
					null,
					null,
					null
				)
			)
		);

		assertThat(
			definition.ranking(),
			is(
				new IndexDefinition.Ranking(
					java.util.List.of(
						new IndexDefinition.Ranking.TieBreaker(
							"popularity",
							IndexDefinition.Ranking.TieBreaker.Direction.DESCENDING
						),
						new IndexDefinition.Ranking.TieBreaker("name", null)
					),
					null
				)
			)
		);
	}

	@Test
	public void testWriteRanking() throws Exception {
		var definition = new IndexDefinition(
			null,
			null,
			null,
			new IndexDefinition.Ranking(
				java.util.List.of(
					new IndexDefinition.Ranking.TieBreaker(
						"popularity",
						IndexDefinition.Ranking.TieBreaker.Direction.ASCENDING
					)
				),
				null
			),
			null,
			null
		);

		assertThat(
			mapper.writeValueAsString(definition),
			is(
				"{\"ranking\":{\"tieBreakers\":"
					+ "[{\"field\":\"popularity\",\"direction\":\"ascending\"}]}}"
			)
		);
	}

	@Test
	public void testUnknownTypeIsRejected() {
		var json = """
			{
				"fields": {
					"price": { "type": "decimal" }
				}
			}
			""";

		Assertions.assertThrows(
			InvalidTypeIdException.class,
			() -> mapper.readValue(json, IndexDefinition.class)
		);
	}

	@Test
	public void testUnknownPropertyIsRejected() {
		var json = """
			{
				"fields": {
					"name": { "type": "string", "indexed": true }
				}
			}
			""";

		Assertions.assertThrows(
			UnrecognizedPropertyException.class,
			() -> mapper.readValue(json, IndexDefinition.class)
		);
	}

	@Test
	public void testWriteOnlyIncludesWhatIsSet() throws Exception {
		var definition = new IndexDefinition(
			null,
			null,
			Map.of(
				"name",
				new StringFieldDefinition(
					null, null, null, true, null,
					null, null, null,
					null,
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
				)
			),
			null,
			null,
			null
		);

		assertThat(
			mapper.writeValueAsString(definition),
			is(
				"{\"fields\":{\"name\":{\"type\":\"string\",\"stored\":true,"
					+ "\"matching\":{\"highlight\":{}}}}}"
			)
		);
	}

	@Test
	public void testWriteIndexInfo() throws Exception {
		var info = new IndexInfo(
			"books",
			"2",
			true,
			"abc123",
			new IndexDefinition(null, null, Map.of(), null, null, null),
			new IndexStatus(
				IndexState.USABLE,
				false,
				new IndexerInfo("node-1", "http://node-1:8080"),
				LuceneCompatibility.CURRENT,
				10
			),
			List.of(new GenerationSummary("2", true, "2026-08-16T10:00:00Z"))
		);

		assertThat(
			mapper.writeValueAsString(info),
			is(
				"{\"name\":\"books\",\"generation\":\"2\",\"live\":true,"
					+ "\"version\":\"abc123\",\"definition\":{\"fields\":{}},"
					+ "\"status\":{\"state\":\"USABLE\",\"readOnly\":false,"
					+ "\"indexer\":{\"node\":\"node-1\",\"address\":\"http://node-1:8080\"},"
					+ "\"luceneCompatibility\":\"CURRENT\",\"luceneCreatedMajor\":10},"
					+ "\"generations\":[{\"name\":\"2\",\"live\":true,"
					+ "\"createdAt\":\"2026-08-16T10:00:00Z\"}]}"
			)
		);
	}

	@Test
	public void testWriteIndexStatusWithoutLuceneVersion() throws Exception {
		var status = new IndexStatus(
			IndexState.NEEDS_PULL, true, null, LuceneCompatibility.UNKNOWN, null
		);

		assertThat(
			mapper.writeValueAsString(status),
			is(
				"{\"state\":\"NEEDS_PULL\",\"readOnly\":true,"
					+ "\"luceneCompatibility\":\"UNKNOWN\"}"
			)
		);
	}
}
