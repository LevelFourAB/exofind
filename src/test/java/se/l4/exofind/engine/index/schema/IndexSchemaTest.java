package se.l4.exofind.engine.index.schema;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.types.BooleanFieldType;
import se.l4.exofind.engine.query.SaturationSignal;

public class IndexSchemaTest {
	@Test
	public void testEmptySchema() {
		IndexSchema schema = new IndexSchema();
		assertThat(schema.getFields().isEmpty(), is(true));
	}

	@Test
	public void testCanNotSetInvalidDef() {
		IndexSchema schema = new IndexSchema();

		IndexDef.Builder builder = IndexDef.newBuilder();
		builder.putFields(
			"field-name",
			FieldDef.newBuilder()
				.setType(
					FieldTypeDef.newBuilder().setBoolean(BooleanFieldTypeDef.getDefaultInstance())
				)
				.build()
		);

		try {
			schema.setDefinition(builder.build());
		} catch(ValidationException e) {
			assertThat(e.getErrors().size(), is(1));
			assertThat(e.getErrors().get(0).getCode(), is("index:field:invalid_name"));
		}
	}

	@Test
	public void testGetFields() {
		IndexSchema schema = new IndexSchema();

		IndexDef.Builder builder = IndexDef.newBuilder();
		builder.putFields(
			"field1",
			FieldDef.newBuilder()
				.setType(
					FieldTypeDef.newBuilder().setBoolean(BooleanFieldTypeDef.getDefaultInstance())
				)
				.build()
		);
		builder.putFields(
			"field2",
			FieldDef.newBuilder()
				.setType(
					FieldTypeDef.newBuilder().setBoolean(BooleanFieldTypeDef.getDefaultInstance())
				)
				.build()
		);
		builder.putFields(
			"field3",
			FieldDef.newBuilder()
				.setType(
					FieldTypeDef.newBuilder().setBoolean(BooleanFieldTypeDef.getDefaultInstance())
				)
				.build()
		);
		schema.setDefinition(builder.build());

		assertThat(schema.getFields().size(), is(3));
		assertThat(schema.getFields().get(0).getName(), is("field1"));
		assertThat(schema.getFields().get(1).getName(), is("field2"));
		assertThat(schema.getFields().get(2).getName(), is("field3"));
	}

	@Test
	public void testMatchFieldWildcard() {
		IndexSchema schema = new IndexSchema();

		IndexDef.Builder builder = IndexDef.newBuilder();
		builder.putFields(
			"field*",
			FieldDef.newBuilder()
				.setType(
					FieldTypeDef.newBuilder().setBoolean(BooleanFieldTypeDef.getDefaultInstance())
				)
				.setStored(true)
				.build()
		);
		builder.putFields(
			"field2",
			FieldDef.newBuilder()
				.setType(
					FieldTypeDef.newBuilder().setBoolean(BooleanFieldTypeDef.getDefaultInstance())
				)
				.setStored(false)
				.build()
		);
		schema.setDefinition(builder.build());

		var field1 = schema.getField("field1");
		assertThat(field1.isPresent(), is(true));
		assertThat(field1.get().getName(), is("field*"));
		assertThat(field1.get().getType(), instanceOf(BooleanFieldType.class));
		assertThat(field1.get().isStored(), is(true));

		var field2 = schema.getField("field2");
		assertThat(field2.isPresent(), is(true));
		assertThat(field2.get().getName(), is("field2"));
		assertThat(field2.get().getType(), instanceOf(BooleanFieldType.class));
		assertThat(field2.get().isStored(), is(false));
	}

	/**
	 * Which pattern a name matching several wildcard fields resolves to is
	 * contract: an exact field always wins, then the pattern with the longer
	 * literal prefix, and on an equal prefix the shorter pattern. Callers
	 * build on this ordering, so a change to it has to fail here rather than
	 * quietly moving names to another field.
	 */
	@Test
	public void testWildcardPrecedence() {
		IndexSchema schema = new IndexSchema();

		var builder = IndexDef.newBuilder();
		for(var name : new String[] { "*", "a.*", "a.b*", "a.*x", "a.exact" }) {
			builder.putFields(
				name,
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder().setBoolean(BooleanFieldTypeDef.getDefaultInstance())
					)
					.build()
			);
		}
		schema.setDefinition(builder.build());

