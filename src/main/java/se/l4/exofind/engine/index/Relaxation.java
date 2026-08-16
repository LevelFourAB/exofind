package se.l4.exofind.engine.index;

import java.io.IOException;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.set.SetIterable;

import se.l4.exofind.engine.query.AndQuery;
import se.l4.exofind.engine.query.BoostQuery;
import se.l4.exofind.engine.query.FieldQuery;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.TextQuery;
import se.l4.exofind.engine.query.matchers.TextMatcher;
import se.l4.exofind.engine.query.matchers.UserText;

/**
 * Letting go of words rather than answering a search with nothing.
 *
 * Long text finds nothing more often the more of it there is - a shopper who
 * types {@code mens waterproof running shoes size 44} has written six things
 * that all have to hold at once, and one word the catalogue happens not to use
 * empties the page. That is the one answer they can do nothing about, so a
 * search that came back with nothing is run again with a word let go of, until
 * something is found.
 *
 * Three rules keep this from quietly answering a different question:
 *
 * <ul>
 * <li>It is the search as a whole finding nothing that starts it, never a
 * clause on its own, so relaxing can only ever turn an empty page into results
 * - a search that found something is answered exactly as it was asked.
 * <li>Only the loose words of a single text clause are ever let go. A quoted
 * phrase and a word marked to be left out were asked for deliberately, and a
 * search that built several text clauses said something about how its parts
 * combine that the engine has no business second-guessing.
 * <li>Whatever went is reported, and the words that went come back as a
 * {@link BoostQuery boost}, so a document that does hold them still rises to
 * the top of the page they were dropped from.
 * </ul>
 *
 * Nothing here runs on a search that found something. The counting below is
 * paid for only where the alternative was an empty page, which is worth almost
 * any amount of work.
 */
final class Relaxation {
	/**
	 * How much a document holding a word that was let go counts for. The word
	 * is no longer a condition, so this is the whole of what it still says.
	 */
	private static final float DROPPED_WEIGHT = 1f;

	/**
	 * How many words a text may hold and still be relaxed. Every word costs a
	 * count of its own, and a text this long was not typed into a search box -
	 * it is a caller pasting a document, which relaxing one word at a time
	 * would not rescue anyway.
	 */
	private static final int MAX_WORDS = 32;

	/**
	 * Counting how many documents a set of clauses matches.
	 */
	@FunctionalInterface
	interface Counting {
		long count(ImmutableList<Query> clauses) throws IOException;
	}

	/**
	 * What relaxing a search arrived at.
	 *
	 * @param query
	 *   the clauses to search with instead
	 * @param relaxed
	 *   what was let go, to be answered alongside the results
	 */
	record Outcome(
		ImmutableList<Query> query,
		SearchResult.Relaxed relaxed
	) {
	}

	private final ImmutableList<Query> clauses;
	private final Query clause;
	private final TextMatcher matcher;

	/**
	 * The loose words as they were typed, which is what the text is rebuilt
	 * from - a word somebody typed twice stays there twice.
	 */
	private final ImmutableList<String> typed;

	/**
	 * The distinct words among them, which is what is judged, let go of and
	 * reported. The same word twice is one thing to look for.
	 */
	private final ImmutableList<String> words;

	private Relaxation(
		ImmutableList<Query> clauses,
		Query clause,
		TextMatcher matcher,
		ImmutableList<String> typed
	) {
		this.clauses = clauses;
		this.clause = clause;
		this.matcher = matcher;
		this.typed = typed;
		this.words = typed.distinct();
	}

	/**
	 * Find what a search can let go of, or {@code null} when it can let go of
	 * nothing and has to be answered as it was asked.
	 *
	 * @param clauses
	 *   the clauses of the search, without its filters - a filter holds no text
	 *   and is never relaxed
	 * @return
	 */
	static Relaxation of(ImmutableList<Query> clauses) {
		var found = Lists.mutable.<Query>empty();
		collect(clauses, found);

		/*
		 * Exactly one text clause is the search box a person typed into. None
		 * has nothing to give up, and several is a caller that assembled its
		 * query out of parts, where dropping a word from one of them changes a
		 * shape that was built on purpose.
		 */
		if(found.size() != 1) {
			return null;
		}

		var clause = found.get(0);
		var matcher = matcherOf(clause);
		var typed = wordsOf(matcher);

		if(typed.isEmpty() || typed.size() > MAX_WORDS) {
			return null;
		}

		return new Relaxation(clauses, clause, matcher, typed);
	}

