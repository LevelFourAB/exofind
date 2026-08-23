package se.l4.exofind.engine.index.types;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.lucene.index.IndexableField;
import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.IndexEncounterImpl;
import se.l4.exofind.engine.index.locales.Locales;
import se.l4.exofind.engine.index.schema.FacetConfig;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.ResourcesDef;
import se.l4.exofind.engine.index.schema.SortConfig;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;

/**
 * Tests for which Lucene fields a string turns into, and what they hold.
 */
public class StringFieldTypeTest {
	private final StringFieldType type = new StringFieldType();

	private Map<String, IndexableField> index(FieldDef.Builder def, String value) {
		return index(def, value, false);
	}

	private Map<String, IndexableField> index(
		FieldDef.Builder def,
		String value,
		boolean highlightsInPostings
	) {
		var encounter = new IndexEncounterImpl(
			ResourcesDef.getDefaultInstance(),
			highlightsInPostings
		);
		encounter.updateLocale(Locales.getDefault());
		encounter.updateValue("title", def.build());

		var fields = new LinkedHashMap<String, IndexableField>();
		for(var field : type.createFields(encounter, value)) {
			fields.put(field.name(), field);
		}

		return fields;
	}

	private FieldDef.Builder string() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder()
					.setString(StringFieldTypeDef.getDefaultInstance())
			);
	}

	@Test
	public void testNothingIsIndexedWithoutAUsage() {
		assertThat(index(string(), "Fiction").isEmpty(), is(true));
	}

	@Test
	public void testFilterIgnoresCaseByDefault() {
		var fields = index(string().setFilter(FilterConfig.getDefaultInstance()), "Fiction");

		assertThat(fields, hasKey("title:_:filter"));
		assertThat(fields.get("title:_:filter").stringValue(), is("fiction"));
	}

	@Test
	public void testFilterKeepsCaseWhenTheKeywordConfigSaysSo() {
		var fields = index(
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
				.setFilter(FilterConfig.getDefaultInstance()),
			"Fiction"
		);

		assertThat(fields.get("title:_:filter").stringValue(), is("Fiction"));
	}

	/**
	 * The primary key gets a field of its own, because a filter that ignores
	 * case would make two documents look like the same one.
	 */
	@Test
	public void testPrimaryKeyKeepsCaseEvenWhenFilterDoesNot() {
		var fields = index(
			string()
				.setPrimaryKey(true)
				.setFilter(FilterConfig.getDefaultInstance()),
			"ABC"
		);

		assertThat(fields.get("title:_:pk").stringValue(), is("ABC"));
		assertThat(fields.get("title:_:filter").stringValue(), is("abc"));
	}

	@Test
	public void testFacetCountsTheValueAsItWasGiven() {
		var fields = index(
			string()
				.setFilter(FilterConfig.getDefaultInstance())
				.setFacet(FacetConfig.getDefaultInstance()),
			"Fiction"
		);

		assertThat(fields, hasKey("title:_:values"));
		assertThat(fields.get("title:_:values").binaryValue(), is(new BytesRef("Fiction")));
	}

	@Test
	public void testFacetIsNotWrittenWithoutTheConfig() {
		var fields = index(string().setFilter(FilterConfig.getDefaultInstance()), "Fiction");

		assertThat(fields, not(hasKey("title:_:values")));
	}

	/**
	 * A field that ranks a whole-value match above a mention needs the value
	 * as one term to compare against, which nothing else it writes holds - the
	 * matching field has it in pieces, and the filter field is only written
	 * for a field that can be filtered and normalizes by other rules.
	 */
	@Test
	public void testMatchingWritesTheWholeValueWhenTheUsageAsksForIt() {
		var fields = index(matching(true), "The Silent Spring");

		assertThat(fields, hasKey("title:_:matching_exact"));
		assertThat(
			fields.get("title:_:matching_exact").binaryValue(),
			is(new BytesRef("the silent spring"))
		);
	}

	@Test
	public void testMatchingWritesNoWholeValueWithoutTheConfig() {
		assertThat(index(matching(false), "The Silent Spring"), not(hasKey("title:_:matching_exact")));
	}

	/**
	 * Under its own name, because the two usages normalize through chains of
	 * their own and a term written by one is not the term the other looks up.
	 */
	@Test
	public void testAutocompleteWritesItsOwnWholeValue() {
		var fields = index(
			FieldDef.newBuilder()
				.setType(
					FieldTypeDef.newBuilder()
						.setString(
							StringFieldTypeDef.newBuilder()
								.setAutocomplete(
									StringFieldTypeDef.TextUsageConfig.newBuilder()
										.setExact(
											StringFieldTypeDef.TextUsageConfig.ExactConfig
												.getDefaultInstance()
										)
								)
						)
				),
			"Stockholm"
		);

		assertThat(fields, hasKey("title:_:autocomplete_exact"));
		assertThat(
			fields.get("title:_:autocomplete_exact").binaryValue(),
			is(new BytesRef("stockholm"))
		);
	}

	private FieldDef.Builder matching(boolean exact) {
		var usage = StringFieldTypeDef.TextUsageConfig.newBuilder();
		if(exact) {
			usage.setExact(
				StringFieldTypeDef.TextUsageConfig.ExactConfig.getDefaultInstance()
			);
		}

		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder()
					.setString(StringFieldTypeDef.newBuilder().setMatching(usage))
			);
	}

	private FieldDef.Builder highlightedMatching() {
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
	 * An index laying highlighting out in term vectors writes them per
	 * document, with the positions and payloads that were always written
	 * beside them, and keeps offsets out of the postings.
	 */
	@Test
	public void testHighlightedMatchingWritesTermVectorsUnderTheVectorLayout() {
		var field = index(highlightedMatching(), "The Silent Spring", false)
			.get("title:_:matching");

		assertThat(field.fieldType().storeTermVectors(), is(true));
		assertThat(field.fieldType().storeTermVectorOffsets(), is(true));
		assertThat(
			field.fieldType().indexOptions(),
			is(org.apache.lucene.index.IndexOptions.DOCS_AND_FREQS_AND_POSITIONS)
		);
	}

	/**
	 * An index laying highlighting out in postings writes the offsets into
	 * the postings of the field itself and no term vectors at all.
	 */
	@Test
	public void testHighlightedMatchingWritesOffsetsUnderThePostingsLayout() {
		var field = index(highlightedMatching(), "The Silent Spring", true)
			.get("title:_:matching");

		assertThat(field.fieldType().storeTermVectors(), is(false));
		assertThat(
			field.fieldType().indexOptions(),
			is(org.apache.lucene.index.IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS)
		);
	}

	/**
	 * The layout only reaches fields that highlight - one that does not stays
	 * bare whichever layout the index uses.
	 */
	@Test
	public void testPlainMatchingIgnoresTheLayout() {
		var field = index(matching(false), "The Silent Spring", true)
			.get("title:_:matching");

		assertThat(field.fieldType().storeTermVectors(), is(false));
		assertThat(
			field.fieldType().indexOptions(),
			is(org.apache.lucene.index.IndexOptions.DOCS_AND_FREQS_AND_POSITIONS)
		);
	}

	private BytesRef sortValue(SortConfig.Builder sort, String value) {
		return index(string().setSort(sort), value).get("title:_:sort").binaryValue();
	}

	/**
	 * Ordering by the bytes of a value puts anything outside ASCII after `z`,
	 * so the default turns the value into a collation key first.
	 */
	@Test
	public void testSortUsesLocaleCollationByDefault() {
		var sort = SortConfig.newBuilder();

		assertThat(sortValue(sort, "Äpple"), is(lessThan(sortValue(sort, "Zebra"))));
	}

	@Test
	public void testSortCanUseTheBytesOfTheValue() {
		var sort = SortConfig.newBuilder().setCollation(SortConfig.Collation.COLLATION_BINARY);

		assertThat(sortValue(sort, "Zebra"), is(new BytesRef("Zebra")));

		// Which is the order the collation above exists to correct
		assertThat(sortValue(sort, "Zebra"), is(lessThan(sortValue(sort, "Äpple"))));
	}
}
