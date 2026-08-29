package se.l4.exofind.engine.index.types.vectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
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
import se.l4.exofind.engine.index.schema.ObjectFieldTypeDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.schema.VectorFieldTypeDef;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.matchers.Matchers;

/**
 * Tests for indexing and searching vectors - the knn clause, its pre-filter,
 * quantization, and what a stored vector comes back as.
 */
public class VectorIndexingTest extends AbstractIndexTest {
	/**
	 * Vectors that point along each axis, so that which document is nearest to
	 * a query is obvious from the test itself.
	 */
	private static final float[] X = { 1f, 0f, 0f, 0f };
	private static final float[] Y = { 0f, 1f, 0f, 0f };
	private static final float[] Z = { 0f, 0f, 1f, 0f };
	private static final float[] NEAR_X = { 0.9f, 0.1f, 0f, 0f };

	@Test
	public void testKnnFindsTheNearestDocuments() throws IOException {
		var index = books(VectorFieldTypeDef.newBuilder().setDimensions(4));

		var result = search(index, Query.knn("embedding", NEAR_X, 2));

		assertThat(result.total().count(), is(2L));
		assertThat(ids(result).get(0), is("x"));
	}

	@Test
	public void testKnnScoresNearerDocumentsHigher() throws IOException {
		var index = books(VectorFieldTypeDef.newBuilder().setDimensions(4));

		var result = search(index, Query.knn("embedding", NEAR_X, 3));

		assertThat(ids(result).get(0), is("x"));
	}

	/**
	 * Wider than Lucene's stock ceiling of 1024, which the codec raises - the
	 * width the common embedding models produce.
	 */
	@Test
	public void testWideVectorsIndexAndSearch() throws IOException {
		var index = create(
			definition(VectorFieldTypeDef.newBuilder().setDimensions(1536))
		);

		index.addDocument(doc("1", wide(1536, 0)));
		index.addDocument(doc("2", wide(1536, 500)));
		index.commit();

		var result = search(index, Query.knn("embedding", wide(1536, 0), 1));

		assertThat(ids(result), contains("1"));
	}

	@Test
	public void testInt8QuantizationIndexesAndSearches() throws IOException {
		var index = books(
			VectorFieldTypeDef.newBuilder()
				.setDimensions(4)
				.setQuantization(VectorFieldTypeDef.Quantization.QUANTIZATION_INT8)
		);

		var result = search(index, Query.knn("embedding", NEAR_X, 2));

		assertThat(ids(result).get(0), is("x"));
	}

	@Test
	public void testInt4QuantizationIndexesAndSearches() throws IOException {
		var index = books(
			VectorFieldTypeDef.newBuilder()
				.setDimensions(4)
				.setQuantization(VectorFieldTypeDef.Quantization.QUANTIZATION_INT4)
		);

		var result = search(index, Query.knn("embedding", NEAR_X, 2));

		assertThat(ids(result).get(0), is("x"));
	}

	@Test
	public void testInt4QuantizationHandlesOddDimensions() throws IOException {
		var index = create(
			definition(
				VectorFieldTypeDef.newBuilder()
					.setDimensions(5)
					.setQuantization(VectorFieldTypeDef.Quantization.QUANTIZATION_INT4)
			)
		);

		index.addDocument(doc("1", new float[] { 1f, 0f, 0f, 0f, 0f }));
		index.addDocument(doc("2", new float[] { 0f, 1f, 0f, 0f, 0f }));
		index.commit();

		var result = search(index, Query.knn("embedding", new float[] { 1f, 0f, 0f, 0f, 0.1f }, 1));

		assertThat(ids(result), contains("1"));
	}

	@Test
	public void testCustomHnswParametersIndexAndSearch() throws IOException {
		var index = books(
			VectorFieldTypeDef.newBuilder()
				.setDimensions(4)
				.setHnsw(
					VectorFieldTypeDef.HNSWConfig.newBuilder()
						.setM(48)
						.setEfConstruction(400)
				)
		);

		var result = search(index, Query.knn("embedding", NEAR_X, 2));

		assertThat(ids(result).get(0), is("x"));
	}

	/**
	 * A stored vector is kept per field, so it comes back even when the index
	 * keeps no copy of the document.
	 */
	@Test
	public void testStoredVectorComesBackWithoutASource() throws IOException {
		var index = create(
			definition(VectorFieldTypeDef.newBuilder().setDimensions(4), true)
				.setSource(IndexDef.SourceMode.SOURCE_MODE_NONE)
		);

		index.addDocument(doc("1", X));
		index.commit();

		var doc = index.getDocument("1");
		assertThat(doc.get("embedding"), is(X));
	}

