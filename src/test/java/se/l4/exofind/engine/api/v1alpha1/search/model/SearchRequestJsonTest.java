package se.l4.exofind.engine.api.v1alpha1.search.model;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tests for how a search looks on the wire - above all that the untagged
 * short forms land on the right kinds: a clause with no {@code type} is a
 * field clause, a matcher with no {@code type} is {@code equals} and a sort
 * with no {@code type} is a field sort.
 */
public class SearchRequestJsonTest {
	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	public void testClauseWithoutTypeIsFieldClause() throws Exception {
		var json = """
			{
				"query": [
					{ "field": "published", "match": { "value": true } }
				]
			}
			""";

		var request = mapper.readValue(json, SearchRequest.class);

		var clause = (Clause.Field) request.query().get(0);
		assertThat(clause.field(), is("published"));

		var match = (Matcher.Equals) clause.match();
		assertThat(match.value(), is(true));
	}

	@Test
	public void testFiltersAndFacets() throws Exception {
		var json = """
			{
				"filters": [
					{ "field": "category", "match": { "type": "in", "values": ["fiction"] } }
				],
				"facets": [
					{ "field": "category" },
					{ "name": "alpha", "field": "category", "limit": 5, "order": "value" }
				]
			}
			""";

		var request = mapper.readValue(json, SearchRequest.class);

		var filter = request.filters().get(0);
		assertThat(filter.field(), is("category"));
		assertThat(filter.match(), instanceOf(Matcher.In.class));

		var unnamed = request.facets().get(0);
		assertThat(unnamed.field(), is("category"));
		assertThat(unnamed.name(), is(nullValue()));
		assertThat(unnamed.limit(), is(nullValue()));
		assertThat(unnamed.order(), is(nullValue()));

		var named = request.facets().get(1);
		assertThat(named.name(), is("alpha"));
		assertThat(named.limit(), is(5));
		assertThat(named.order(), is(SearchRequest.Facet.Order.VALUE));
	}

	@Test
	public void testFacetDownATree() throws Exception {
		var json = """
			{
				"facets": [
					{ "field": "category" },
					{ "field": "category", "name": "men", "path": "Men/Shoes", "depth": 2 }
				]
			}
			""";

		var request = mapper.readValue(json, SearchRequest.class);

		var top = request.facets().get(0);
		assertThat(top.path(), is(nullValue()));
		assertThat(top.depth(), is(nullValue()));

		var drilled = request.facets().get(1);
		assertThat(drilled.path(), is("Men/Shoes"));
		assertThat(drilled.depth(), is(2));
	}

	@Test
	public void testFacetRanges() throws Exception {
		var json = """
			{
				"facets": [
					{ "field": "price", "ranges": [
						{ "to": 100 },
						{ "from": 100, "to": 200 },
						{ "from": 200 }
					] }
				]
			}
			""";

		var request = mapper.readValue(json, SearchRequest.class);

		assertThat(
			request.facets().get(0).ranges(),
			contains(
				new SearchRequest.Facet.Range(null, 100),
				new SearchRequest.Facet.Range(100, 200),
				new SearchRequest.Facet.Range(200, null)
			)
		);
	}

	@Test
	public void testMatcherTypes() throws Exception {
		var json = """
			{
				"query": [
					{ "field": "a", "match": { "type": "any" } },
					{ "field": "b", "match": { "type": "prefix", "value": "EX-" } },
					{ "field": "f", "match": { "type": "under", "path": "Men/Shoes" } },
					{ "field": "c", "match": { "type": "in", "values": ["x", "y"] } },
					{ "field": "d", "match": { "type": "range", "gte": 10, "lt": 20 } },
					{ "field": "e", "match": { "type": "text", "text": "spr", "typos": "off" } }
				]
			}
			""";

		var request = mapper.readValue(json, SearchRequest.class);

		assertThat(((Clause.Field) request.query().get(0)).match(), is(new Matcher.Any()));
		assertThat(
			((Clause.Field) request.query().get(1)).match(),
			is(new Matcher.Prefix("EX-"))
		);

		assertThat(
			((Clause.Field) request.query().get(2)).match(),
			is(new Matcher.Under("Men/Shoes"))
		);

		var in = (Matcher.In) ((Clause.Field) request.query().get(3)).match();
		assertThat(in.values(), contains("x", "y"));

		var range = (Matcher.Range) ((Clause.Field) request.query().get(4)).match();
		assertThat(range.gte(), is(10));
		assertThat(range.lt(), is(20));
		assertThat(range.gt(), is(nullValue()));

		var text = (Matcher.Text) ((Clause.Field) request.query().get(5)).match();
		assertThat(text.text(), is("spr"));
		assertThat(text.typos(), is(Matcher.Text.Typos.OFF));
		assertThat(text.match(), is(nullValue()));
	}

	@Test
	public void testNestedClause() throws Exception {
		var json = """
			{
				"query": [
					{
						"type": "nested",
						"path": "variants",
						"clauses": [
							{ "field": "variants.color", "match": { "value": "red" } }
						]
					}
				]
			}
			""";

		var request = mapper.readValue(json, SearchRequest.class);

		var clause = (Clause.Nested) request.query().get(0);
		assertThat(clause.path(), is("variants"));

		var inner = (Clause.Field) clause.clauses().get(0);
		assertThat(inner.field(), is("variants.color"));
		assertThat(((Matcher.Equals) inner.match()).value(), is("red"));
	}

