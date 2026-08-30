package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;

import org.eclipse.collections.impl.factory.Lists;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.ObjectFieldTypeDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.matchers.Matchers;

/**
 * Tests for highlighting fields below a nested list - fragments come back on
 * the hits that stand for the list's values, cut from each hit's own value.
 */
public class NestedHighlightSearchTest extends AbstractIndexTest {
	@Test
	public void testValueHitsCarryFragmentsOfTheirOwnValue() throws IOException {
		var index = shop(false);

		var result = index.search(
			SearchRequest.create()
				.withQuery(leather())
				.withHits("variants")
				.addHighlight("variants.note")
				.build()
		);

		assertThat(result.hits().size(), is(2));

		var first = result.hits().detect(h -> "1".equals(h.id()));
		assertThat(
			fragments(first, "variants.note"),
			contains("Soft <em>leather</em> upper")
		);

		var second = result.hits().detect(h -> "2".equals(h.id()));
		assertThat(
			fragments(second, "variants.note"),
			contains("<em>Leather</em> care kit included")
		);
	}

	/**
	 * The fragment of a hit is cut from that hit's own value - a document
	 * whose other values also hold text never bleeds into it.
	 */
	@Test
	public void testEachValueHitIsCutFromItsOwnText() throws IOException {
		var index = shop(false);

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.nested("variants", Query.text("canvas")))
				.withHits("variants")
				.addHighlight("variants.note")
				.build()
		);

		assertThat(result.hits().size(), is(1));
		var hit = result.hits().getFirst();
		assertThat(hit.index(), is(1));
		assertThat(
			fragments(hit, "variants.note"),
			contains("Vegan <em>canvas</em> shoe")
		);
	}

	/**
	 * With {@code when} given the page holds both kinds of hit. The
	 * highlighted fields sit inside the values, so a document answering as
	 * itself answers with no fragments.
	 */
	@Test
	public void testDocumentAnsweringAsItselfCarriesNoFragments() throws IOException {
		var index = shop(false);

		var result = index.search(
			SearchRequest.create()
				.withQuery(leather())
				.withHits(new SearchRequest.Hits(
					"variants",
					null,
					Lists.immutable.of(
						Query.field("category", Matchers.equalTo("shoes"))
					)
				))
				.addHighlight("variants.note")
				.build()
		);

		var expanded = result.hits().detect(h -> "1".equals(h.id()));
		assertThat(expanded.index(), is(0));
		assertThat(
			fragments(expanded, "variants.note"),
			contains("Soft <em>leather</em> upper")
		);

		var itself = result.hits().detect(h -> "2".equals(h.id()));
		assertThat(itself.index(), is(nullValue()));
		assertThat(itself.highlights().isEmpty(), is(true));
	}

	@Test
	public void testHighlightingWorksWithoutTheKeptDocument() throws IOException {
		var index = shop(true);

		var result = index.search(
			SearchRequest.create()
				.withQuery(leather())
				.withHits("variants")
				.addHighlight("variants.note")
				.build()
		);

		var first = result.hits().detect(h -> "1".equals(h.id()));
		assertThat(
			fragments(first, "variants.note"),
			contains("Soft <em>leather</em> upper")
		);
	}

	@Test
	public void testHighlightingAFieldOfTheIndexOnAValuePageIsRefused() throws IOException {
		var index = shop(false);

		assertThrows(
			IllegalArgumentException.class,
			() -> index.search(
				SearchRequest.create()
					.withQuery(leather())
					.withHits("variants")
					.addHighlight("title")
					.build()
			)
		);
	}

	@Test
	public void testHighlightingAFieldNotDefinedForItIsRefused() throws IOException {
		var index = shop(false);

		var e = assertThrows(
			IndexFieldUsageException.class,
			() -> index.search(
				SearchRequest.create()
					.withQuery(leather())
					.withHits("variants")
					.addHighlight("variants.color")
					.build()
			)
		);

		assertThat(e.getCode(), is("index:query:usage_not_enabled"));
	}

	private static Query leather() {
		return Query.nested("variants", Query.text("leather"));
	}

	private static List<String> fragments(SearchResult.Hit hit, String field) {
		var found = hit.highlights().get(field);
		return found == null ? null : found.castToList();
	}

	/**
	 * A shop whose {@code variants} nested list holds a highlightable
	 * {@code note} beside a {@code color} that never highlights, with a
	 * {@code category} at the root for expanding only some documents.
	 */
	private Index shop(boolean withoutSource) throws IOException {
		var definition = IndexDef.newBuilder()
			.putFields("id", string().setPrimaryKey(true).build())
			.putFields(
				"title",
				string(
					StringFieldTypeDef.newBuilder().setMatching(
						StringFieldTypeDef.TextUsageConfig.newBuilder()
							.setHighlight(
								StringFieldTypeDef.TextUsageConfig.HighlightConfig
									.getDefaultInstance()
							)
					)
				).build()
			)
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
									"note",
									string(
										StringFieldTypeDef.newBuilder().setMatching(
											StringFieldTypeDef.TextUsageConfig.newBuilder()
												.setHighlight(
													StringFieldTypeDef.TextUsageConfig.HighlightConfig
														.getDefaultInstance()
												)
										)
									).build()
								)
								.putFields(
									"color",
									string()
										.setFilter(FilterConfig.getDefaultInstance())
										.build()
								)
								.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
						)
					)
					.setMultiple(true)
					.build()
			);

		if(withoutSource) {
			definition.setSource(IndexDef.SourceMode.SOURCE_MODE_NONE);
		}

		var index = create(withoutSource ? "shop-bare" : "shop", definition);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("category", "shoes"),
				new Document.Value(
					"variants",
					new Document(
						new Document.Value("note", "Soft leather upper"),
						new Document.Value("color", "brown")
					)
				),
				new Document.Value(
					"variants",
					new Document(
						new Document.Value("note", "Vegan canvas shoe"),
						new Document.Value("color", "green")
					)
				)
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("category", "kits"),
				new Document.Value(
					"variants",
					new Document(
						new Document.Value("note", "Leather care kit included"),
						new Document.Value("color", "black")
					)
				)
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
}