	@Test
	public void testVectorComesBackFromTheSource() throws IOException {
		var index = books(VectorFieldTypeDef.newBuilder().setDimensions(4));

		var doc = index.getDocument("x");
		assertThat(doc.get("embedding"), is(X));
	}

	@Test
	public void testVectorIsNotReturnedWhenNeitherStoredNorInTheSource() throws IOException {
		var index = create(
			definition(VectorFieldTypeDef.newBuilder().setDimensions(4))
				.setSource(IndexDef.SourceMode.SOURCE_MODE_NONE)
		);

		index.addDocument(doc("1", X));
		index.commit();

		assertThat(index.getDocument("1").get("embedding"), is(nullValue()));
	}

	/**
	 * A wrong vector joins the other problems of the document, so everything
	 * wrong with it is reported at once.
	 */
	@Test
	public void testWrongDimensionsAreCollectedWithOtherErrors() throws IOException {
		var index = books(VectorFieldTypeDef.newBuilder().setDimensions(4));

		var e = assertThrows(
			ValidationException.class,
			() -> index.addDocument(
				new Document(
					new Document.Value("id", "broken"),
					new Document.Value("embedding", new float[] { 1f, 2f }),
					new Document.Value("unknown", "value")
				)
			)
		);

		var codes = e.getErrors().collect(error -> error.getCode()).toList();
		assertThat(codes, hasItem("index:update:vector:wrong_dimensions"));
		assertThat(codes, hasItem("index:update:field_not_found"));
	}

	/**
	 * knn scores, so combining it with a text clause through `or` adds the two
	 * rankings together - the plain Lucene form of a hybrid search.
	 */
	@Test
	public void testHybridSearchCombinesKnnAndText() throws IOException {
		var index = books(VectorFieldTypeDef.newBuilder().setDimensions(4));

		var result = search(
			index,
			Query.or(
				Query.knn("embedding", NEAR_X, 2),
				Query.text("themes")
			)
		);

		// `x` matches both ways, so it comes out on top
		assertThat(ids(result).get(0), is("x"));
		assertThat(ids(result), hasItem("z"));
	}

	/**
	 * The pre-filter narrows which documents may be neighbours before the
	 * nearest are picked, so a filtered search still returns k results rather
	 * than the global top-k with most of it filtered away.
	 */
	@Test
	public void testKnnWithPreFilterReturnsKFilteredResults() throws IOException {
		var index = books(VectorFieldTypeDef.newBuilder().setDimensions(4));

		var result = search(
			index,
			Query.knn("embedding", NEAR_X, 2)
				.withFilter(Query.field("category", Matchers.equalTo("poetry")))
		);

		// The nearest documents are in the other category, so a filter applied
		// after the fact would have left nothing
		assertThat(ids(result), containsInAnyOrder("y", "z"));
	}

	@Test
	public void testUpdatingByPrimaryKeyReplacesTheVector() throws IOException {
		var index = books(VectorFieldTypeDef.newBuilder().setDimensions(4));

		index.addDocument(doc("x", new float[] { 0f, 0f, 0f, 1f }));
		index.commit();

		var result = search(index, Query.knn("embedding", NEAR_X, 3));

		// Still three documents - the vector was replaced, not added
		assertThat(result.total().count(), is(3L));
		// `x` is now orthogonal to the query, so `y` is the nearest instead
		assertThat(ids(result).get(0), is("y"));
	}

	/**
	 * Changing quantization only affects segments flushed from then on, the
	 * way an analysis change behaves - a search reads both kinds. Reaching
	 * nothing already indexed is what makes it a change the index only takes
	 * when the caller says the documents may go stale.
	 */
	@Test
	public void testQuantizationChangeSearchesAcrossOldAndNewSegments() throws IOException {
		var index = books(VectorFieldTypeDef.newBuilder().setDimensions(4));

		index.updateDefinition(
			definition(
				VectorFieldTypeDef.newBuilder()
					.setDimensions(4)
					.setQuantization(VectorFieldTypeDef.Quantization.QUANTIZATION_INT8)
			).build(),
			null,
			true
		);

		index.addDocument(doc("w", new float[] { 0.7f, 0.7f, 0f, 0f }));
		index.commit();

		var result = search(index, Query.knn("embedding", NEAR_X, 4));

		assertThat(result.total().count(), is(4L));
		assertThat(ids(result), containsInAnyOrder("x", "y", "z", "w"));
	}

