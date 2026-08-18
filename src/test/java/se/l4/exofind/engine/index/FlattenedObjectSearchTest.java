package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.schema.DoubleFieldTypeDef;
import se.l4.exofind.engine.index.schema.FacetConfig;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.ObjectFieldTypeDef;
import se.l4.exofind.engine.index.schema.SortConfig;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.SortBy;
import se.l4.exofind.engine.query.matchers.Matchers;
import se.l4.exofind.engine.query.matchers.TextMatcher;

/**
 * Tests for flattened object fields - objects whose fields fold into the
 * document itself and answer searches as ordinary fields named by the dotted
 * path, with no {@code nested} clause in between.
 */
public class FlattenedObjectSearchTest extends AbstractIndexTest {
	@Test
	public void testFilterOnAFieldInsideASingleObject() throws IOException {
		var index = products();

		var result = search(
			index,
			Query.field("dimensions.width", Matchers.lessThan(15d))
		);

		assertThat(ids(result), contains("1"));
	}

	@Test
	public void testSortByAFieldInsideASingleObject() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.field("dimensions.width", Matchers.greaterThan(0d)))
				.withSort(SortBy.field("dimensions.width", SortBy.Order.DESCENDING))
				.build()
		);

		assertThat(ids(result), contains("2", "1"));
	}

	@Test
	public void testFacetOnAFieldInsideAFlattenedList() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.addFacet(Facet.of("attributes.material"))
				.build()
		);

		assertThat(
			result.facets().get("attributes.material").values(),
			contains(
				new SearchResult.Facet.Value("canvas", 2),
				new SearchResult.Facet.Value("leather", 1)
			)
		);
	}

	@Test
	public void testTextSearchCoversFieldsInsideObjects() throws IOException {
		var index = products();

		var result = search(
			index,
			Query.text(TextMatcher.of("hiking"))
		);

		assertThat(ids(result), contains("1"));
	}

	@Test
	public void testConditionsMatchAcrossValuesOfAList() throws IOException {
		var index = products();

		/*
		 * Product 2 pairs canvas with blue and leather with brown, yet matches
		 * canvas and brown together - the fields of a flattened list belong to
		 * the document, not to the value they arrived in. Asking that they
		 * hold inside the same value is what the nested mode is for.
		 */
		var result = search(
			index,
			Query.field("attributes.material", Matchers.equalTo("canvas")),
			Query.field("attributes.color", Matchers.equalTo("brown"))
		);

		assertThat(ids(result), contains("2"));
	}

	@Test
	public void testNestedClauseOnAFlattenedFieldIsRefused() throws IOException {
		var index = products();

		var e = assertThrows(
			IndexException.class,
			() -> search(
				index,
				Query.nested(
					"attributes",
					Query.field("attributes.color", Matchers.equalTo("blue"))
				)
			)
		);

		assertThat(e.getCode(), is("index:query:nested:flattened"));
	}

	@Test
	public void testListingReturnsEachDocumentOnce() throws IOException {
		var index = products();

		var result = index.search(SearchRequest.all());

		assertThat(result.total().count(), is(3L));
		assertThat(ids(result), containsInAnyOrder("1", "2", "3"));
	}

	@Test
	public void testDocumentComesBackWithItsValues() throws IOException {
		var index = products();

		var document = index.getDocument("1");
		var dimensions = document.get("dimensions");
		assertThat(((Document) dimensions).get("width"), is(10d));
	}

	@Test
	public void testValueGivenUnderTheDottedPathIsRefused() throws IOException {
		var index = products();

		var e = assertThrows(
			ValidationException.class,
			() -> index.addDocument(
				new Document(
					new Document.Value("id", "4"),
					new Document.Value("dimensions.width", 12d)
				)
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:update:field_inside_object"));
	}

	@Test
	public void testUnknownFieldInsideAValueIsRefused() throws IOException {
		var index = products();

		var e = assertThrows(
			ValidationException.class,
			() -> index.addDocument(
				new Document(
					new Document.Value("id", "4"),
					new Document.Value(
						"dimensions",
						new Document(new Document.Value("depth", 3d))
					)
				)
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:update:field_not_found"));
	}

	@Test
	public void testRequiredFieldIsRequiredInEveryValue() throws IOException {
		var index = products();

		var e = assertThrows(
			ValidationException.class,
			() -> index.addDocument(
				new Document(
					new Document.Value("id", "4"),
					new Document.Value(
						"attributes",
						new Document(new Document.Value("color", "green"))
					),
					new Document.Value(
						"attributes",
						new Document(new Document.Value("material", "suede"))
					)
				)
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:update:required_field_missing"));
	}

	@Test
	public void testSingleFieldRefusesSeveralValuesInsideOneValue() throws IOException {
		var index = products();

		var e = assertThrows(
			ValidationException.class,
			() -> index.addDocument(
				new Document(
					new Document.Value("id", "4"),
					new Document.Value(
						"dimensions",
						new Document(
							new Document.Value("width", 5d),
							new Document.Value("width", 6d)
						)
					)
				)
			)
		);

		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:update:not_multiple"));
	}

	@Test
	public void testEveryValueOfAListMayGiveASingleField() throws IOException {
		var index = products();

		var result = search(
			index,
			Query.field("attributes.color", Matchers.equalTo("brown"))
		);

		assertThat(ids(result), contains("2"));
	}

	/**
	 * Three products. {@code dimensions} is a single object used for grouping;
	 * {@code attributes} is a flattened list, so its values only ever narrow
	 * independently.
	 */
	private Index products() throws IOException {
		var index = create(
			"products",
			IndexDef.newBuilder()
				.putFields(
					"id",
					string().setPrimaryKey(true).build()
				)
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder().setMatching(
							StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
						)
					).build()
				)
				.putFields(
					"dimensions",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder().setObject(
								ObjectFieldTypeDef.newBuilder()
									.putFields(
										"width",
										doubleField()
											.setFilter(FilterConfig.getDefaultInstance())
											.setSort(SortConfig.getDefaultInstance())
											.build()
									)
									.putFields(
										"note",
										string(
											StringFieldTypeDef.newBuilder().setMatching(
												StringFieldTypeDef.TextUsageConfig
													.getDefaultInstance()
											)
										).build()
									)
							)
						)
						.build()
				)
				.putFields(
					"attributes",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder().setObject(
								ObjectFieldTypeDef.newBuilder()
									.putFields(
										"color",
										string()
											.setFilter(FilterConfig.getDefaultInstance())
											.setRequired(true)
											.build()
									)
									.putFields(
										"material",
										string()
											.setFilter(FilterConfig.getDefaultInstance())
											.setFacet(FacetConfig.getDefaultInstance())
											.build()
									)
									.setMode(ObjectFieldTypeDef.Mode.MODE_FLATTENED)
							)
						)
						.setMultiple(true)
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Trail Runner"),
				new Document.Value(
					"dimensions",
					new Document(
						new Document.Value("width", 10d),
						new Document.Value("note", "fits most hiking feet")
					)
				),
				new Document.Value(
					"attributes",
					new Document(
						new Document.Value("color", "red"),
						new Document.Value("material", "canvas")
					)
				)
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "City Boot"),
				new Document.Value(
					"dimensions",
					new Document(new Document.Value("width", 20d))
				),
				new Document.Value(
					"attributes",
					new Document(
						new Document.Value("color", "blue"),
						new Document.Value("material", "canvas")
					)
				),
				new Document.Value(
					"attributes",
					new Document(
						new Document.Value("color", "brown"),
						new Document.Value("material", "leather")
					)
				)
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "3"),
				new Document.Value("name", "Plain Sandal")
			)
		);

		index.commit();
		return index;
	}

	private static FieldDef.Builder string() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setString(StringFieldTypeDef.getDefaultInstance())
			);
	}

	private static FieldDef.Builder string(StringFieldTypeDef.Builder type) {
		return FieldDef.newBuilder()
			.setType(FieldTypeDef.newBuilder().setString(type));
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