	/**
	 * Gather the text clauses a search can let words go from.
	 *
	 * Only clauses every document has to satisfy are gathered. Inside an
	 * {@code or} a clause is one of several ways to match and the others were
	 * meant to catch what it missed; inside a {@code not} it is what somebody
	 * asked to be rid of; inside a {@code boost} it never narrowed anything to
	 * begin with. None of them is why the page is empty.
	 */
	private static void collect(ListIterable<Query> clauses, MutableList<Query> found) {
		for(var clause : clauses) {
			if(clause instanceof AndQuery q) {
				collect(q.clauses(), found);
			} else if(matcherOf(clause) != null) {
				found.add(clause);
			}
		}
	}

	/**
	 * Get the matcher a clause lets words go from, or {@code null} if it is not
	 * such a clause.
	 *
	 * A phrase holds the words somebody put in quotes and matching any word is
	 * already as wide as a search goes, so neither has anything to let go of.
	 */
	private static TextMatcher matcherOf(Query clause) {
		TextMatcher matcher = null;
		if(clause instanceof TextQuery q) {
			matcher = q.matcher();
		} else if(clause instanceof FieldQuery q && q.matcher() instanceof TextMatcher m) {
			matcher = m;
		}

		if(matcher == null || matcher.relax() == TextMatcher.Relax.OFF) {
			return null;
		}

		return matcher.match() == TextMatcher.Match.ALL
			|| matcher.match() == TextMatcher.Match.USER
			? matcher
			: null;
	}

	/**
	 * Get the words of a text that may be let go, each of them once and in the
	 * order they were typed.
	 */
	private static ImmutableList<String> wordsOf(TextMatcher matcher) {
		if(matcher.match() == TextMatcher.Match.USER) {
			return UserText.parse(matcher.text()).words();
		}

		if(matcher.text() == null || matcher.text().isBlank()) {
			return Lists.immutable.empty();
		}

		return Lists.immutable.of(matcher.text().strip().split("\\s+"));
	}

	/**
	 * Get the text of the clause with the given words left out, or {@code null}
	 * when there would be no word left to look for.
	 *
	 * Letting go of the last word is where relaxing stops. A text of nothing
	 * asks for nothing, and one of nothing but an exclusion asks for everything
	 * the exclusion does not name - neither is an answer to what was typed, and
	 * both look to the caller like the search worked.
	 */
	private String textWithout(SetIterable<String> dropped) {
		if(matcher.match() == TextMatcher.Match.USER) {
			var remaining = UserText.parse(matcher.text()).without(dropped);

			// A quoted phrase is still something to look for; an exclusion is not
			return remaining.parts().anySatisfy(part -> !part.exclude())
				? remaining.text()
				: null;
		}

		var text = typed.reject(dropped::contains).makeString(" ");
		return text.isBlank() ? null : text;
	}

	/**
	 * Get the search as it stands without its text clause at all, for asking
	 * whether the words are what emptied the page. When nothing matches even
	 * with them gone, a filter or another clause is the reason and no amount of
	 * relaxing will find anything.
	 */
	ImmutableList<Query> withoutText() {
		return replace(clauses, null);
	}

	/**
	 * Get a search for one word alone, for asking how much of the index holds
	 * it.
	 *
	 * Asked without the filters and the other clauses of the search, because
	 * what is being judged is the word - a word every document holds is still
	 * the least telling one, however few of them a filter leaves. The word is
	 * looked for the way the search would have looked for it, so a half typed
	 * one is still a prefix and a misspelled one may still be forgiven.
	 */
	private ImmutableList<Query> probe(String word) {
		var last = word.equals(typed.getLast())
			&& matcher.prefix() == TextMatcher.Prefix.LAST_TOKEN;

		return Lists.immutable.of(
			withText(
				matcher.withText(word)
					.withMatch(TextMatcher.Match.ALL)
					.withPrefix(last ? TextMatcher.Prefix.LAST_TOKEN : TextMatcher.Prefix.OFF)
					.withRelax(TextMatcher.Relax.OFF)
			)
		);
	}

	/**
	 * Get the search with the given words left out, or {@code null} when they
	 * cannot be.
	 */
	private ImmutableList<Query> without(SetIterable<String> dropped) {
		var text = textWithout(dropped);
		if(text == null) {
			return null;
		}

		return replace(clauses, withText(matcher.withText(text)));
	}

