package se.l4.exofind.engine.index.types.vectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.AbstractIndexTest;
import se.l4.exofind.engine.index.Document;
import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.IndexException;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.ObjectFieldTypeDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.schema.VectorFieldTypeDef;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.matchers.Matchers;

/**
 * Tests for a {@code knn} clause inside a {@code nested} clause - a manual held
 * as a list of chunks, each carrying its own vector, searched for the chunks
 * nearest a question.
 *
 * The chunks are laid out so that the nearest four are known from the test
 * itself, and so that two of them belong to one manual. {@code k} counts
 * values, so a document holding several of the nearest takes up several of
 * them.
 */
public class NestedVectorSearchTest extends AbstractIndexTest {
	private static final float[] X = { 1f, 0f, 0f, 0f };
	private static final float[] NEAR_X = { 0.9f, 0.1f, 0f, 0f };
	private static final float[] MID = { 0.6f, 0.8f, 0f, 0f };
	private static final float[] Y = { 0f, 1f, 0f, 0f };

	/**
	 * Nearest first: {@code a} chunk 0, {@code b} chunk 0, {@code a} chunk 1,
	 * {@code c} chunk 0.
	 */
	@Test
	public void testKnnCountsValuesRatherThanDocuments() throws IOException {
		var index = manuals();

		/*
		 * The two nearest chunks are both in `a`, so asking for two of them
		 * answers with one manual - the count is of chunks throughout.
		 */
		var result = search(
			index,
			Query.nested("chunks", Query.knn("chunks.embedding", X, 2))
		);

		assertThat(ids(result), contains("a", "b"));
	}

	@Test
	public void testDocumentsRankByTheirNearestValue() throws IOException {
		var index = manuals();

		var result = search(
			index,
			Query.nested("chunks", Query.knn("chunks.embedding", X, 4))
		);

		// `a` holds the nearest chunk of all, `c` the furthest of the four
		assertThat(ids(result), contains("a", "b", "c"));
	}

	/**
	 * The case the clause exists for: the chunks themselves come back, so a
	 * reader is handed the passages rather than the manuals holding them.
	 */
	@Test
	public void testNearestValuesAreTheHits() throws IOException {
		var index = manuals();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.nested("chunks", Query.knn("chunks.embedding", X, 3)))
				.withHits("chunks")
				.build()
		);

		assertThat(result.total().count(), is(3L));

		// One manual answers with two of its chunks, told apart by their position
		assertThat(ids(result), contains("a", "b", "a"));
		assertThat(
			result.hits().collect(SearchResult.Hit::index).toList(),
			contains(0, 0, 1)
		);
		assertThat(
			result.hits().collect(hit -> hit.value().get("text")).toList(),
			contains("first", "only", "second")
		);
	}

	/**
	 * The pre-filter narrows which values may be neighbours before the nearest
	 * are picked, so a narrowed search still answers with as many as it asked
	 * for rather than with what is left of the global nearest.
	 */
	@Test
	public void testPreFilterNarrowsWhichValuesMayBeNeighbours() throws IOException {
		var index = manuals();

		var result = index.search(
			SearchRequest.create()
				.withQuery(
					Query.nested(
						"chunks",
						Query.knn("chunks.embedding", X, 2)
							.withFilter(Query.field("chunks.lang", Matchers.equalTo("en")))
					)
				)
				.withHits("chunks")
				.build()
		);

		/*
		 * The nearest chunk of all is Swedish, so a filter applied after the
		 * neighbours were picked would have left a single hit.
		 */
		assertThat(result.total().count(), is(2L));
		assertThat(ids(result), contains("b", "a"));
		assertThat(result.hits().collect(SearchResult.Hit::index).toList(), contains(0, 1));
	}

	@Test
	public void testKnnNamingAFieldOfTheIndexInsideTheClauseIsRefused() throws IOException {
		var index = manuals();

		var e = assertThrows(
			IndexException.class,
			() -> search(index, Query.nested("chunks", Query.knn("summary", X, 2)))
		);

		assertThat(e.getCode(), is("index:query:nested:not_in_path"));
	}

	@Test
	public void testKnnNamingAValueFieldOutsideTheClauseIsRefused() throws IOException {
		var index = manuals();

		var e = assertThrows(
			IndexException.class,
			() -> search(index, Query.knn("chunks.embedding", X, 2))
		);

		assertThat(e.getCode(), is("index:query:nested:outside"));
	}

	/**
	 * Three manuals whose chunks carry vectors along a plane, so that which
	 * chunk is nearest a question is obvious from the test itself. Nearest to
	 * {@code X} first: {@code a} chunk 0, {@code b} chunk 0, {@code a} chunk 1,
	 * {@code c} chunk 0.
	 */
	private Index manuals() throws IOException {
		var index = create(
			"manuals",
			IndexDef.newBuilder()
				.putFields(
					"id",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setString(StringFieldTypeDef.getDefaultInstance())
						)
						.setPrimaryKey(true)
						.build()
				)
				.putFields(
					"summary",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder().setVector(
								VectorFieldTypeDef.newBuilder().setDimensions(4)
							)
						)
						.build()
				)
				.putFields(
					"chunks",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder().setObject(
								ObjectFieldTypeDef.newBuilder()
									.putFields(
										"text",
										FieldDef.newBuilder()
											.setType(
												FieldTypeDef.newBuilder().setString(
													StringFieldTypeDef.getDefaultInstance()
												)
											)
											.build()
									)
									.putFields(
										"lang",
										FieldDef.newBuilder()
											.setType(
												FieldTypeDef.newBuilder().setString(
													StringFieldTypeDef.getDefaultInstance()
												)
											)
											.setFilter(FilterConfig.getDefaultInstance())
											.build()
									)
									.putFields(
										"embedding",
										FieldDef.newBuilder()
											.setType(
												FieldTypeDef.newBuilder().setVector(
													VectorFieldTypeDef.newBuilder()
														.setDimensions(4)
												)
											)
											.build()
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
				new Document.Value("id", "a"),
				new Document.Value("chunks", chunk("first", "sv", X)),
				new Document.Value("chunks", chunk("second", "en", MID))
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "b"),
				new Document.Value("chunks", chunk("only", "en", NEAR_X))
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "c"),
				new Document.Value("chunks", chunk("apart", "sv", Y))
			)
		);

		index.commit();
		return index;
	}

	private static Document chunk(String text, String lang, float[] embedding) {
		return new Document(
			new Document.Value("text", text),
			new Document.Value("lang", lang),
			new Document.Value("embedding", embedding)
		);
	}

	private static SearchResult search(Index index, Query... query) throws IOException {
		return index.search(SearchRequest.create().withQuery(query).build());
	}

	private static List<Object> ids(SearchResult result) {
		return result.hits().collect(SearchResult.Hit::id).toList();
	}
}
