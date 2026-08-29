package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;

import org.eclipse.collections.api.factory.Sets;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.schema.DoubleFieldTypeDef;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.ObjectFieldTypeDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;

/**
 * Tests for an object field that names one of its own fields as what tells its
 * values apart - what the index refuses to keep, and what a value hit answers
 * with.
 */
public class ObjectKeyTest extends AbstractIndexTest {
	@Test
	public void testValuesWithDifferentKeysAreKept() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create().withHits("variants").build()
		);

		assertThat(
			result.hits().collect(SearchResult.Hit::valueKey).toList(),
			containsInAnyOrder("V-1", "V-2", "W-1")
		);
	}

	@Test
	public void testTwoValuesUnderOneKeyAreRefused() throws IOException {
		var index = products();

		var e = assertThrows(
			ValidationException.class,
			() -> index.addDocument(
				new Document(
					new Document.Value("id", "9"),
					new Document.Value("variants", variant("V-1", 10d)),
					new Document.Value("variants", variant("V-1", 20d))
				)
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:update:object:key_duplicate"));
	}

	/**
	 * One key is one document's to hold, so two documents are free to name
	 * their values the same.
	 */
	@Test
	public void testTwoDocumentsMayUseTheSameKey() throws IOException {
		var index = products();

		index.addDocument(
			new Document(
				new Document.Value("id", "9"),
				new Document.Value("variants", variant("V-1", 10d))
			)
		);
		index.commit();

		var result = index.search(
			SearchRequest.create().withHits("variants").build()
		);

		assertThat(
			result.hits().select(hit -> "V-1".equals(hit.valueKey())).size(),
			is(2)
		);
	}

	/**
	 * A value with no key at all is caught by the key field being required,
	 * which points at the field rather than at a duplicate that is not there.
	 */
	@Test
	public void testValueWithoutAKeyIsRefusedAsAMissingField() throws IOException {
		var index = products();

		var e = assertThrows(
			ValidationException.class,
			() -> index.addDocument(
				new Document(
					new Document.Value("id", "9"),
					new Document.Value(
						"variants",
						new Document(new Document.Value("price", 10d))
					)
				)
			)
		);

		assertThat(
			e.getErrors().get(0).getCode(),
			is("index:update:required_field_missing")
		);
	}

	@Test
	public void testAValueHitAnswersWithItsKey() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create().withHits("variants").build()
		);

		var first = result.hits().detect(hit -> "V-1".equals(hit.valueKey()));
		assertThat(first.id(), is("1"));
		assertThat(first.value().get("price"), is(15d));
	}

	/**
	 * The key says which value the hit is, not what the hit shows, so cutting
	 * the value down to the fields a search asked for leaves it.
	 */
	@Test
	public void testTheKeyIsAnsweredEvenWhenItIsNotAmongTheFieldsAskedFor() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withHits(new SearchRequest.Hits(
					"variants",
					Sets.immutable.of("variants.price"),
					null
				))
				.build()
		);

		var first = result.hits().detect(hit -> "V-1".equals(hit.valueKey()));
		assertThat(first.value().get("sku"), is(nullValue()));
		assertThat(first.value().get("price"), is(15d));
	}

	/**
	 * The key is read out of the kept copy of the document, so an index that
	 * keeps none answers by position alone.
	 */
	@Test
	public void testAnIndexKeepingNoCopyAnswersWithoutAKey() throws IOException {
		var index = create(
			"bare",
			definition().setSource(IndexDef.SourceMode.SOURCE_MODE_NONE)
		);
		addProducts(index);

		var result = index.search(
			SearchRequest.create().withHits("variants").build()
		);

		assertThat(
			result.hits().collect(SearchResult.Hit::valueKey).toList(),
			contains(nullValue(), nullValue(), nullValue())
		);
	}

	/**
	 * A field with no key is unchanged - the position is still all a hit has
	 * to say which value it stands for.
	 */
	@Test
	public void testAFieldWithoutAKeyAnswersWithoutOne() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create().withHits("badges").build()
		);

		assertThat(result.hits().collect(SearchResult.Hit::valueKey).toList(),
			contains(nullValue()));
		assertThat(result.hits().collect(SearchResult.Hit::index).toList(), contains(0));
	}

	private Index products() throws IOException {
		var index = create("products", definition());
		addProducts(index);
		return index;
	}

	private static IndexDef.Builder definition() {
		return IndexDef.newBuilder()
			.putFields("id", string().setPrimaryKey(true).build())
			.putFields(
				"variants",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder().setObject(
							ObjectFieldTypeDef.newBuilder()
								.putFields("sku", string().setRequired(true).build())
								.putFields(
									"price",
									FieldDef.newBuilder()
										.setType(
											FieldTypeDef.newBuilder().setDouble(
												DoubleFieldTypeDef.getDefaultInstance()
											)
										)
										.setFilter(FilterConfig.getDefaultInstance())
										.build()
								)
								.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
								.setKey("sku")
						)
					)
					.setMultiple(true)
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
									string().setFilter(FilterConfig.getDefaultInstance()).build()
								)
								.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
						)
					)
					.setMultiple(true)
					.build()
			);
	}

	private static void addProducts(Index index) throws IOException {
		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("variants", variant("V-1", 15d)),
				new Document.Value("variants", variant("V-2", 25d)),
				new Document.Value(
					"badges",
					new Document(new Document.Value("label", "eco"))
				)
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("variants", variant("W-1", 30d))
			)
		);

		index.commit();
	}

	private static Document variant(String sku, double price) {
		return new Document(
			new Document.Value("sku", sku),
			new Document.Value("price", price)
		);
	}

	private static FieldDef.Builder string() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setString(StringFieldTypeDef.getDefaultInstance())
			);
	}
}
