package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;

import org.eclipse.collections.impl.factory.Lists;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.DoubleFieldTypeDef;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.Int64FieldTypeDef;
import se.l4.exofind.engine.index.schema.ObjectFieldTypeDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.matchers.Matchers;

/**
 * Tests for taking documents out of an index - by the key they were indexed
 * under and by a query they match, and that what a removed document brought
 * with it goes with it.
 */
public class IndexDeleteTest extends AbstractIndexTest {
	@Test
	public void testKeyRemovesTheDocument() throws IOException {
		var index = foods();

		index.deleteDocument("1");
		index.commit();

		assertThat(index.getDocument("1"), is(nullValue()));
		assertThat(ids(index.search(SearchRequest.all())), containsInAnyOrder("2", "3"));
	}

	@Test
	public void testSeveralKeysAreOneChange() throws IOException {
		var index = foods();

		assertThat(index.deleteDocuments(Lists.immutable.of("1", "3")), is(2));
		index.commit();

		assertThat(ids(index.search(SearchRequest.all())), contains("2"));
	}

	/**
	 * A key nothing was indexed under says the same thing as one that was: the
	 * index is to hold no document under it.
	 */
	@Test
	public void testUnknownKeyChangesNothing() throws IOException {
		var index = foods();

		index.deleteDocument("does-not-exist");
		index.commit();

		assertThat(index.search(SearchRequest.all()).total().count(), is(3L));
	}

	@Test
	public void testKeyIsReadAsTheTypeOfTheKeyField() throws IOException {
		var index = numbered();

		index.deleteDocument(index.parsePrimaryKey("2"));
		index.commit();

		assertThat(ids(index.search(SearchRequest.all())), contains(1L));
	}

	@Test
	public void testKeyOfTheWrongTypeIsRefused() throws IOException {
		var index = foods();

		assertThrows(
			IndexInvalidQueryValueException.class,
			() -> index.deleteDocument(4711)
		);
	}

	/**
	 * A key the index refuses leaves the keys around it alone, so a request can
	 * be fixed and sent again without wondering how far it got.
	 */
	@Test
	public void testRefusedKeyRemovesNothing() throws IOException {
		var index = foods();

		assertThrows(
			IndexInvalidQueryValueException.class,
			() -> index.deleteDocuments(Lists.immutable.of("1", 4711))
		);
		index.commit();

		assertThat(index.search(SearchRequest.all()).total().count(), is(3L));
	}

	@Test
	public void testDeleteWithoutAPrimaryKeyIsRefused() throws IOException {
		var index = create(
			"anonymous",
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
		);

		assertThrows(IndexNoPrimaryKeyException.class, () -> index.deleteDocument("1"));
	}

	@Test
	public void testQueryRemovesEveryMatch() throws IOException {
		var index = foods();

		var deleted = index.deleteByQuery(
			Lists.immutable.of(Query.field("category", Matchers.equalTo("sylt"))),
			null
		);
		index.commit();

		assertThat(deleted, is(2));
		assertThat(ids(index.search(SearchRequest.all())), contains("3"));
	}

	/**
	 * An index without a primary key holds documents that can not be named one
	 * by one, and a query is what reaches them.
	 */
	@Test
	public void testQueryWorksWithoutAPrimaryKey() throws IOException {
		var index = create(
			"anonymous",
			IndexDef.newBuilder()
				.putFields(
					"name",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setString(StringFieldTypeDef.getDefaultInstance())
						)
						.setFilter(FilterConfig.getDefaultInstance())
						.build()
				)
		);

		index.addDocument(new Document(new Document.Value("name", "sylt")));
		index.addDocument(new Document(new Document.Value("name", "bröd")));
		index.commit();

		assertThat(
			index.deleteByQuery(
				Lists.immutable.of(Query.field("name", Matchers.equalTo("sylt"))),
				null
			),
			is(1)
		);
		index.commit();