	/**
	 * Get the search with the given words left out and asked for as a boost
	 * instead, which is what the search ends up running.
	 *
	 * A word that was let go still says something about what was wanted, so a
	 * document holding it is ranked above one that does not. It is no longer a
	 * condition, which is the whole of the difference.
	 */
	private ImmutableList<Query> relaxed(SetIterable<String> dropped) {
		var narrowed = without(dropped);
		if(narrowed == null) {
			return null;
		}

		return narrowed.newWith(
			BoostQuery.of(
				DROPPED_WEIGHT,
				withText(
					matcher.withText(words.select(dropped::contains).makeString(" "))
						.withMatch(TextMatcher.Match.ANY)
						.withPrefix(TextMatcher.Prefix.OFF)
						.withRelax(TextMatcher.Relax.OFF)
				)
			)
		);
	}

	/**
	 * Get the text clause of this search carrying the given matcher instead.
	 */
	private Query withText(TextMatcher replacement) {
		return switch(clause) {
			case TextQuery q -> q.withMatcher(replacement);
			case FieldQuery q -> Query.field(q.field(), replacement);
			default -> throw new IllegalStateException(
				"Relaxing " + clause.type() + " is not something this clause can do"
			);
		};
	}

	/**
	 * Rebuild the clauses of the search with its text clause replaced, or left
	 * out when there is nothing to replace it with. Only the branch the clause
	 * sits in is rebuilt, so everything else stays the instance it was.
	 */
	private ImmutableList<Query> replace(ListIterable<Query> clauses, Query replacement) {
		var result = Lists.mutable.<Query>empty();

		for(var candidate : clauses) {
			if(candidate == clause) {
				if(replacement != null) {
					result.add(replacement);
				}
			} else if(candidate instanceof AndQuery q) {
				result.add(AndQuery.of(replace(q.clauses(), replacement)));
			} else {
				result.add(candidate);
			}
		}

		return result.toImmutable();
	}

	/**
	 * Run the search again with words let go of, until something is found.
	 *
	 * @param whole
	 *   counts what the whole search matches, filters and all
	 * @param alone
	 *   counts what clauses match on their own, without the filters of the
	 *   search
	 * @return
	 *   what to search with instead and what was let go, or {@code null} when
	 *   nothing could be given up that found anything - a search nothing helps
	 *   is answered with the empty page it asked for
	 * @throws IOException
	 */
	Outcome run(Counting whole, Counting alone) throws IOException {
		/*
		 * Nothing matches even with the text gone, so the filters or the other
		 * clauses are what emptied the page and letting words go cannot rescue
		 * it. One count here saves a pass per word.
		 */
		if(whole.count(withoutText()) == 0) {
			return null;
		}

		var held = new long[words.size()];
		for(var i = 0; i < words.size(); i++) {
			held[i] = alone.count(probe(words.get(i)));
		}

		var dropped = Sets.mutable.<String>empty();

		/*
		 * The words nothing holds go together and go first. Each of them
		 * emptied the page on its own, so none of them can be the one worth
		 * keeping, and dropping them all costs a single pass.
		 */
		for(var i = 0; i < words.size(); i++) {
			if(held[i] == 0) {
				dropped.add(words.get(i));
			}
		}

		if(!dropped.isEmpty()) {
			var outcome = attempt(whole, dropped, held);
			if(outcome != null) {
				return outcome;
			}
		}

		if(matcher.relax() != TextMatcher.Relax.WORDS) {
			return null;
		}

		/*
		 * Then one word at a time, the most common first: the more documents
		 * hold a word the less it said about which of them was wanted, so it is
		 * the one whose loss costs the least. Words typed earlier go first
		 * among equals, which is only there to make the same search let go of
		 * the same words every time it is run.
		 */
		var order = Lists.mutable.ofAll(words)
			.reject(dropped::contains)
			.sortThisByLong(word -> -held[words.indexOf(word)]);

		for(var word : order) {
			dropped.add(word);

			var outcome = attempt(whole, dropped, held);
			if(outcome != null) {
				return outcome;
			}

			if(textWithout(dropped) == null) {
				// Nothing left to let go of without asking for nothing at all
				break;
			}
		}

		return null;
	}

	/**
	 * Try the search with the given words let go of, answering what to run when
	 * it finds something and {@code null} when it does not.
	 */
	private Outcome attempt(
		Counting whole,
		SetIterable<String> dropped,
		long[] held
	) throws IOException {
		var narrowed = without(dropped);
		if(narrowed == null || whole.count(narrowed) == 0) {
			return null;
		}

		var report = words.select(dropped::contains)
			.collect(word -> new SearchResult.Relaxed.Dropped(
				word,
				held[words.indexOf(word)] == 0
					? SearchResult.Relaxed.Reason.UNMATCHED
					: SearchResult.Relaxed.Reason.COMMON
			));

		return new Outcome(
			relaxed(dropped),
			new SearchResult.Relaxed(report, textWithout(dropped))
		);
	}
}