	/**
	 * A vector field whose name is a pattern is resolved through the schema
	 * when the codec picks the format of the Lucene field it was written as.
	 */
	@Test
	public void testWildcardNamedVectorField() throws IOException {
		var index = create(
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"emb_*",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setVector(
									VectorFieldTypeDef.newBuilder()
										.setDimensions(4)
										.setQuantization(
											VectorFieldTypeDef.Quantization.QUANTIZATION_INT8
										)
								)
						)
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("emb_title", X)
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("emb_title", Y)
			)
		);
		index.commit();

		var result = search(index, Query.knn("emb_title", NEAR_X, 1));

		assertThat(ids(result), contains("1"));
	}

	/**
	 * A vector inside an object is written into the child document its value
	 * became, under its dotted path, and the codec has to find it there to
	 * write it as the definition asks. Held wide on purpose: the stock format
	 * stops at 1024 dimensions, so a field the codec did not resolve would be
	 * refused as it is written rather than quietly written another way.
	 */
	@Test
	public void testWideVectorInsideAnObjectIsWrittenAsDefined() throws IOException {
		var index = create(
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"chunks",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder().setObject(
								ObjectFieldTypeDef.newBuilder()
									.putFields(
										"embedding",
										FieldDef.newBuilder()
											.setType(
												FieldTypeDef.newBuilder().setVector(
													VectorFieldTypeDef.newBuilder()
														.setDimensions(1536)
														.setQuantization(
															VectorFieldTypeDef.Quantization
																.QUANTIZATION_INT8
														)
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
				new Document.Value("id", "1"),
				new Document.Value(
					"chunks",
					new Document(new Document.Value("embedding", wide(1536, 0)))
				),
				new Document.Value(
					"chunks",
					new Document(new Document.Value("embedding", wide(1536, 500)))
				)
			)
		);
		index.commit();

		var doc = index.getDocument("1");
		assertThat(doc.getAll("chunks"), hasSize(2));
	}

	/**
	 * An index of three documents whose vectors point along the axes, with a
	 * category to filter on and a title to search hybrid with.
	 *
	 * @param vector
	 *   the vector type under test
	 * @return
	 * @throws IOException
	 */
	private Index books(VectorFieldTypeDef.Builder vector) throws IOException {
		var index = create(definition(vector));

		index.addDocument(
			new Document(
				new Document.Value("id", "x"),
				new Document.Value("title", "A book about themes"),
				new Document.Value("category", "fiction"),
				new Document.Value("embedding", X)
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "y"),
				new Document.Value("title", "Another book"),
				new Document.Value("category", "poetry"),
				new Document.Value("embedding", Y)
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "z"),
				new Document.Value("title", "Themes and more themes"),
				new Document.Value("category", "poetry"),
				new Document.Value("embedding", Z)
			)
		);

		index.commit();
		return index;
	}

	private static IndexDef.Builder definition(VectorFieldTypeDef.Builder vector) {
		return definition(vector, false);
	}

	private static IndexDef.Builder definition(
		VectorFieldTypeDef.Builder vector,
		boolean stored
	) {
		return IndexDef.newBuilder()
			.putFields("id", string().setPrimaryKey(true).build())
			.putFields(
				"title",
				string()
					.setType(
						FieldTypeDef.newBuilder()
							.setString(
								StringFieldTypeDef.newBuilder()
									.setMatching(
										StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
									)
							)
					)
					.build()
			)
			.putFields(
				"category",
				string().setFilter(FilterConfig.getDefaultInstance()).build()
			)
			.putFields(
				"embedding",
				FieldDef.newBuilder()
					.setType(FieldTypeDef.newBuilder().setVector(vector))
					.setStored(stored)
					.build()
			);
	}

	private static FieldDef.Builder string() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setString(StringFieldTypeDef.getDefaultInstance())
			);
	}

	private static Document doc(String id, float[] embedding) {
		return new Document(
			new Document.Value("id", id),
			new Document.Value("embedding", embedding)
		);
	}

	/**
	 * A wide vector with its weight at one position, so two of them are only
	 * near when the position matches.
	 *
	 * @param dimensions
	 * @param hot
	 * @return
	 */
	private static float[] wide(int dimensions, int hot) {
		var vector = new float[dimensions];
		vector[hot] = 1f;
		return vector;
	}

	private static SearchResult search(Index index, Query... query) throws IOException {
		return index.search(SearchRequest.create().withQuery(query).build());
	}

	private static List<Object> ids(SearchResult result) {
		return result.hits().collect(SearchResult.Hit::id).toList();
	}
}
