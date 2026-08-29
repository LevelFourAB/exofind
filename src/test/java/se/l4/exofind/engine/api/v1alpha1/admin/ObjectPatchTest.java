package se.l4.exofind.engine.api.v1alpha1.admin;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import se.l4.exofind.engine.errors.ValidationException;

/**
 * Tests for the paths a change to part of an admin object is written as: what
 * each one names, what it leaves alone, and which of them are refused rather
 * than quietly doing something else.
 */
public class ObjectPatchTest {
	private final ObjectMapper mapper = new ObjectMapper();

	private JsonNode json(String text) {
		try {
			return mapper.readTree(text);
		} catch(JsonProcessingException e) {
			throw new AssertionError(e);
		}
	}

	/**
	 * Apply a change, taking the paths in the order they are written here.
	 */
	private JsonNode patch(String base, Object... pathsAndValues) {
		var changes = new LinkedHashMap<String, Object>();
		for(var i = 0; i < pathsAndValues.length; i += 2) {
			changes.put((String) pathsAndValues[i], pathsAndValues[i + 1]);
		}

		return ObjectPatch.applyTo(json(base), changes, mapper);
	}

	private String codeOf(Executable executable) {
		return assertThrows(ValidationException.class, executable).getErrors().get(0).getCode();
	}

	@Test
	public void testAValueReplacesWhatThePathNames() {
		assertThat(
			patch("{\"ranking\":{\"signals\":[]},\"other\":1}", "ranking", Map.of("a", 1)),
			is(json("{\"ranking\":{\"a\":1},\"other\":1}"))
		);
	}

	@Test
	public void testNullClearsWhatThePathNames() {
		assertThat(
			patch("{\"ranking\":{\"signals\":[]},\"other\":1}", "ranking", null),
			is(json("{\"other\":1}"))
		);
	}

	@Test
	public void testAPlaceNoPathNamesIsLeftAlone() {
		assertThat(
			patch(
				"{\"ranking\":{\"signals\":[],\"tieBreakers\":[{\"field\":\"id\"}]}}",
				"ranking.signals", List.of(Map.of("field", "sales"))
			),
			is(json(
				"{\"ranking\":{\"signals\":[{\"field\":\"sales\"}],"
				+ "\"tieBreakers\":[{\"field\":\"id\"}]}}"
			))
		);
	}

	/**
	 * A path may name a place nothing has been stored under yet, so the first
	 * change to a part of an object does not have to send its parents.
	 */
	@Test
	public void testObjectsAlongTheWayAreMade() {
		assertThat(
			patch("{}", "ranking.signals", List.of()),
			is(json("{\"ranking\":{\"signals\":[]}}"))
		);
	}

	@Test
	public void testASelectorReachesOneEntryOfAList() {
		assertThat(
			patch(
				"{\"ranking\":{\"signals\":["
				+ "{\"field\":\"sales\",\"weight\":1},"
				+ "{\"field\":\"views\",\"weight\":1}]}}",
				"ranking.signals[field=views].weight", 3
			),
			is(json(
				"{\"ranking\":{\"signals\":["
				+ "{\"field\":\"sales\",\"weight\":1},"
				+ "{\"field\":\"views\",\"weight\":3}]}}"
			))
		);
	}

	@Test
	public void testASelectorReplacesOneEntryWhole() {
		assertThat(
			patch(
				"{\"signals\":[{\"field\":\"sales\",\"weight\":1},{\"field\":\"views\"}]}",
				"signals[field=sales]", Map.of("field", "sales")
			),
			is(json("{\"signals\":[{\"field\":\"sales\"},{\"field\":\"views\"}]}"))
		);
	}

	@Test
	public void testNullOnASelectorRemovesTheEntry() {
		assertThat(
			patch(
				"{\"signals\":[{\"field\":\"sales\"},{\"field\":\"views\"}]}",
				"signals[field=sales]", null
			),
			is(json("{\"signals\":[{\"field\":\"views\"}]}"))
		);
	}

	@Test
	public void testEmptyBracketsAddAnEntry() {
		assertThat(
			patch("{\"signals\":[{\"field\":\"sales\"}]}", "signals[]", Map.of("field", "views")),
			is(json("{\"signals\":[{\"field\":\"sales\"},{\"field\":\"views\"}]}"))
		);
	}