		assertThat(index.search(SearchRequest.all()).total().count(), is(1L));
	}

	@Test
	public void testEmptyQueryEmptiesTheIndex() throws IOException {
		var index = foods();

		assertThat(index.deleteByQuery(Lists.immutable.empty(), null), is(3));
		index.commit();

		assertThat(index.search(SearchRequest.all()).total().count(), is(0L));
	}

	/**
	 * Nothing is searchable until it has been committed, so a document indexed
	 * since the last commit is not part of the count - but the query reaches it
	 * all the same, which is what lets a load be corrected before it lands.
	 */
	@Test
	public void testQueryReachesDocumentsNotYetCommitted() throws IOException {
		var index = foods();

		index.addDocument(
			new Document(
				new Document.Value("id", "4"),
				new Document.Value("name", "hjortronsylt"),
				new Document.Value("category", "sylt")
			)
		);

		var deleted = index.deleteByQuery(
			Lists.immutable.of(Query.field("category", Matchers.equalTo("sylt"))),
			null
		);
		index.commit();

		assertThat(deleted, is(2));
		assertThat(ids(index.search(SearchRequest.all())), contains("3"));
	}

	@Test
	public void testUnsupportedLocaleIsRefused() throws IOException {
		var index = foods();

		var e = assertThrows(
			IndexException.class,
			() -> index.deleteByQuery(Lists.immutable.empty(), "xx-nope")
		);

		assertThat(e.getCode(), is("index:query:unsupported_locale"));
	}

	/**
	 * The values of an object field are documents of their own, and one left
	 * behind would go on answering a {@code nested} clause for a document that
	 * is no longer in the index.
	 */
	@Test
	public void testKeyTakesTheValuesOfObjectFieldsWithIt() throws IOException {
		var index = products();

		index.deleteDocument("1");
		index.commit();

		assertThat(
			ids(
				search(
					index,
					Query.nested("variants", Query.field("variants.color", Matchers.equalTo("red")))
				)
			),
			is(empty())
		);
		assertThat(ids(index.search(SearchRequest.all())), contains("2"));
	}

	@Test
	public void testQueryTakesTheValuesOfObjectFieldsWithIt() throws IOException {
		var index = products();

		assertThat(
			index.deleteByQuery(
				Lists.immutable.of(Query.field("category", Matchers.equalTo("shoes"))),
				null
			),
			is(1)
		);
		index.commit();

		assertThat(
			ids(
				search(
					index,
					Query.nested("variants", Query.field("variants.color", Matchers.equalTo("red")))
				)
			),
			is(empty())
		);

		/*
		 * The other document keeps every one of its values - a block join
		 * deletes by block, and the wrong bit set would take the neighbours too.
		 */
		assertThat(
			ids(
				search(
					index,
					Query.nested(
						"variants",
						Query.field("variants.color", Matchers.equalTo("blue"))
					)
				)
			),
			contains("2")
		);
		assertThat(index.search(SearchRequest.all()).total().count(), is(1L));
	}

	private static SearchResult search(Index index, Query... query) throws IOException {
		return index.search(SearchRequest.create().withQuery(query).build());
	}

	private static List<Object> ids(SearchResult result) {
		return result.hits().collect(SearchResult.Hit::id).toList();
	}

	private static FieldDef.Builder string() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setString(StringFieldTypeDef.getDefaultInstance())
			);
	}

	/**
	 * An index of foods with a string key, two of which share a category.
	 */
	private Index foods() throws IOException {
		var index = create(
			"foods",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields("name", string().setStored(true).build())
				.putFields(
					"category",
					string().setFilter(FilterConfig.getDefaultInstance()).build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "blåbärssylt"),
				new Document.Value("category", "sylt")
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "hallonsylt"),
				new Document.Value("category", "sylt")
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "3"),
				new Document.Value("name", "rågbröd"),
				new Document.Value("category", "bröd")
			)
		);
		index.commit();

		return index;
	}

	/**
	 * An index whose documents are named by a number, which is what a key taken
	 * from a URL has to be read as.
	 */
	private Index numbered() throws IOException {
		var index = create(
			"numbered",
			IndexDef.newBuilder()
				.putFields(
					"id",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setInt64(Int64FieldTypeDef.getDefaultInstance())
						)
						.setPrimaryKey(true)
						.build()
				)
		);

		index.addDocument(new Document(new Document.Value("id", 1L)));
		index.addDocument(new Document(new Document.Value("id", 2L)));
		index.commit();

		return index;
	}

	/**
	 * An index whose documents carry values of an object field, so that a
	 * document is a block of Lucene documents rather than one.
	 */
	private Index products() throws IOException {
		var index = create(
			"products",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"category",
					string().setFilter(FilterConfig.getDefaultInstance()).build()
				)
				.putFields(
					"variants",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder().setObject(
								ObjectFieldTypeDef.newBuilder()
									.putFields(
										"color",
										string()
											.setFilter(FilterConfig.getDefaultInstance())
											.build()
									)
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
							)
						)
						.setMultiple(true)
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("category", "shoes"),
				new Document.Value(
					"variants",
					new Document(
						new Document.Value("color", "red"),
						new Document.Value("price", 15d)
					)
				),
				new Document.Value(
					"variants",
					new Document(
						new Document.Value("color", "black"),
						new Document.Value("price", 25d)
					)
				)
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("category", "shirts"),
				new Document.Value(
					"variants",
					new Document(
						new Document.Value("color", "blue"),
						new Document.Value("price", 10d)
					)
				)
			)
		);
		index.commit();

		return index;
	}
}