	@Test
	public void testTextClauseWithWeightedAndDefaultedFields() throws Exception {
		var json = """
			{
				"query": [
					{
						"type": "text",
						"text": "silent spr",
						"fields": { "name": 3, "description": null },
						"prefix": "off"
					}
				]
			}
			""";

		var request = mapper.readValue(json, SearchRequest.class);

		var clause = (Clause.Text) request.query().get(0);
		assertThat(clause.text(), is("silent spr"));
		assertThat(clause.prefix(), is(Matcher.Text.Prefix.OFF));

		var fields = new HashMap<String, Float>();
		fields.put("name", 3f);
		fields.put("description", null);
		assertThat(clause.fields(), is(fields));
	}

	@Test
	public void testTextClauseAskingForAPhrase() throws Exception {
		var json = """
			{
				"query": [
					{ "type": "text", "text": "apple watch", "match": "phrase" }
				]
			}
			""";

		var request = mapper.readValue(json, SearchRequest.class);

		var clause = (Clause.Text) request.query().get(0);
		assertThat(clause.text(), is("apple watch"));
		assertThat(clause.match(), is(Matcher.Text.Match.PHRASE));
	}

	@Test
	public void testTextClauseAskingForAPhraseWithSlop() throws Exception {
		var json = """
			{
				"query": [
					{ "type": "text", "text": "apple watch", "match": "phrase", "slop": 2 }
				]
			}
			""";

		var request = mapper.readValue(json, SearchRequest.class);

		var clause = (Clause.Text) request.query().get(0);
		assertThat(clause.match(), is(Matcher.Text.Match.PHRASE));
		assertThat(clause.slop(), is(2));
	}

	@Test
	public void testTextClauseReadingWhatWasTyped() throws Exception {
		var json = """
			{
				"query": [
					{ "type": "text", "text": "shoes -leather", "match": "user" }
				]
			}
			""";

		var request = mapper.readValue(json, SearchRequest.class);

		var clause = (Clause.Text) request.query().get(0);
		assertThat(clause.text(), is("shoes -leather"));
		assertThat(clause.match(), is(Matcher.Text.Match.USER));
		assertThat(clause.slop(), is(nullValue()));
	}

	@Test
	public void testNestedClauses() throws Exception {
		var json = """
			{
				"query": [
					{ "type": "or", "clauses": [
						{ "field": "category", "match": { "value": "fiction" } },
						{ "type": "not", "clauses": [
							{ "field": "archived", "match": { "value": true } }
						] }
					] }
				]
			}
			""";

		var request = mapper.readValue(json, SearchRequest.class);

		var or = (Clause.Or) request.query().get(0);
		assertThat(or.clauses().get(0), is(instanceOf(Clause.Field.class)));

		var not = (Clause.Not) or.clauses().get(1);
		assertThat(not.clauses().get(0), is(instanceOf(Clause.Field.class)));
	}

	@Test
	public void testSortWithoutTypeIsFieldSort() throws Exception {
		var json = """
			{
				"sort": [
					{ "type": "score" },
					{ "field": "name", "order": "asc" }
				]
			}
			""";

		var request = mapper.readValue(json, SearchRequest.class);

		assertThat(request.sort().get(0), is(new Sort.Score(null)));
		assertThat(request.sort().get(1), is(new Sort.Field("name", Sort.Order.ASC)));
	}

	@Test
	public void testHighlightWithEmptyOptionsAndPerFieldOptions() throws Exception {
		var json = """
			{
				"highlight": {
					"fields": {
						"name": {},
						"description": { "fragments": 1, "length": 40, "pre": "<b>", "post": "</b>" }
					}
				}
			}
			""";

		var request = mapper.readValue(json, SearchRequest.class);

		assertThat(
			request.highlight().fields().get("name"),
			is(new SearchRequest.HighlightField(null, null, null, null))
		);
		assertThat(
			request.highlight().fields().get("description"),
			is(new SearchRequest.HighlightField(1, 40, "<b>", "</b>"))
		);
	}

	@Test
	public void testMatchedWithEmptyOptionsAndPerFieldOptions() throws Exception {
		var json = """
			{
				"matched": {
					"fields": {
						"variants": {},
						"chunks": { "limit": 5, "fields": ["chunks.text"] }
					}
				}
			}
			""";

		var request = mapper.readValue(json, SearchRequest.class);

		assertThat(
			request.matched().fields().get("variants"),
			is(new SearchRequest.MatchedField(null, null))
		);
		assertThat(
			request.matched().fields().get("chunks"),
			is(new SearchRequest.MatchedField(5, List.of("chunks.text")))
		);
	}

	@Test
	public void testHitsNamesThePath() throws Exception {
		var json = """
			{
				"hits": { "path": "variants" }
			}
			""";

		var request = mapper.readValue(json, SearchRequest.class);

		assertThat(request.hits(), is(new SearchRequest.Hits("variants", null)));
	}

	@Test
	public void testHitsWithFieldsOfTheValues() throws Exception {
		var json = """
			{
				"hits": { "path": "variants", "fields": ["variants.color"] }
			}
			""";

		var request = mapper.readValue(json, SearchRequest.class);

		assertThat(
			request.hits(),
			is(new SearchRequest.Hits("variants", List.of("variants.color")))
		);
	}

	@Test
	public void testPagingAndTotal() throws Exception {
		var json = """
			{
				"limit": 20,
				"after": "AW8AAAAEyAAAACg",
				"pages": { "max": 7 },
				"total": "exact"
			}
			""";

		var request = mapper.readValue(json, SearchRequest.class);

		assertThat(request.limit(), is(20));
		assertThat(request.after(), is("AW8AAAAEyAAAACg"));
		assertThat(request.offset(), is(nullValue()));
		assertThat(request.pages(), is(new SearchRequest.Pages(7)));
		assertThat(request.total(), is(SearchRequest.Total.EXACT));
	}
}
