package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;

import org.eclipse.collections.impl.factory.Sets;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.DoubleFieldTypeDef;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.ObjectFieldTypeDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.matchers.Matchers;

/**
 * Tests for stored fields below nested lists on an index that keeps no copy
 * of its documents - the values answer from their own documents, as far as
 * their stored fields reach.
 */
public class NestedStoredSearchTest extends AbstractIndexTest {
	@Test
	public void testDocumentComesBackWithItsNestedValues() throws IOException {
		var index = catalog();

		var document = index.getDocument("1");
		var product = (Document) document.get("product");

		var variants = product.getAll("variants");
		assertThat(variants.size(), is(3));

		var first = (Document) variants.get(0);
		assertThat(first.get("sku"), is("A"));
		assertThat(first.get("color"), is("red"));
		assertThat(((Document) first.get("size")).get("eu"), is(42d));
		// Not stored, so only the copy could have answered it
		assertThat(first.get("stock"), is(nullValue()));

		assertThat(((Document) variants.get(1)).get("sku"), is("B"));
		assertThat(((Document) variants.get(2)).get("sku"), is("C"));
	}

	@Test
	public void testRootNestedListComesBackWithItsValues() throws IOException {
		var index = catalog();

		var document = index.getDocument("1");
		var badges = document.getAll("badges");

		assertThat(
			badges.stream().map(badge -> ((Document) badge).get("label")).toList(),
			contains("eco", "new")
		);
	}

	@Test
	public void testSearchWithoutFieldsBringsNestedValuesBack() throws IOException {
		var index = catalog();

		var result = index.search(SearchRequest.create().build());

		var hit = result.hits().detect(h -> "1".equals(h.id()));
		var product = (Document) hit.document().get("product");
		var variants = product.getAll("variants");
		assertThat(variants.size(), is(3));
		assertThat(((Document) variants.get(0)).get("color"), is("red"));
	}

	@Test
	public void testAskingForAFieldBelowANestedListIsAnswered() throws IOException {
		var index = catalog();

		var result = index.search(
			SearchRequest.create()
				.withFields("product.variants.sku")
				.build()
		);

		var hit = result.hits().detect(h -> "1".equals(h.id()));
		var product = (Document) hit.document().get("product");
		var variants = product.getAll("variants");

		assertThat(
			variants.stream().map(value -> ((Document) value).get("sku")).toList(),
			contains("A", "B", "C")
		);

		// Only the field asked for comes back
		assertThat(((Document) variants.get(0)).get("color"), is(nullValue()));
	}

	@Test
	public void testAskingForADeepFieldBelowANestedListIsAnswered() throws IOException {
		var index = catalog();

		var result = index.search(
			SearchRequest.create()
				.withFields("product.variants.size.eu")
				.build()
		);

		var hit = result.hits().detect(h -> "1".equals(h.id()));
		var product = (Document) hit.document().get("product");
		var variants = product.getAll("variants");
		assertThat(variants.size(), is(3));

		var size = (Document) ((Document) variants.get(0)).get("size");
		assertThat(size.get("eu"), is(42d));
	}

	@Test
	public void testAskingForANonStoredFieldBelowANestedListIsRefused() throws IOException {
		var index = catalog();

		var e = assertThrows(
			IndexFieldUsageException.class,
			() -> index.search(
				SearchRequest.create().withFields("product.variants.stock").build()
			)
		);

		assertThat(e.getCode(), is("index:query:usage_not_enabled"));
	}

	@Test
	public void testAskingForAnObjectInsideANestedValueIsRefused() throws IOException {
		var index = catalog();

		var e = assertThrows(
			IndexSourceRequiredException.class,
			() -> index.search(
				SearchRequest.create().withFields("product.variants.size").build()
			)
		);

		assertThat(e.getCode(), is("index:query:source_not_kept"));
	}

	@Test
	public void testValueHitsAnswerFromTheValuesOwnDocuments() throws IOException {
		var index = catalog();

		var result = index.search(
			SearchRequest.create()
				.withQuery(
					Query.nested(
						"product.variants",
						Query.field("product.variants.color", Matchers.equalTo("red"))
					)
				)
				.withHits("product.variants")
				.build()
		);

		// The values that matched, both of them from document 1
		assertThat(result.hits().size(), is(2));

		var first = result.hits().detect(
			h -> "1".equals(h.id()) && h.index() == 0
		);
		assertThat(first.valueKey(), is("A"));
		assertThat(first.value().get("color"), is("red"));
		assertThat(((Document) first.value().get("size")).get("eu"), is(42d));
		assertThat(first.value().get("stock"), is(nullValue()));

		var third = result.hits().detect(
			h -> "1".equals(h.id()) && h.index() == 2
		);
		assertThat(third.valueKey(), is("C"));
	}

