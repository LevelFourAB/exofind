package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.matchers.TextMatcher;

/**
 * Tests for how much the length of a value counts against it - the same word
 * covering a short value against it sitting inside a long one.
 *
 * Two documents holding the word once each, so the only thing between them is
 * how much of the value the word is. What is asserted is mostly the gap
 * between their scores rather than their order, because the setting is a dial
 * and turning it off is supposed to leave them tied rather than swapped.
 */
public class LengthNormalizationSearchTest extends AbstractIndexTest {
	/**
	 * How close two scores have to be to count as the same. Scores are floats
	 * that have been through several multiplications, so they are compared as
	 * numbers rather than for equality.
	 */
	private static final double PRECISION = 0.0001;

	@Test
	public void testLengthCountsAgainstAValueByDefault() throws IOException {
		var result = places("default", null);

		assertThat(ids(result), contains("short", "long"));
	}

	/**
	 * Turned off, the word says as much wherever it sits, so the two are left
	 * tied - a field holding everything a document is about wants exactly
	 * this, as a fuller list is not a worse answer.
	 */
	@Test
	public void testLengthCanBeLeftOutOfTheScore() throws IOException {
		var scores = scores(
			places(
				"none",
				StringFieldTypeDef.TextUsageConfig.LengthNormalization.LENGTH_NORMALIZATION_NONE
			)
		);

		assertThat(scores.get(0), is(closeTo(scores.get(1), PRECISION)));
	}

	@Test
	public void testLengthCanCountFully() throws IOException {
		var moderate = gap(
			places(
				"moderate",
				StringFieldTypeDef.TextUsageConfig.LengthNormalization
					.LENGTH_NORMALIZATION_MODERATE
			)
		);

		var strong = gap(
			places(
				"strong",
				StringFieldTypeDef.TextUsageConfig.LengthNormalization.LENGTH_NORMALIZATION_STRONG
			)
		);

		assertThat(strong, is(greaterThan(moderate)));
	}

	/**
	 * Naming the engine's own choice pins it, so a definition that wants
	 * today's behaviour to survive a change of mind can say so.
	 */
	@Test
	public void testNamingTheDefaultScoresTheSameAsLeavingItOut() throws IOException {
		var left = scores(places("left-out", null));

		var named = scores(
			places(
				"named",
				StringFieldTypeDef.TextUsageConfig.LengthNormalization
					.LENGTH_NORMALIZATION_MODERATE
			)
		);

		assertThat(left.get(0), is(closeTo(named.get(0), PRECISION)));
		assertThat(left.get(1), is(closeTo(named.get(1), PRECISION)));
	}

	/**
	 * How far apart the two documents scored, which is what the setting moves.
	 */
	private static double gap(SearchResult result) {
		var scores = scores(result);
		return scores.get(0) / scores.get(1);
	}

	private static List<Double> scores(SearchResult result) {
		return result.hits().collect(hit -> (double) hit.score()).toList();
	}

	/**
	 * Two places named after the same one, one of them nothing else.
	 */
	private SearchResult places(
		String name,
		StringFieldTypeDef.TextUsageConfig.LengthNormalization length
	) throws IOException {
		var usage = StringFieldTypeDef.TextUsageConfig.newBuilder();
		if(length != null) {
			usage.setLengthNormalization(length);
		}

		var index = create(
			name,
			IndexDef.newBuilder()
				.putFields(
					"id",
					FieldDef.newBuilder()
						.setPrimaryKey(true)
						.setType(
							FieldTypeDef.newBuilder()
								.setString(StringFieldTypeDef.getDefaultInstance())
						)
						.setFilter(FilterConfig.getDefaultInstance())
						.build()
				)
				.putFields(
					"title",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setString(
									StringFieldTypeDef.newBuilder().setMatching(usage)
								)
						)
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "short"),
				new Document.Value("title", "London")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "long"),
				new Document.Value(
					"title",
					"London borough council housing services department office records"
				)
			)
		);

		index.commit();

		/*
		 * The word is finished, so it is looked up as a term. A word still
		 * being typed is a prefix, which matches at a flat score and would
		 * have no length to read.
		 */
		return index.search(
			SearchRequest.create()
				.withQuery(
					Query.text(TextMatcher.of("london").withPrefix(TextMatcher.Prefix.OFF))
				)
				.build()
		);
	}

	private static List<Object> ids(SearchResult result) {
		return result.hits().collect(SearchResult.Hit::id).toList();
	}
}
