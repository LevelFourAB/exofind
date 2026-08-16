package se.l4.exofind.engine.index.types.numbers;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.AbstractIndexTest;
import se.l4.exofind.engine.index.Document;
import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.schema.DoubleFieldTypeDef;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.FloatFieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.Int32FieldTypeDef;
import se.l4.exofind.engine.index.schema.Int64FieldTypeDef;
import se.l4.exofind.engine.index.schema.SortConfig;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.SortBy;
import se.l4.exofind.engine.query.matchers.Matchers;

/**
 * Tests for indexing and searching numbers - filtering by equality and range,
 * ordering results, and what a stored number comes back as.
 */
public class NumberIndexingTest extends AbstractIndexTest {
	@Test
	public void testEqualityFindsTheValue() throws IOException {
		var index = books();

		var result = search(index, Query.field("pages", Matchers.equalTo(320)));

		assertThat(ids(result), contains("b"));
	}

	@Test
	public void testInFindsAnyOfTheValues() throws IOException {
		var index = books();

		var result = search(index, Query.field("pages", Matchers.in(120, 320)));

		assertThat(ids(result), containsInAnyOrder("a", "b"));
	}

	@Test
	public void testRangeIsInclusiveOfItsBounds() throws IOException {
		var index = books();

		var result = search(index, Query.field("pages", Matchers.between(120, 320)));

		assertThat(ids(result), containsInAnyOrder("a", "b"));
	}

	@Test
	public void testRangeCanExcludeItsBounds() throws IOException {
		var index = books();

		var result = search(index, Query.field("pages", Matchers.greaterThan(120)));

		assertThat(ids(result), containsInAnyOrder("b", "c"));
	}

	@Test
	public void testOpenEndedRange() throws IOException {
		var index = books();

		var result = search(index, Query.field("pages", Matchers.atMost(320)));

		assertThat(ids(result), containsInAnyOrder("a", "b"));
	}

	@Test
	public void testAnyFindsTheDocumentsWithAValue() throws IOException {
		var index = books();

		index.addDocument(
			new Document(new Document.Value("id", "d"))
		);
		index.commit();

		var result = search(index, Query.field("pages", Matchers.any()));

		assertThat(ids(result), containsInAnyOrder("a", "b", "c"));
	}