		// An exact field wins over every pattern that also covers the name
		assertThat(schema.getField("a.exact").orElseThrow().getName(), is("a.exact"));

		// `a.bc` matches both `a.*` and `a.b*` - the longer literal prefix wins
		assertThat(schema.getField("a.bc").orElseThrow().getName(), is("a.b*"));

		// `a.foox` matches both `a.*` and `a.*x` - on an equal prefix the shorter pattern wins
		assertThat(schema.getField("a.foox").orElseThrow().getName(), is("a.*"));

		// A name only the catch-all covers falls through to it
		assertThat(schema.getField("z").orElseThrow().getName(), is("*"));

		// `*` stands for a single segment, so a dotted name matches none of these
		assertThat(schema.getField("x.y.z").isPresent(), is(false));
	}

	@Test
	public void testFieldWithoutTypeIsRejected() {
		IndexSchema schema = new IndexSchema();

		var definition = IndexDef.newBuilder()
			.putFields("field1", FieldDef.newBuilder().build())
			.build();

		var e = assertThrows(ValidationException.class, () -> schema.setDefinition(definition));
		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:field:missing_type"));
	}

	/**
	 * A type written by a newer version of the engine has no case in this
	 * build's stored format, so parsing keeps it only as an unknown field and
	 * the type reads as not set at all. Refusing it has to point at the field
	 * rather than fail the whole index; the features the definition records
	 * are what name the missing capability - see {@link IndexFeatures}.
	 */
	@Test
	public void testTypeFromANewerVersionIsRejected() throws Exception {
		IndexSchema schema = new IndexSchema();

		// A FieldTypeDef holding only an unknown oneof case: field 99, empty message
		var newerType = FieldTypeDef.parseFrom(new byte[] { (byte) 0x9A, 0x06, 0x00 });

		var definition = IndexDef.newBuilder()
			.putFields(
				"count",
				FieldDef.newBuilder()
					.setType(newerType)
					.build()
			)
			.build();

		var e = assertThrows(ValidationException.class, () -> schema.setDefinition(definition));
		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:field:missing_type"));
	}

	@Test
	public void testPrimaryKeyOnTypeThatCanNotBeOneIsRejected() {
		IndexSchema schema = new IndexSchema();

		var definition = IndexDef.newBuilder()
			.putFields(
				"published",
				FieldDef.newBuilder()
					.setPrimaryKey(true)
					.setType(
						FieldTypeDef.newBuilder()
							.setBoolean(BooleanFieldTypeDef.getDefaultInstance())
					)
					.build()
			)
			.build();

		var e = assertThrows(ValidationException.class, () -> schema.setDefinition(definition));
		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:field:invalid_primary_key_type"));
	}

	@Test
	public void testSortableAndMultipleIsRejected() {
		IndexSchema schema = new IndexSchema();

		var definition = IndexDef.newBuilder()
			.putFields(
				"tags",
				FieldDef.newBuilder()
					.setMultiple(true)
					.setSort(SortConfig.getDefaultInstance())
					.setType(
						FieldTypeDef.newBuilder()
							.setString(StringFieldTypeDef.getDefaultInstance())
					)
					.build()
			)
			.build();

		var e = assertThrows(ValidationException.class, () -> schema.setDefinition(definition));
		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:field:invalid_sortable"));
	}

	/**
	 * Options that only mean something for text should not quietly do nothing
	 * on a type that has none.
	 */
	@Test
	public void testCollationOnABooleanIsRejected() {
		IndexSchema schema = new IndexSchema();

		var definition = IndexDef.newBuilder()
			.putFields(
				"published",
				FieldDef.newBuilder()
					.setSort(
						SortConfig.newBuilder()
							.setCollation(SortConfig.Collation.COLLATION_LOCALE)
					)
					.setType(
						FieldTypeDef.newBuilder()
							.setBoolean(BooleanFieldTypeDef.getDefaultInstance())
					)
					.build()
			)
			.build();

		var e = assertThrows(ValidationException.class, () -> schema.setDefinition(definition));
		assertThat(e.getErrors().size(), is(1));
		assertThat(
			e.getErrors().get(0).getCode(),
			is("index:field:sort:collation_not_supported")
		);
	}

	/**
	 * A locale is a name for rules this build has to actually have - one it
	 * does not is refused rather than analyzed by the wrong rules.
	 */
	@Test
	public void testUnsupportedDefaultLocaleIsRejected() {
		IndexSchema schema = new IndexSchema();

		var definition = IndexDef.newBuilder()
			.putFields(
				"title",
				FieldDef.newBuilder()
					.setLocales(
						FieldDef.LocaleConfig.newBuilder().setDefaultLocale("xx")
					)
					.setType(
						FieldTypeDef.newBuilder()
							.setString(StringFieldTypeDef.getDefaultInstance())
					)
					.build()
			)
			.build();

		var e = assertThrows(ValidationException.class, () -> schema.setDefinition(definition));
		assertThat(e.getErrors().size(), is(1));
		assertThat(
			e.getErrors().get(0).getCode(),
			is("index:field:locales:unsupported_locale")
		);
	}

	@Test
	public void testUnsupportedDeclaredLocaleIsRejected() {
		IndexSchema schema = new IndexSchema();

		var definition = IndexDef.newBuilder()
			.putFields(
				"title",
				FieldDef.newBuilder()
					.setLocales(
						FieldDef.LocaleConfig.newBuilder()
							.setDefaultLocale("en")
							.addLocales("sv")
							.addLocales("xx")
					)
					.setType(
						FieldTypeDef.newBuilder()
							.setString(StringFieldTypeDef.getDefaultInstance())
					)
					.build()
			)
			.build();

		var e = assertThrows(ValidationException.class, () -> schema.setDefinition(definition));
		assertThat(e.getErrors().size(), is(1));
		assertThat(
			e.getErrors().get(0).getCode(),
			is("index:field:locales:unsupported_locale")
		);
	}

	/**
	 * A reference into the resources with nothing behind it can only be a
	 * mistake, and one that would otherwise only surface when a document is
	 * indexed.
	 */
	@Test
	public void testUnknownAnalyzerRefIsRejected() {
		IndexSchema schema = new IndexSchema();

		var definition = IndexDef.newBuilder()
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

		var e = assertThrows(ValidationException.class, () -> schema.setDefinition(definition));
		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:field:analyzer:unknown_ref"));
	}

	@Test
	public void testUnknownNamedStopwordsAreRejected() {
		IndexSchema schema = new IndexSchema();

		var definition = IndexDef.newBuilder()
			.putFields(
				"title",
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
															.setStopwords(
																TokenFilterDef.Stopwords
																	.newBuilder()
																	.setNamed(
																		TokenFilterDef.Stopwords.NamedWords
																			.newBuilder()
																			.setName("brands")
																	)
															)
													)
											)
									)
							)
					)
					.build()
			)
			.build();

		var e = assertThrows(ValidationException.class, () -> schema.setDefinition(definition));
		assertThat(e.getErrors().size(), is(1));
		assertThat(
			e.getErrors().get(0).getCode(),
			is("index:field:analyzer:unknown_stopwords")
		);
	}

	@Test
	public void testUnknownSynonymSetIsRejected() {
		IndexSchema schema = new IndexSchema();

		var definition = IndexDef.newBuilder()
			.putFields(
				"title",
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
															.setSynonyms(
																TokenFilterDef.Synonyms
																	.newBuilder()
																	.setName("cars")
															)
													)
											)
									)
							)
					)
					.build()
			)
			.build();

		var e = assertThrows(ValidationException.class, () -> schema.setDefinition(definition));
		assertThat(e.getErrors().size(), is(1));
		assertThat(
			e.getErrors().get(0).getCode(),
			is("index:field:analyzer:unknown_synonyms")
		);
	}

	/**
	 * A chain among the resources is checked the way one on a field is, so a
	 * broken chain is refused wherever it sits.
	 */
	@Test
	public void testChainsInResourcesAreValidated() {
		IndexSchema schema = new IndexSchema();

		var definition = IndexDef.newBuilder()
			.setResources(
				ResourcesDef.newBuilder()
					.putAnalyzers(
						"prose",
						AnalyzerDef.newBuilder()
							.addFilters(
								TokenFilterDef.newBuilder()
									.setStemming(
										TokenFilterDef.Stemming.newBuilder().setLocale("xx")
									)
							)
							.build()
					)
			)
			.build();

		var e = assertThrows(ValidationException.class, () -> schema.setDefinition(definition));
		assertThat(e.getErrors().size(), is(1));
		assertThat(
			e.getErrors().get(0).getCode(),
			is("index:field:analyzer:unsupported_locale")
		);
	}

	/**
	 * Naming a locale outright is a promise the build has to keep, so a
	 * locale without decompounding data is refused - unlike the unset form,
	 * which follows the value and quietly passes such locales through.
	 */
	@Test
	public void testDecompoundingNeedsDataForANamedLocale() {
		IndexSchema schema = new IndexSchema();

		var definition = IndexDef.newBuilder()
			.setResources(
				ResourcesDef.newBuilder()
					.putAnalyzers(
						"prose",
						AnalyzerDef.newBuilder()
							.addFilters(
								TokenFilterDef.newBuilder()
									.setDecompound(
										TokenFilterDef.Decompound.newBuilder().setLocale("th")
									)
							)
							.build()
					)
			)
			.build();

		var e = assertThrows(ValidationException.class, () -> schema.setDefinition(definition));
		assertThat(e.getErrors().size(), is(1));
		assertThat(
			e.getErrors().get(0).getCode(),
			is("index:field:analyzer:unsupported_decompounding")
		);
	}

	@Test
	public void testSynonymRulesHaveToSaySomething() {
		IndexSchema schema = new IndexSchema();

		var definition = IndexDef.newBuilder()
			.setResources(
				ResourcesDef.newBuilder()
					.putSynonyms(
						"cars",
						ResourcesDef.SynonymsResource.newBuilder()
							.addRules(
								// One word is equivalent to nothing
								ResourcesDef.SynonymsResource.Rule.newBuilder()
									.setEquivalent(
										ResourcesDef.SynonymsResource.Rule.Equivalent
											.newBuilder()
											.addTerms("car")
									)
							)
							.addRules(
								// A mapping with an empty side maps to nothing
								ResourcesDef.SynonymsResource.Rule.newBuilder()
									.setMapping(
										ResourcesDef.SynonymsResource.Rule.Mapping
											.newBuilder()
											.addFrom("ny")
									)
							)
							.addRules(
								// A blank word can never be a token
								ResourcesDef.SynonymsResource.Rule.newBuilder()
									.setEquivalent(
										ResourcesDef.SynonymsResource.Rule.Equivalent
											.newBuilder()
											.addTerms("car")
											.addTerms(" ")
									)
							)
							.build()
					)
			)
			.build();

		var e = assertThrows(ValidationException.class, () -> schema.setDefinition(definition));
		assertThat(e.getErrors().size(), is(3));
		assertThat(
			e.getErrors().get(0).getCode(),
			is("index:resources:synonyms:too_few_words")
		);
		assertThat(e.getErrors().get(1).getCode(), is("index:resources:synonyms:one_sided"));
		assertThat(e.getErrors().get(2).getCode(), is("index:resources:synonyms:blank_word"));
	}

	@Test
	public void testSortAndFacetAreReadFromTheDefinition() {
		IndexSchema schema = new IndexSchema();

		schema.setDefinition(
			IndexDef.newBuilder()
				.putFields(
					"title",
					FieldDef.newBuilder()
						.setFilter(FilterConfig.getDefaultInstance())
						.setSort(SortConfig.getDefaultInstance())
						.setFacet(FacetConfig.getDefaultInstance())
						.setType(
							FieldTypeDef.newBuilder()
								.setString(StringFieldTypeDef.getDefaultInstance())
						)
						.build()
				)
				.build()
		);

		var field = schema.getField("title").orElseThrow();
		assertThat(field.isFiltered(), is(true));
		assertThat(field.isSorted(), is(true));
		assertThat(field.isFaceted(), is(true));
		assertThat(field.isStoreDocValues(), is(true));
	}

	@Test
	public void testStringCanBePrimaryKey() {
		IndexSchema schema = new IndexSchema();

		schema.setDefinition(
			IndexDef.newBuilder()
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
				.build()
		);

		assertThat(schema.getPrimaryKey().isPresent(), is(true));
		assertThat(schema.getPrimaryKey().get().getName(), is("id"));
		assertThat(schema.getRequiredFields().contains("id"), is(true));
	}

	private IndexDef.Builder sortableField(String name) {
		return IndexDef.newBuilder()
			.putFields(
				name,
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder()
							.setString(StringFieldTypeDef.getDefaultInstance())
					)
					.setSort(SortConfig.getDefaultInstance())
					.build()
			);
	}

	private static RankingConfig.TieBreaker tieBreaker(String field) {
		return RankingConfig.TieBreaker.newBuilder().setField(field).build();
	}

	@Test
	public void testTieBreakersAreKept() {
		IndexSchema schema = new IndexSchema();

		schema.setDefinition(
			sortableField("popularity")
				.setRanking(RankingConfig.newBuilder().addTieBreakers(tieBreaker("popularity")))
				.build()
		);

		assertThat(schema.getTieBreakers().size(), is(1));
		assertThat(schema.getTieBreakers().get(0).getField(), is("popularity"));
	}

	@Test
	public void testTieBreakerOnUnknownFieldIsRefused() {
		IndexSchema schema = new IndexSchema();

		var e = assertThrows(
			ValidationException.class,
			() -> schema.setDefinition(
				sortableField("popularity")
					.setRanking(RankingConfig.newBuilder().addTieBreakers(tieBreaker("missing")))
					.build()
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:ranking:unknown_field"));
	}

	@Test
	public void testTieBreakerOnFieldWithoutSortIsRefused() {
		IndexSchema schema = new IndexSchema();

		var e = assertThrows(
			ValidationException.class,
			() -> schema.setDefinition(
				IndexDef.newBuilder()
					.putFields(
						"popularity",
						FieldDef.newBuilder()
							.setType(
								FieldTypeDef.newBuilder()
									.setString(StringFieldTypeDef.getDefaultInstance())
							)
							.build()
					)
					.setRanking(
						RankingConfig.newBuilder().addTieBreakers(tieBreaker("popularity"))
					)
					.build()
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:ranking:field_not_sortable"));
	}

	@Test
	public void testTieBreakerOnWildcardFieldIsRefused() {
		IndexSchema schema = new IndexSchema();

		var e = assertThrows(
			ValidationException.class,
			() -> schema.setDefinition(
				sortableField("meta.*")
					.setRanking(RankingConfig.newBuilder().addTieBreakers(tieBreaker("meta.*")))
					.build()
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:ranking:wildcard_field"));
	}

	@Test
	public void testTieBreakerOnSameFieldTwiceIsRefused() {
		IndexSchema schema = new IndexSchema();

		var e = assertThrows(
			ValidationException.class,
			() -> schema.setDefinition(
				sortableField("popularity")
					.setRanking(
						RankingConfig.newBuilder()
							.addTieBreakers(tieBreaker("popularity"))
							.addTieBreakers(tieBreaker("popularity"))
					)
					.build()
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:ranking:duplicate_field"));
	}

	/**
	 * A definition holding one sortable number, which is what a ranking signal
	 * reads.
	 */
	private IndexDef.Builder countedField(String name) {
		return IndexDef.newBuilder()
			.putFields(
				name,
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder()
							.setInt32(Int32FieldTypeDef.getDefaultInstance())
					)
					.setSort(SortConfig.getDefaultInstance())
					.build()
			);
	}

	private static RankingConfig.Signal saturation(String field, double pivot) {
		return RankingConfig.Signal.newBuilder()
			.setField(field)
			.setSaturation(RankingConfig.Signal.Saturation.newBuilder().setPivot(pivot))
			.build();
	}

	@Test
	public void testSignalsAreKept() {
		IndexSchema schema = new IndexSchema();

		schema.setDefinition(
			countedField("purchases")
				.setRanking(RankingConfig.newBuilder().addSignals(saturation("purchases", 50)))
				.build()
		);

		assertThat(schema.getSignals().size(), is(1));
		assertThat(
			schema.getSignals().get(0),
			is(new SaturationSignal("purchases", 50, 1f))
		);
	}

	@Test
	public void testSignalWeightDefaultsToOne() {
		IndexSchema schema = new IndexSchema();

		schema.setDefinition(
			countedField("purchases")
				.setRanking(RankingConfig.newBuilder().addSignals(saturation("purchases", 50)))
				.build()
		);

		assertThat(schema.getSignals().get(0).weight(), is(1f));
	}

	@Test
	public void testSignalOnUnknownFieldIsRefused() {
		IndexSchema schema = new IndexSchema();

		var e = assertThrows(
			ValidationException.class,
			() -> schema.setDefinition(
				countedField("purchases")
					.setRanking(RankingConfig.newBuilder().addSignals(saturation("missing", 50)))
					.build()
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:ranking:signal:unknown_field"));
	}

	@Test
	public void testSignalOnFieldWithoutSortIsRefused() {
		IndexSchema schema = new IndexSchema();

		var e = assertThrows(
			ValidationException.class,
			() -> schema.setDefinition(
				IndexDef.newBuilder()
					.putFields(
						"purchases",
						FieldDef.newBuilder()
							.setType(
								FieldTypeDef.newBuilder()
									.setInt32(Int32FieldTypeDef.getDefaultInstance())
							)
							.build()
					)
					.setRanking(
						RankingConfig.newBuilder().addSignals(saturation("purchases", 50))
					)
					.build()
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:ranking:signal:field_not_sortable"));
	}

	@Test
	public void testSignalOnWildcardFieldIsRefused() {
		IndexSchema schema = new IndexSchema();

		var e = assertThrows(
			ValidationException.class,
			() -> schema.setDefinition(
				countedField("counts.*")
					.setRanking(
						RankingConfig.newBuilder().addSignals(saturation("counts.*", 50))
					)
					.build()
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:ranking:signal:wildcard_field"));
	}

	@Test
	public void testSignalWithoutShapeIsRefused() {
		IndexSchema schema = new IndexSchema();

		var e = assertThrows(
			ValidationException.class,
			() -> schema.setDefinition(
				countedField("purchases")
					.setRanking(
						RankingConfig.newBuilder().addSignals(
							RankingConfig.Signal.newBuilder().setField("purchases")
						)
					)
					.build()
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:ranking:signal:shape_not_set"));
	}

	@Test
	public void testSignalWithoutPivotIsRefused() {
		IndexSchema schema = new IndexSchema();

		var e = assertThrows(
			ValidationException.class,
			() -> schema.setDefinition(
				countedField("purchases")
					.setRanking(
						RankingConfig.newBuilder().addSignals(
							RankingConfig.Signal.newBuilder()
								.setField("purchases")
								.setSaturation(
									RankingConfig.Signal.Saturation.getDefaultInstance()
								)
						)
					)
					.build()
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:ranking:signal:invalid_pivot"));
	}

	@Test
	public void testSignalWithPivotOfZeroIsRefused() {
		IndexSchema schema = new IndexSchema();

		var e = assertThrows(
			ValidationException.class,
			() -> schema.setDefinition(
				countedField("purchases")
					.setRanking(RankingConfig.newBuilder().addSignals(saturation("purchases", 0)))
					.build()
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:ranking:signal:invalid_pivot"));
	}

	@Test
	public void testSignalWithNegativeWeightIsRefused() {
		IndexSchema schema = new IndexSchema();

		var e = assertThrows(
			ValidationException.class,
			() -> schema.setDefinition(
				countedField("purchases")
					.setRanking(
						RankingConfig.newBuilder().addSignals(
							saturation("purchases", 50).toBuilder().setWeight(-1)
						)
					)
					.build()
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:ranking:signal:invalid_weight"));
	}

	/**
	 * The boost is a multiple of what a hit in the field already counts, so
	 * zero is a field asking to be ranked by nothing rather than one asking
	 * for less.
	 */
	@Test
	public void testAWholeValueMatchWorthNothingIsRefused() {
		IndexSchema schema = new IndexSchema();

		var e = assertThrows(
			ValidationException.class,
			() -> schema.setDefinition(
				IndexDef.newBuilder()
					.putFields(
						"title",
						FieldDef.newBuilder()
							.setType(
								FieldTypeDef.newBuilder()
									.setString(
										StringFieldTypeDef.newBuilder()
											.setMatching(
												StringFieldTypeDef.TextUsageConfig.newBuilder()
													.setExact(
														StringFieldTypeDef.TextUsageConfig
															.ExactConfig.newBuilder()
															.setBoost(0)
													)
											)
									)
							)
							.build()
					)
					.build()
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:field:exact:invalid_boost"));
	}

	@Test
	public void testDecayOfANumberIsRefused() {
		IndexSchema schema = new IndexSchema();

		var e = assertThrows(
			ValidationException.class,
			() -> schema.setDefinition(
				countedField("purchases")
					.setRanking(
						RankingConfig.newBuilder().addSignals(
							RankingConfig.Signal.newBuilder()
								.setField("purchases")
								.setDecay(
									RankingConfig.Signal.Decay.newBuilder()
										.setHalfLifeSeconds(3600)
								)
						)
					)
					.build()
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:ranking:signal:shape_not_supported"));
	}

	@Test
	public void testSaturationOfATimestampIsRefused() {
		IndexSchema schema = new IndexSchema();

		var e = assertThrows(
			ValidationException.class,
			() -> schema.setDefinition(
				IndexDef.newBuilder()
					.putFields(
						"published",
						FieldDef.newBuilder()
							.setType(
								FieldTypeDef.newBuilder()
									.setTimestamp(TimestampFieldTypeDef.getDefaultInstance())
							)
							.setSort(SortConfig.getDefaultInstance())
							.build()
					)
					.setRanking(
						RankingConfig.newBuilder().addSignals(saturation("published", 50))
					)
					.build()
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:ranking:signal:shape_not_supported"));
	}

	/**
	 * A definition holding one locale specific field, for the locale fallback
	 * below to have something to fill.
	 */
	private static IndexDef.Builder localizedField(String defaultLocale, String... locales) {
		var config = FieldDef.LocaleConfig.newBuilder().setDefaultLocale(defaultLocale);
		for(var locale : locales) {
			config.addLocales(locale);
		}

		return IndexDef.newBuilder()
			.putFields(
				"name",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder()
							.setString(StringFieldTypeDef.getDefaultInstance())
					)
					.setLocales(config)
					.build()
			);
	}

	@Test
	public void testLocaleFallbackChainIsKept() {
		IndexSchema schema = new IndexSchema();

		schema.setDefinition(
			localizedField("en", "sv")
				.setLocaleFallback(IndexDef.LocaleFallbackConfig.newBuilder().addChain("en"))
				.build()
		);

		assertThat(schema.hasLocaleFallback(), is(true));
		assertThat(
			schema.getLocaleFallbackChain(schema.getField("name").get()).toList(),
			is(List.of("en"))
		);
	}

	/**
	 * Naming no chain sends every field to its own default locale, which is
	 * what the chain resolves to per field rather than something the fields
	 * have to repeat.
	 */
	@Test
	public void testEmptyLocaleFallbackChainIsTheFieldDefault() {
		IndexSchema schema = new IndexSchema();

		schema.setDefinition(
			localizedField("sv", "de")
				.setLocaleFallback(IndexDef.LocaleFallbackConfig.newBuilder())
				.build()
		);

		assertThat(
			schema.getLocaleFallbackChain(schema.getField("name").get()).toList(),
			is(List.of("sv"))
		);
	}

	@Test
	public void testFieldOptedOutOfLocaleFallbackHasNoChain() {
		IndexSchema schema = new IndexSchema();

		schema.setDefinition(
			IndexDef.newBuilder()
				.putFields(
					"name",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setString(StringFieldTypeDef.getDefaultInstance())
						)
						.setLocales(
							FieldDef.LocaleConfig.newBuilder()
								.setDefaultLocale("en")
								.addLocales("sv")
								.setFallback(FieldDef.LocaleConfig.Fallback.FALLBACK_DISABLED)
						)
						.build()
				)
				.setLocaleFallback(IndexDef.LocaleFallbackConfig.newBuilder().addChain("en"))
				.build()
		);

		assertThat(
			schema.getLocaleFallbackChain(schema.getField("name").get()).isEmpty(),
			is(true)
		);
	}

	@Test
	public void testLocaleFallbackToUnsupportedLocaleIsRefused() {
		IndexSchema schema = new IndexSchema();

		var e = assertThrows(
			ValidationException.class,
			() -> schema.setDefinition(
				localizedField("en", "sv")
					.setLocaleFallback(IndexDef.LocaleFallbackConfig.newBuilder().addChain("xx"))
					.build()
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(
			e.getErrors().get(0).getCode(),
			is("index:locale_fallback:unsupported_locale")
		);
	}

	@Test
	public void testLocaleFallbackToTheSameLocaleTwiceIsRefused() {
		IndexSchema schema = new IndexSchema();

		var e = assertThrows(
			ValidationException.class,
			() -> schema.setDefinition(
				localizedField("en", "sv")
					.setLocaleFallback(
						IndexDef.LocaleFallbackConfig.newBuilder()
							.addChain("en")
							.addChain("en")
					)
					.build()
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:locale_fallback:duplicate_locale"));
	}

	/**
	 * A locale no field holds values in would never be taken from, so naming
	 * it can only be a mistake - and one that leaves the definition reading as
	 * if it covered a locale it does not.
	 */
	@Test
	public void testLocaleFallbackToALocaleNoFieldHoldsIsRefused() {
		IndexSchema schema = new IndexSchema();

		var e = assertThrows(
			ValidationException.class,
			() -> schema.setDefinition(
				localizedField("en", "sv")
					.setLocaleFallback(IndexDef.LocaleFallbackConfig.newBuilder().addChain("de"))
					.build()
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:locale_fallback:locale_not_held"));
	}

	@Test
	public void testLocaleFallbackWithoutLocaleSpecificFieldsIsRefused() {
		IndexSchema schema = new IndexSchema();

		var e = assertThrows(
			ValidationException.class,
			() -> schema.setDefinition(
				IndexDef.newBuilder()
					.putFields(
						"name",
						FieldDef.newBuilder()
							.setType(
								FieldTypeDef.newBuilder()
									.setString(StringFieldTypeDef.getDefaultInstance())
							)
							.build()
					)
					.setLocaleFallback(IndexDef.LocaleFallbackConfig.newBuilder())
					.build()
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:locale_fallback:no_locale_fields"));
	}

	/**
	 * A field asking to take part where the index declares no fallback would
	 * quietly get none, so it is refused rather than answered with nothing.
	 * Turning it off asks for nothing extra and is left alone.
	 */
	@Test
	public void testFieldTakingPartWithoutAnIndexFallbackIsRefused() {
		IndexSchema schema = new IndexSchema();

		var e = assertThrows(
			ValidationException.class,
			() -> schema.setDefinition(
				IndexDef.newBuilder()
					.putFields(
						"name",
						FieldDef.newBuilder()
							.setType(
								FieldTypeDef.newBuilder()
									.setString(StringFieldTypeDef.getDefaultInstance())
							)
							.setLocales(
								FieldDef.LocaleConfig.newBuilder()
									.setDefaultLocale("en")
									.setFallback(FieldDef.LocaleConfig.Fallback.FALLBACK_ENABLED)
							)
							.build()
					)
					.build()
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(
			e.getErrors().get(0).getCode(),
			is("index:field:locales:fallback_without_index")
		);
	}

	@Test
	public void testFieldKeepingItsGapsWithoutAnIndexFallbackIsAllowed() {
		IndexSchema schema = new IndexSchema();

		schema.setDefinition(
			IndexDef.newBuilder()
				.putFields(
					"name",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setString(StringFieldTypeDef.getDefaultInstance())
						)
						.setLocales(
							FieldDef.LocaleConfig.newBuilder()
								.setDefaultLocale("en")
								.setFallback(FieldDef.LocaleConfig.Fallback.FALLBACK_DISABLED)
						)
						.build()
				)
				.build()
		);

		assertThat(schema.hasLocaleFallback(), is(false));
	}
}