	@Test
	public void testValueHitsCutToTheFieldsAskedForKeepTheKey() throws IOException {
		var index = catalog();

		var result = index.search(
			SearchRequest.create()
				.withQuery(
					Query.nested(
						"product.variants",
						Query.field("product.variants.color", Matchers.equalTo("green"))
					)
				)
				.withHits(new SearchRequest.Hits(
					"product.variants",
					Sets.immutable.of("product.variants.color"),
					null
				))
				.build()
		);

		var hit = result.hits().getFirst();
		assertThat(hit.valueKey(), is("D"));
		assertThat(hit.value().get("color"), is("green"));
		assertThat(hit.value().get("sku"), is(nullValue()));
	}

	@Test
	public void testValueHitsNamingANonStoredFieldAreRefused() throws IOException {
		var index = catalog();

		var e = assertThrows(
			IndexFieldUsageException.class,
			() -> index.search(
				SearchRequest.create()
					.withHits(new SearchRequest.Hits(
						"product.variants",
						Sets.immutable.of("product.variants.stock"),
						null
					))
					.build()
			)
		);

		assertThat(e.getCode(), is("index:query:usage_not_enabled"));
	}

	@Test
	public void testMatchedValuesAnswerFromTheValuesOwnDocuments() throws IOException {
		var index = catalog();

		var result = index.search(
			SearchRequest.create()
				.withQuery(
					Query.nested(
						"product.variants",
						Query.field("product.variants.color", Matchers.equalTo("red"))
					)
				)
				.addMatched("product.variants")
				.build()
		);

		var hit = result.hits().detect(h -> "1".equals(h.id()));
		var matched = hit.matched().get("product.variants");

		assertThat(matched.totalValues(), is(2));
		assertThat(
			matched.values().collect(value -> value.get("sku")).toList(),
			contains("A", "C")
		);
	}

	@Test
	public void testMatchedValuesCutToTheFieldsAskedFor() throws IOException {
		var index = catalog();

		var result = index.search(
			SearchRequest.create()
				.withQuery(
					Query.nested(
						"product.variants",
						Query.field("product.variants.color", Matchers.equalTo("red"))
					)
				)
				.addMatched(
					"product.variants",
					new SearchRequest.Matched(
						10,
						Sets.immutable.of("product.variants.color")
					)
				)
				.build()
		);

		var hit = result.hits().detect(h -> "1".equals(h.id()));
		var matched = hit.matched().get("product.variants");

		assertThat(matched.values().getFirst().get("color"), is("red"));
		assertThat(matched.values().getFirst().get("sku"), is(nullValue()));
	}

	/**
	 * Two products under a single {@code product} object whose {@code
	 * variants} is a nested list with stored fields, and a {@code badges}
	 * nested list at the root - on an index that keeps no copy of its
	 * documents.
	 */
	private Index catalog() throws IOException {
		var index = create(
			"catalog",
			IndexDef.newBuilder()
				.setSource(IndexDef.SourceMode.SOURCE_MODE_NONE)
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"product",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder().setObject(
								ObjectFieldTypeDef.newBuilder()
									.putFields(
										"variants",
										FieldDef.newBuilder()
											.setType(
												FieldTypeDef.newBuilder().setObject(
													ObjectFieldTypeDef.newBuilder()
														.putFields(
															"sku",
															string()
																.setRequired(true)
																.setStored(true)
																.build()
														)
														.putFields(
															"color",
															string()
																.setFilter(FilterConfig.getDefaultInstance())
																.setStored(true)
																.build()
														)
														.putFields(
															"stock",
															string()
																.setFilter(FilterConfig.getDefaultInstance())
																.build()
														)
														.putFields(
															"size",
															FieldDef.newBuilder()
																.setType(
																	FieldTypeDef.newBuilder().setObject(
																		ObjectFieldTypeDef.newBuilder()
																			.putFields(
																				"eu",
																				doubleField()
																					.setStored(true)
																					.build()
																			)
																	)
																)
																.build()
														)
														.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
														.setKey("sku")
												)
											)
											.setMultiple(true)
											.build()
									)
							)
						)
						.build()
				)
				.putFields(
					"badges",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder().setObject(
								ObjectFieldTypeDef.newBuilder()
									.putFields(
										"label",
										string().setStored(true).build()
									)
									.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
							)
						)
						.setMultiple(true)
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value(
					"product",
					new Document(
						variant("A", "red", "low", 42d),
						variant("B", "blue", "high", 44d),
						variant("C", "red", "low", 40d)
					)
				),
				new Document.Value(
					"badges",
					new Document(new Document.Value("label", "eco"))
				),
				new Document.Value(
					"badges",
					new Document(new Document.Value("label", "new"))
				)
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value(
					"product",
					new Document(variant("D", "green", "high", 38d))
				)
			)
		);

		index.commit();
		return index;
	}

	private static Document.Value variant(String sku, String color, String stock, double eu) {
		return new Document.Value(
			"variants",
			new Document(
				new Document.Value("sku", sku),
				new Document.Value("color", color),
				new Document.Value("stock", stock),
				new Document.Value(
					"size",
					new Document(new Document.Value("eu", eu))
				)
			)
		);
	}

	private static FieldDef.Builder string() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setString(StringFieldTypeDef.getDefaultInstance())
			);
	}

	private static FieldDef.Builder doubleField() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setDouble(DoubleFieldTypeDef.getDefaultInstance())
			);
	}
}