	@Test
	public void testEmptyBracketsMakeTheListWhereThereIsNone() {
		assertThat(
			patch("{}", "signals[]", Map.of("field", "views")),
			is(json("{\"signals\":[{\"field\":\"views\"}]}"))
		);
	}

	/**
	 * Values are compared as text, so a selector matches a number written the
	 * way the object holds it.
	 */
	@Test
	public void testASelectorMatchesANumberByItsDigits() {
		assertThat(
			patch("{\"signals\":[{\"weight\":2,\"field\":\"a\"}]}", "signals[weight=2].field", "b"),
			is(json("{\"signals\":[{\"weight\":2,\"field\":\"b\"}]}"))
		);
	}

	@Test
	public void testChangesAreAppliedInTheOrderTheyAreWritten() {
		assertThat(
			patch("{}", "a", 1, "a", 2),
			is(json("{\"a\":2}"))
		);
	}

	@Test
	public void testABackslashHoldsABracketInASelector() {
		assertThat(
			patch("{\"signals\":[{\"field\":\"a]b\"}]}", "signals[field=a\\]b].weight", 2),
			is(json("{\"signals\":[{\"field\":\"a]b\",\"weight\":2}]}"))
		);
	}

	@Test
	public void testASelectorMatchingNothingIsRefused() {
		assertThat(
			codeOf(() -> patch("{\"signals\":[{\"field\":\"a\"}]}", "signals[field=b]", 1)),
			is("request:update:no_match")
		);
	}

	/**
	 * A selector picks an entry rather than inventing one, so a list that is
	 * not there matches nothing rather than being made.
	 */
	@Test
	public void testASelectorOnAMissingListIsRefused() {
		assertThat(
			codeOf(() -> patch("{}", "signals[field=a]", 1)),
			is("request:update:no_match")
		);
	}

	@Test
	public void testASelectorOnSomethingThatIsNotAListIsRefused() {
		assertThat(
			codeOf(() -> patch("{\"ranking\":{}}", "ranking[field=a]", 1)),
			is("request:update:selector_not_supported")
		);
	}

	@Test
	public void testReachingIntoAListWithoutASelectorIsRefused() {
		assertThat(
			codeOf(() -> patch("{\"signals\":[]}", "signals.weight", 1)),
			is("request:update:value_required")
		);
	}

	@Test
	public void testReachingInsideAValueHoldingNoFieldsIsRefused() {
		assertThat(
			codeOf(() -> patch("{\"weight\":2}", "weight.pivot", 1)),
			is("request:update:not_an_object")
		);
	}

	@Test
	public void testReachingInsideAnAddedValueIsRefused() {
		assertThat(
			codeOf(() -> patch("{}", "signals[].weight", 1)),
			is("request:update:add_reaches_inside")
		);
	}

	@Test
	public void testAPathThatIsNotOneIsRefused() {
		assertThat(codeOf(() -> patch("{}", "", 1)), is("request:update:path_invalid"));
		assertThat(codeOf(() -> patch("{}", "a.", 1)), is("request:update:path_invalid"));
		assertThat(codeOf(() -> patch("{}", ".a", 1)), is("request:update:path_invalid"));
		assertThat(codeOf(() -> patch("{}", "a[field=b", 1)), is("request:update:path_invalid"));
		assertThat(codeOf(() -> patch("{}", "a[field=b]c", 1)), is("request:update:path_invalid"));
		assertThat(codeOf(() -> patch("{}", "a[b]", 1)), is("request:update:path_invalid"));
		assertThat(codeOf(() -> patch("{}", "a b", 1)), is("request:update:path_invalid"));
	}

	@Test
	public void testTheObjectTheChangeWasBuiltOnIsLeftAsItWas() {
		var base = json("{\"ranking\":{\"signals\":[{\"field\":\"a\"}]}}");

		var changes = new LinkedHashMap<String, Object>();
		changes.put("ranking.signals[field=a]", null);
		ObjectPatch.applyTo(base, changes, mapper);

		assertThat(base, is(json("{\"ranking\":{\"signals\":[{\"field\":\"a\"}]}}")));
	}
}
