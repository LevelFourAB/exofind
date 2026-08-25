package se.l4.exofind.engine.index.types.timestamps;

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
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.SortConfig;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.schema.TimestampFieldTypeDef;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.SortBy;
import se.l4.exofind.engine.query.matchers.Matchers;
import se.l4.exofind.engine.query.matchers.RangeMatcher;

/**
 * Tests for indexing and searching timestamps - values compared as the
 * instants they name, the offsets that requires, and what comes back.
 */
public class TimestampIndexingTest extends AbstractIndexTest {
	@Test
	public void testRangeFindsTheInstantsBetween() throws IOException {
		var index = books();

		var result = search(
			index,
			Query.field(
				"published",
				Matchers.between("2020-01-01T00:00:00Z", "2022-12-31T23:59:59Z")
			)
		);

		assertThat(ids(result), containsInAnyOrder("b", "c"));
	}

	@Test
	public void testOpenEndedRange() throws IOException {
		var index = books();

		var result = search(
			index,
			Query.field("published", Matchers.atLeast("2022-01-01T00:00:00Z"))
		);

		assertThat(ids(result), containsInAnyOrder("c"));
	}

	@Test
	public void testRangesFindsInstantsInAnyOfThem() throws IOException {
		var index = books();

		var result = search(
			index,
			Query.field("published", Matchers.ranges(
				RangeMatcher.atMost("2020-01-01T00:00:00Z"),
				RangeMatcher.atLeast("2022-01-01T00:00:00Z")
			))
		);

		assertThat(ids(result), containsInAnyOrder("a", "c"));
	}

	/**
	 * The offset only says where the clock was read, so a query written in one
	 * offset finds a value written in another when they name the same instant.
	 */
	@Test
	public void testValuesCompareAsInstantsAcrossOffsets() throws IOException {
		var index = books();

		var result = search(
			index,
			Query.field("published", Matchers.equalTo("2021-06-01T14:00:00+02:00"))
		);

		assertThat(ids(result), contains("b"));
	}

	@Test
	public void testSortOrdersByInstant() throws IOException {
		var index = books();

		var result = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("published"))
				.build()
		);

		assertThat(ids(result), contains("a", "b", "c"));
	}

	@Test
	public void testDocumentWithoutAValueSortsLastByDefault() throws IOException {
		var index = books();

		index.addDocument(new Document(new Document.Value("id", "d")));
		index.commit();

		var result = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("published"))
				.build()
		);

		assertThat(ids(result), contains("a", "b", "c", "d"));
	}

	@Test
	public void testAnyFindsTheDocumentsWithAValue() throws IOException {
		var index = books();

		index.addDocument(new Document(new Document.Value("id", "d")));
		index.commit();

		var result = search(index, Query.field("published", Matchers.any()));

		assertThat(ids(result), containsInAnyOrder("a", "b", "c"));
	}

	/**
	 * The value as it was given is what comes back, offset and all - reading
	 * it as an instant is for comparing, not for rewriting.
	 */
	@Test
	public void testValueComesBackAsItWasGiven() throws IOException {
		var index = books();

		assertThat(
			index.getDocument("b").get("published"),
			is("2021-06-01T12:00:00Z")
		);
	}

	@Test
	public void testStoredValueComesBackAsItWasGivenWithoutASource() throws IOException {
		var index = create(
			definition()
				.setSource(IndexDef.SourceMode.SOURCE_MODE_NONE)
				.putFields("published", timestamp().setStored(true).build())
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("published", "2021-06-01T14:00:00+02:00")
			)
		);
		index.commit();

		assertThat(
			index.getDocument("1").get("published"),
			is("2021-06-01T14:00:00+02:00")
		);
	}

	/**
	 * A value without an offset names no instant, so it is refused rather than
	 * read in whatever zone this node happens to run in.
	 */
	@Test
	public void testValueWithoutAnOffsetIsRefused() throws IOException {
		var index = books();

		var e = assertThrows(
			ValidationException.class,
			() -> index.addDocument(
				new Document(
					new Document.Value("id", "broken"),
					new Document.Value("published", "2021-06-01T12:00:00")
				)
			)
		);

		assertThat(
			e.getErrors().collect(error -> error.getCode()).toList(),
			hasItem("index:update:timestamp:invalid_value")
		);
	}

	@Test
	public void testValueThatIsNotATimestampIsRefused() throws IOException {
		var index = books();

		var e = assertThrows(
			ValidationException.class,
			() -> index.addDocument(
				new Document(
					new Document.Value("id", "broken"),
					new Document.Value("published", "yesterday")
				)
			)
		);

		assertThat(
			e.getErrors().collect(error -> error.getCode()).toList(),
			hasItem("index:update:timestamp:invalid_value")
		);
	}

	/**
	 * An index of three documents published a year apart, the middle one
	 * written with a non-UTC offset so comparisons across offsets are covered.
	 */
	private Index books() throws IOException {
		var index = create(
			definition()
				.putFields(
					"published",
					timestamp()
						.setFilter(FilterConfig.getDefaultInstance())
						.setSort(SortConfig.getDefaultInstance())
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "a"),
				new Document.Value("published", "2019-03-01T09:30:00Z")
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "b"),
				new Document.Value("published", "2021-06-01T12:00:00Z")
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "c"),
				new Document.Value("published", "2022-11-15T18:45:00-05:00")
			)
		);

		index.commit();
		return index;
	}

	private static IndexDef.Builder definition() {
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
			);
	}

	private static FieldDef.Builder timestamp() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder()
					.setTimestamp(TimestampFieldTypeDef.getDefaultInstance())
			);
	}

	private static SearchResult search(Index index, Query... query) throws IOException {
		return index.search(SearchRequest.create().withQuery(query).build());
	}

	private static List<Object> ids(SearchResult result) {
		return result.hits().collect(SearchResult.Hit::id).toList();
	}
}
