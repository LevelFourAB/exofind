package se.l4.exofind.engine.index.types.numbers;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

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
import se.l4.exofind.engine.index.schema.ObjectFieldTypeDef;
import se.l4.exofind.engine.index.schema.SortConfig;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.schema.TimestampFieldTypeDef;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.SortBy;

/**
 * Tests for ordering by a number over more documents than a search reads.
 *
 * A field with both {@code filter} and {@code sort} answers an order without
 * reading every match, so these searches take a different path through Lucene
 * than the same order over a field that only has {@code sort}. That path
 * starts leaving documents out once a search has counted past the total hits
 * threshold, so every index here holds more documents than that threshold.
 */
public class NumberSortSkippingTest extends AbstractIndexTest {
	private static final int COUNT = 3000;

	@Test
	public void testAscendingBringsTheLowestValues() throws IOException {
		var index = catalogue();

		var result = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("rank"))
				.withLimit(10)
				.build()
		);

		assertThat(ids(result), is(up(0, 10)));
	}

	@Test
	public void testDescendingBringsTheHighestValues() throws IOException {
		var index = catalogue();

		var result = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("rank").descending())
				.withLimit(10)
				.build()
		);

		assertThat(ids(result), is(down(COUNT - 1, 10)));
	}

	/**
	 * A field without {@code filter} has no points to skip through, and orders
	 * the same documents the same way.
	 */
	@Test
	public void testFieldWithoutFilterOrdersTheSame() throws IOException {
		var index = catalogue();

		var ascending = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("plain"))
				.withLimit(10)
				.build()
		);
		assertThat(ids(ascending), is(up(0, 10)));

		var descending = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("plain").descending())
				.withLimit(10)
				.build()
		);
		assertThat(ids(descending), is(down(COUNT - 1, 10)));
	}

	/**
	 * Every width of number, and the timestamp that compares as one, reads its
	 * values through a comparator of its own.
	 */
	@Test
	public void testEveryNumericTypeOrders() throws IOException {
		var index = catalogue();

		var prices = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("price").descending())
				.withLimit(5)
				.build()
		);
		assertThat(ids(prices), is(down(COUNT - 1, 5)));

		var weights = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("weight"))
				.withLimit(5)
				.build()
		);
		assertThat(ids(weights), is(up(0, 5)));

		var counts = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("copies").descending())
				.withLimit(5)
				.build()
		);
		assertThat(ids(counts), is(down(COUNT - 1, 5)));

		var published = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("published"))
				.withLimit(5)
				.build()
		);
		assertThat(ids(published), is(up(0, 5)));
	}

	@Test
	public void testPagesAfterAHitContinueTheOrder() throws IOException {
		var index = catalogue();

		var first = page(index, null);
		assertThat(ids(first), is(up(0, 10)));

		var second = page(index, first);
		assertThat(ids(second), is(up(10, 10)));

		var third = page(index, second);
		assertThat(ids(third), is(up(20, 10)));
	}

	@Test
	public void testPagesBeforeAHitReadInTheRequestedOrder() throws IOException {
		var index = catalogue();

		var deep = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("rank"))
				.withOffset(1000)
				.withLimit(10)
				.build()
		);
		assertThat(ids(deep), is(up(1000, 10)));

		var back = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("rank"))
				.withBefore(deep.hits().getFirst().key())
				.withLimit(10)
				.build()
		);

		assertThat(ids(back), is(up(990, 10)));
	}

	@Test
	public void testDeepPageKeepsTheOrder() throws IOException {
		var index = catalogue();

		var result = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("rank").descending())
				.withOffset(2000)
				.withLimit(5)
				.build()
		);

		assertThat(ids(result), is(down(COUNT - 1 - 2000, 5)));
	}

	@Test
	public void testDocumentWithoutAValueSortsLastByDefault() throws IOException {
		var index = withGap("last", SortConfig.getDefaultInstance());

		var result = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("rank"))
				.withOffset(COUNT - 2)
				.withLimit(3)
				.build()
		);

		var expected = up(COUNT - 2, 2);
		expected.add("gap");
		assertThat(ids(result), is(expected));
	}

	@Test
	public void testDocumentWithoutAValueSortsFirstWhenAskedTo() throws IOException {
		var index = withGap(
			"first",
			SortConfig.newBuilder().setMissing(SortConfig.Missing.MISSING_FIRST).build()
		);

		var result = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("rank"))
				.withLimit(3)
				.build()
		);

		var expected = new ArrayList<Object>();
		expected.add("gap");
		expected.addAll(up(0, 2));
		assertThat(ids(result), is((List<Object>) expected));
	}

	/**
	 * Every value of an object field is a Lucene document of its own, and a
	 * document of the index is the only kind of hit a search of the index
	 * answers with. Ordering has to read the two apart: counting the values as
	 * documents that hold no number puts a missing value at whichever end the
	 * order asks for, and descending is the end that decides the page.
	 */
	@Test
	public void testOrderIsKeptWhereDocumentsHoldValuesOfObjectFields() throws IOException {
		var index = withVariants();

		var descending = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("rank").descending())
				.withLimit(10)
				.build()
		);
		assertThat(ids(descending), is(down(COUNT - 1, 10)));

		var ascending = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("rank"))
				.withLimit(10)
				.build()
		);
		assertThat(ids(ascending), is(up(0, 10)));

		var deep = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("rank").descending())
				.withOffset(1500)
				.withLimit(10)
				.build()
		);
		assertThat(ids(deep), is(down(COUNT - 1 - 1500, 10)));
	}

	/**
	 * The field that has no points orders the same documents the same way, so
	 * it says what the answer is without any skipping involved.
	 */
	@Test
	public void testObjectFieldsDoNotChangeWhatTheOrderAnswers() throws IOException {
		var index = withVariants();

		for(var offset : new int[] { 0, 1500 }) {
			var skipping = index.search(
				SearchRequest.create()
					.withSort(SortBy.field("rank").descending())
					.withOffset(offset)
					.withLimit(10)
					.build()
			);

			var plain = index.search(
				SearchRequest.create()
					.withSort(SortBy.field("plain").descending())
					.withOffset(offset)
					.withLimit(10)
					.build()
			);

			assertThat(ids(skipping), is(ids(plain)));
		}
	}

	/**
	 * An exact count reads every match, so the same order comes back from a
	 * search that never stops counting.
	 */
	@Test
	public void testCountingEveryMatchKeepsTheOrder() throws IOException {
		var index = catalogue();

		var result = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("rank").descending())
				.withTotal(SearchRequest.Total.EXACT)
				.withLimit(10)
				.build()
		);

		assertThat(ids(result), is(down(COUNT - 1, 10)));
		assertThat(result.total().count(), is((long) COUNT));
	}

	private static SearchResult page(Index index, SearchResult previous) throws IOException {
		var request = SearchRequest.create()
			.withSort(SortBy.field("rank"))
			.withLimit(10);

		if(previous != null) {
			request = request.withAfter(previous.hits().getLast().key());
		}

		return index.search(request.build());
	}

	/**
	 * An index holding every number width in a field that is both filtered and
	 * sorted, and one integer field that is only sorted.
	 */
	private Index catalogue() throws IOException {
		var index = create(
			"catalogue",
			definition()
				.putFields("rank", filterAndSort(int32()))
				.putFields("plain", int32().setSort(SortConfig.getDefaultInstance()).build())
				.putFields("copies", filterAndSort(int64()))
				.putFields("price", filterAndSort(doubleField()))
				.putFields("weight", filterAndSort(floatField()))
				.putFields("published", filterAndSort(timestamp()))
		);

		for(var i = 0; i < COUNT; i++) {
			index.addDocument(
				new Document(
					new Document.Value("id", id(i)),
					new Document.Value("rank", i),
					new Document.Value("plain", i),
					new Document.Value("copies", (long) i),
					new Document.Value("price", i * 1.5),
					new Document.Value("weight", i * 0.5f),
					new Document.Value("published", Instant.ofEpochMilli(i * 1000L).toString())
				)
			);
		}

		index.commit();
		return index;
	}

	/**
	 * An index of the same size whose documents each hold a few values of an
	 * object field, so that most Lucene documents of a segment are not
	 * documents of the index.
	 */
	private Index withVariants() throws IOException {
		var index = create(
			"variants",
			definition()
				.putFields("rank", filterAndSort(int32()))
				.putFields("plain", int32().setSort(SortConfig.getDefaultInstance()).build())
				.putFields(
					"variants",
					FieldDef.newBuilder()
						.setMultiple(true)
						.setType(
							FieldTypeDef.newBuilder().setObject(
								ObjectFieldTypeDef.newBuilder()
									.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
									.putFields("price", filterAndSort(doubleField()))
							)
						)
						.build()
				)
		);

		for(var i = 0; i < COUNT; i++) {
			index.addDocument(
				new Document(
					new Document.Value("id", id(i)),
					new Document.Value("rank", i),
					new Document.Value("plain", i),
					new Document.Value(
						"variants",
						new Document(new Document.Value("price", i * 1.5))
					),
					new Document.Value(
						"variants",
						new Document(new Document.Value("price", i * 2.5))
					)
				)
			);
		}

		index.commit();
		return index;
	}

	/**
	 * An index of the same size where one document holds no number at all, so
	 * the end a missing value sorts at can be read.
	 */
	private Index withGap(String name, SortConfig sort) throws IOException {
		var index = create(
			name,
			definition()
				.putFields(
					"rank",
					int32()
						.setFilter(FilterConfig.getDefaultInstance())
						.setSort(sort)
						.build()
				)
		);

		for(var i = 0; i < COUNT; i++) {
			index.addDocument(
				new Document(
					new Document.Value("id", id(i)),
					new Document.Value("rank", i)
				)
			);
		}

		index.addDocument(new Document(new Document.Value("id", "gap")));

		index.commit();
		return index;
	}

	private static IndexDef.Builder definition() {
		return IndexDef.newBuilder()
			.putFields("id", string().setPrimaryKey(true).build());
	}

	private static FieldDef filterAndSort(FieldDef.Builder field) {
		return field
			.setFilter(FilterConfig.getDefaultInstance())
			.setSort(SortConfig.getDefaultInstance())
			.build();
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

	private static FieldDef.Builder timestamp() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder()
					.setTimestamp(TimestampFieldTypeDef.getDefaultInstance())
			);
	}

	/**
	 * The identifier of the document holding the given number, padded so that
	 * identifiers sort the same way the numbers do.
	 */
	private static String id(int value) {
		return String.format("%04d", value);
	}

	private static List<Object> up(int from, int count) {
		var ids = new ArrayList<Object>();
		for(var i = 0; i < count; i++) {
			ids.add(id(from + i));
		}
		return ids;
	}

	private static List<Object> down(int from, int count) {
		var ids = new ArrayList<Object>();
		for(var i = 0; i < count; i++) {
			ids.add(id(from - i));
		}
		return ids;
	}

	private static List<Object> ids(SearchResult result) {
		return result.hits().collect(SearchResult.Hit::id).toList();
	}
}