	@Test
	public void testSortAscending() throws IOException {
		var index = books();

		var result = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("price"))
				.build()
		);

		assertThat(ids(result), contains("b", "a", "c"));
	}

	@Test
	public void testSortDescending() throws IOException {
		var index = books();

		var result = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("price").descending())
				.build()
		);

		assertThat(ids(result), contains("c", "a", "b"));
	}

	@Test
	public void testDocumentWithoutAValueSortsLastByDefault() throws IOException {
		var index = books();

		index.addDocument(
			new Document(new Document.Value("id", "d"))
		);
		index.commit();

		var result = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("price"))
				.build()
		);

		assertThat(ids(result), contains("b", "a", "c", "d"));
	}

	@Test
	public void testDocumentWithoutAValueSortsFirstWhenAskedTo() throws IOException {
		var index = create(
			definition()
				.putFields(
					"rating",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setDouble(DoubleFieldTypeDef.getDefaultInstance())
						)
						.setSort(
							SortConfig.newBuilder().setMissing(SortConfig.Missing.MISSING_FIRST)
						)
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "rated"),
				new Document.Value("rating", 4.5)
			)
		);
		index.addDocument(
			new Document(new Document.Value("id", "unrated"))
		);
		index.commit();

		var result = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("rating"))
				.build()
		);

		assertThat(ids(result), contains("unrated", "rated"));
	}

	@Test
	public void testFloatAndDoubleRangesFilter() throws IOException {
		var index = books();

		var floats = search(index, Query.field("weight", Matchers.lessThan(1.0f)));
		assertThat(ids(floats), containsInAnyOrder("a", "b"));

		var doubles = search(index, Query.field("price", Matchers.atLeast(20.0)));
		assertThat(ids(doubles), containsInAnyOrder("a", "c"));
	}

	@Test
	public void testInt64Filters() throws IOException {
		var index = books();

		var result = search(
			index,
			Query.field("isbn", Matchers.equalTo(9780000000002L))
		);

		assertThat(ids(result), contains("b"));
	}

	@Test
	public void testStoredNumbersComeBackTyped() throws IOException {
		var index = create(
			definition()
				.setSource(IndexDef.SourceMode.SOURCE_MODE_NONE)
				.putFields("pages", int32().setStored(true).build())
				.putFields("isbn", int64().setStored(true).build())
				.putFields("weight", floatField().setStored(true).build())
				.putFields("price", doubleField().setStored(true).build())
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("pages", 320),
				new Document.Value("isbn", 9780000000001L),
				new Document.Value("weight", 0.4f),
				new Document.Value("price", 24.5)
			)
		);
		index.commit();

		var doc = index.getDocument("1");
		assertThat(doc.get("pages"), is(320));
		assertThat(doc.get("isbn"), is(9780000000001L));
		assertThat(doc.get("weight"), is(0.4f));
		assertThat(doc.get("price"), is(24.5));
	}

	@Test
	public void testNumbersComeBackFromTheSource() throws IOException {
		var index = books();

		var doc = index.getDocument("a");
		assertThat(doc.get("pages"), is(120));
		assertThat(doc.get("isbn"), is(9780000000001L));
		assertThat(doc.get("weight"), is(0.4f));
		assertThat(doc.get("price"), is(24.5));
	}

	@Test
	public void testAnIntegerCanBeThePrimaryKey() throws IOException {
		var index = create(
			IndexDef.newBuilder()
				.putFields(
					"id",
					FieldDef.newBuilder()
						.setPrimaryKey(true)
						.setType(
							FieldTypeDef.newBuilder()
								.setInt64(Int64FieldTypeDef.getDefaultInstance())
						)
						.build()
				)
				.putFields("title", string().setStored(true).build())
		);

		index.addDocument(
			new Document(
				new Document.Value("id", 1L),
				new Document.Value("title", "First")
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", 1L),
				new Document.Value("title", "Replaced")
			)
		);
		index.commit();

		var result = index.search(SearchRequest.create().build());
		assertThat(result.total().count(), is(1L));

		var doc = index.getDocument(1L);
		assertThat(doc.get("title"), is("Replaced"));
	}

	/**
	 * A wrong number joins the other problems of the document, so everything
	 * wrong with it is reported at once.
	 */
	@Test
	public void testOutOfBoundsIsCollectedWithOtherErrors() throws IOException {
		var index = create(
			definition()
				.putFields(
					"pages",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setInt32(
									Int32FieldTypeDef.newBuilder()
										.setValidation(
											Int32FieldTypeDef.ValidationConfig.newBuilder()
												.setMin(1)
										)
								)
						)
						.build()
				)
		);

		var e = assertThrows(
			ValidationException.class,
			() -> index.addDocument(
				new Document(
					new Document.Value("id", "broken"),
					new Document.Value("pages", -1),
					new Document.Value("unknown", "value")
				)
			)
		);

		var codes = e.getErrors().collect(error -> error.getCode()).toList();
		assertThat(codes, hasItem("index:update:number:out_of_bounds"));
		assertThat(codes, hasItem("index:update:field_not_found"));
	}

	/**
	 * An index of three documents with a number of every width, so each type's
	 * filtering and ordering can be checked against the same data.
	 */
	private Index books() throws IOException {
		var index = create(
			definition()
				.putFields(
					"pages",
					int32().setFilter(FilterConfig.getDefaultInstance()).build()
				)
				.putFields(
					"isbn",
					int64().setFilter(FilterConfig.getDefaultInstance()).build()
				)
				.putFields(
					"weight",
					floatField().setFilter(FilterConfig.getDefaultInstance()).build()
				)
				.putFields(
					"price",
					doubleField()
						.setFilter(FilterConfig.getDefaultInstance())
						.setSort(SortConfig.getDefaultInstance())
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "a"),
				new Document.Value("pages", 120),
				new Document.Value("isbn", 9780000000001L),
				new Document.Value("weight", 0.4f),
				new Document.Value("price", 24.5)
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "b"),
				new Document.Value("pages", 320),
				new Document.Value("isbn", 9780000000002L),
				new Document.Value("weight", 0.8f),
				new Document.Value("price", 12.0)
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "c"),
				new Document.Value("pages", 640),
				new Document.Value("isbn", 9780000000003L),
				new Document.Value("weight", 1.6f),
				new Document.Value("price", 49.0)
			)
		);

		index.commit();
		return index;
	}

	private static IndexDef.Builder definition() {
		return IndexDef.newBuilder()
			.putFields("id", string().setPrimaryKey(true).build());
	}

	private static FieldDef.Builder string() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setString(StringFieldTypeDef.getDefaultInstance())
			);
	}

	private static FieldDef.Builder int32() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setInt32(Int32FieldTypeDef.getDefaultInstance())
			);
	}

	private static FieldDef.Builder int64() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setInt64(Int64FieldTypeDef.getDefaultInstance())
			);
	}

	private static FieldDef.Builder floatField() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setFloat(FloatFieldTypeDef.getDefaultInstance())
			);
	}

	private static FieldDef.Builder doubleField() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setDouble(DoubleFieldTypeDef.getDefaultInstance())
			);
	}

	private static SearchResult search(Index index, Query... query) throws IOException {
		return index.search(SearchRequest.create().withQuery(query).build());
	}

	private static List<Object> ids(SearchResult result) {
		return result.hits().collect(SearchResult.Hit::id).toList();
	}
}
