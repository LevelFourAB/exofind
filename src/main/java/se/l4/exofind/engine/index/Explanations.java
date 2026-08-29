package se.l4.exofind.engine.index;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.lucene.search.Explanation;
import org.eclipse.collections.api.factory.Lists;

import se.l4.exofind.engine.query.SearchExplanation;

/**
 * Reads a Lucene {@link Explanation} into a {@link SearchExplanation.Detail}
 * tree.
 *
 * <p>Two things are restated on the way. The nodes {@link ClauseQuery} left in
 * the tree become the {@code clause} and {@code clauseType} of the step below
 * them, and the names a value was written under - which carry a usage and a
 * locale - become the field names of the definition.
 *
 * <p>The structure, the scores and the wording of each step are Lucene's and
 * are passed through.
 */
final class Explanations {
	/**
	 * A name a value was written under, as it turns up inside a description.
	 * The separator cannot occur in a field name a caller writes, so a name
	 * that parses is one the engine built - including a name that exists only
	 * because a wildcard field matched it, which the schema cannot confirm.
	 */
	private static final Pattern INTERNAL_NAME = Pattern.compile(
		"([A-Za-z0-9_.*]+):([A-Za-z0-9_-]+):([a-z_]+)"
	);

	private Explanations() {
	}

	/**
	 * @param explanation
	 *   an explanation of a query compiled with {@link
	 *   QueryCompiler#markClauses} in force
	 * @return
	 */
	static SearchExplanation.Detail of(Explanation explanation) {
		var mark = ClauseQuery.markOf(explanation.getDescription());
		var details = explanation.getDetails();

		if(mark == null) {
			var described = describe(explanation.getDescription());

			return new SearchExplanation.Detail(
				explanation.isMatch(),
				scoreOf(explanation),
				described.description(),
				null,
				null,
				described.field(),
				described.usage(),
				described.locale(),
				childrenOf(details)
			);
		}

		// A marking node holds one clause and nothing else, so the two collapse
		// into a single step rather than nesting one inside the other
		if(details.length == 1 && ClauseQuery.markOf(details[0].getDescription()) == null) {
			var inner = of(details[0]);

			return new SearchExplanation.Detail(
				explanation.isMatch(),
				scoreOf(explanation),
				inner.description(),
				mark.path(),
				mark.type(),
				inner.field(),
				inner.usage(),
				inner.locale(),
				inner.children()
			);
		}

		// A clause compiling to a single other clause, such as an `and` holding
		// one condition. Collapsing would drop one of the two paths
		return new SearchExplanation.Detail(
			explanation.isMatch(),
			scoreOf(explanation),
			mark.type(),
			mark.path(),
			mark.type(),
			null,
			null,
			null,
			childrenOf(details)
		);
	}

	private static org.eclipse.collections.api.list.ImmutableList<SearchExplanation.Detail>
		childrenOf(Explanation[] details)
	{
		if(details.length == 0) {
			return Lists.immutable.empty();
		}

		var children = Lists.mutable.<SearchExplanation.Detail>ofInitialCapacity(details.length);
		for(var detail : details) {
			children.add(of(detail));
		}

		return children.toImmutable();
	}

	/**
	 * What a step scored. A step that did not match scores zero rather than
	 * whatever number sits on a node Lucene never reached.
	 */
	private static float scoreOf(Explanation explanation) {
		return explanation.isMatch() ? explanation.getValue().floatValue() : 0f;
	}

	/**
	 * A description carrying the names of the definition, and the field it
	 * reads.
	 */
	private record Described(String description, String field, String usage, String locale) {
	}

	/**
	 * Rewrite the names inside a description and pick out the field it reads.
	 * A description naming several fields reports none, leaving them to the
	 * words of the step.
	 */
	private static Described describe(String description) {
		if(description == null) {
			return new Described(null, null, null, null);
		}

		var matcher = INTERNAL_NAME.matcher(description);
		if(!matcher.find()) {
			return new Described(description, null, null, null);
		}

		var rewritten = new StringBuilder();
		FieldNames.Parsed only = null;
		var several = false;

		do {
			var parsed = FieldNames.parse(matcher.group());
			if(parsed == null) {
				continue;
			}

			if(only == null) {
				only = parsed;
			} else if(!only.field().equals(parsed.field())) {
				several = true;
			}

			matcher.appendReplacement(rewritten, Matcher.quoteReplacement(parsed.field()));
		} while(matcher.find());

		matcher.appendTail(rewritten);

		if(only == null || several) {
			return new Described(rewritten.toString(), null, null, null);
		}

		return new Described(rewritten.toString(), only.field(), only.suffix(), only.locale());
	}
}
