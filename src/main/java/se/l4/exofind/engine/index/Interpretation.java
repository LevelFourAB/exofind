package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.Objects;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.set.ImmutableSet;
import org.eclipse.collections.api.set.MutableSet;

import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.index.locales.LocaleSupport;
import se.l4.exofind.engine.index.locales.Locales;
import se.l4.exofind.engine.index.locales.Units;
import se.l4.exofind.engine.index.schema.Field;
import se.l4.exofind.engine.index.schema.IndexSchema;
import se.l4.exofind.engine.index.types.NumberFieldType;
import se.l4.exofind.engine.query.AndQuery;
import se.l4.exofind.engine.query.BoostQuery;
import se.l4.exofind.engine.query.FuseQuery;
import se.l4.exofind.engine.query.NestedQuery;
import se.l4.exofind.engine.query.NotQuery;
import se.l4.exofind.engine.query.OrQuery;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.TextQuery;
import se.l4.exofind.engine.query.matchers.AnyMatcher;
import se.l4.exofind.engine.query.matchers.EqualsMatcher;
import se.l4.exofind.engine.query.matchers.Matcher;
import se.l4.exofind.engine.query.matchers.RangeMatcher;
import se.l4.exofind.engine.query.matchers.TextMatcher;
import se.l4.exofind.engine.query.matchers.UserText;

/**
 * Reading the text of a search box as the filters a person meant by it.
 *
 * {@code red shoes under 100 kr} is five words, and a search that looks for
 * all five in the text of every document finds the ones that happen to mention
 * a price and misses the ones that are cheap. The shopper meant a price below
 * a hundred, the colour red and the word {@code shoes}. This turns the number
 * and the words around it into a filter on the field whose unit they name,
 * turns a word that is a value of a field into a filter on that field, and
 * leaves the rest as text.
 *
 * <p>Three rules keep this from answering a question nobody asked:
 *
 * <ul>
 * <li>Only what the index declares can be read is read: a number next to a
 * unit some number field declares, or next to a comparative word of the
 * search locale when exactly one field holds a currency; and a value of a
 * field the search settings opted in, see {@link ValueDictionaries}. A number
 * on its own stays a word, so {@code size 44} still asks for text, and so
 * does a word no opted-in field holds as a value.
 * <li>A reading never commits. The words are still searched as text, so the
 * filter and the words are two ways of matching and either is enough - a
 * product named {@code Air Max 100} is still found by its name, and so is a
 * book about {@code black friday} when {@code black} is a colour. The filter
 * side is boosted, so what the reading found ranks first.
 * <li>What was read is reported, so a search box can show it and let a
 * person take it away. A wrong reading is never silent.
 * </ul>
 *
 * <p>Numbers are read first, and the values of fields among the words that
 * are left; a word that is part of a number is never part of a value. Both
 * are read greedily from the left, the longest reading at each word winning,
 * see {@link QuantityReader} and {@link ValueReader}.
 *
 * <p>A search may name the fields a reading is allowed to be a filter on,
 * see {@link TextQuery.Target}. That is for an index where the unit alone
 * does not say which field was meant - a product priced on many lists holds
 * the same currency on every one, and only the caller knows which list the
 * person is on. The named targets stand in for the fields of the index: the
 * unit of a typed number picks among them, and a number without a unit goes
 * to the ones holding a currency when they all hold the same one. A target
 * may carry the clauses that pin it to one value of a list, and the targets
 * read instead where a document holds nothing on it.
 *
 * <p>A text clause in {@link TextMatcher.Match#USER} mode is read wherever
 * it sits. One clause replaces it: the text that is left, and for each
 * reading either its filter or its words as text, all of which have to hold.
 * That clause means the same thing in every position, so a text clause
 * inside an {@code or}, a {@code not}, a {@code boost}, a {@code nested} or a
 * ranking of a {@code fuse} is read the same way as one at the top. A
 * catalogue of products with variants puts one typed text in two clauses,
 * one over the product and one inside a {@code nested} clause over its
 * variants. Both are read, and each gets the filters its position can hold.
 * Text clauses holding different texts were assembled by a caller, and none
 * is read: there is no single search box to report on. Quoted phrases and
 * exclusions are never read; they were typed on purpose.
 *
 * <p>Inside a {@code nested} clause every clause runs against one value of
 * the list. A reading there is a filter on the same value the text matched,
 * and only a field of that path can be one. A declared field on another path
 * is left out of that position. A named target on another path is refused,
 * as a {@code field} clause on it would be.
 */
final class Interpretation {
	private static final ErrorType TARGET_WITHOUT_UNIT = ErrorType
		.withCode("index:query:interpret:no_unit")
		.withArguments("name")
		.withMessage(
			"Field `{{name}}` declares no unit, so a number in the text can not be read as a filter on it"
		);

	private static final ErrorType FALLBACK_UNIT_DIFFERS = ErrorType
		.withCode("index:query:interpret:fallback_unit")
		.withArguments("name", "unit", "field", "expected")
		.withMessage(
			"Fallback `{{name}}` is in `{{unit}}`, but stands in for `{{field}}` which is in `{{expected}}`"
		);

	/**
	 * How much satisfying the filter side of a reading counts, so that what
	 * the reading found ranks above a document holding the words as text.
	 */
	private static final float READING_WEIGHT = 1f;

	/**
	 * What reading a search arrived at.
	 *
	 * @param query
	 *   the clauses to search with instead
	 * @param interpreted
	 *   what was read, to be answered alongside the results
	 */
	record Outcome(
		ImmutableList<Query> query,
		SearchResult.Interpreted interpreted
	) {
	}

	/**
	 * A number field a reading can be a filter on.
	 *
	 * @param name
	 *   the field, named as the definition names it
	 * @param nestedPath
	 *   the object field the values sit inside, when a filter on the field
	 *   has to run against one value at a time, or {@code null}
	 * @param type
	 *   the type of the field
	 * @param unit
	 *   the unit the field declares, as the definition spells it
	 * @param spellings
	 *   every folded spelling of its unit in the search locale
	 * @param currency
	 *   if the unit is a currency, which a number without a unit is read as
	 */
	private record UnitField(
		String name,
		String nestedPath,
		NumberFieldType type,
		String unit,
		ImmutableSet<String> spellings,
		boolean currency
	) {
	}

	/**
	 * One place a reading is a filter on: a field, and the clauses that have
	 * to hold where the number is read.
	 *
	 * @param field
	 *   the field
	 * @param when
	 *   the clauses that have to hold in the same value as the number, or for
	 *   the document when the field is not inside a list. Empty for none
	 */
	private record Step(
		UnitField field,
		ImmutableList<Query> when
	) {
		/**
		 * Get the clause that holds where the given matcher is satisfied on
		 * this step, together with whatever else has to hold there. For a
		 * field inside a list that is inside one value of the list: wrapped
		 * in a {@code nested} clause where the reading is a filter on the
		 * document, and as it is where the reading already sits inside a
		 * {@code nested} clause for the path.
		 *
		 * @param matcher
		 * @param insidePath
		 *   the path of the {@code nested} clause the reading sits inside, or
		 *   {@code null} for a reading on the document
		 */
		Query clause(Matcher matcher, String insidePath) {
			Query filter = Query.field(field.name(), matcher);

			if(field.nestedPath() != null && insidePath == null) {
				return NestedQuery.of(field.nestedPath(), when.newWith(filter));
			}

			return when.isEmpty() ? filter : AndQuery.of(when.newWith(filter));
		}

		/**
		 * Whether this step can be a filter in the given position.
		 *
		 * On the document, every step can. Inside a {@code nested} clause
		 * only a field of that path can, as the clauses there run against one
		 * value at a time and see nothing outside it.
		 */
		boolean heldInside(String path) {
			return path == null || path.equals(field.nestedPath());
		}
	}

	/**
	 * The steps a reading is tried on, in order: the first, and the ones read
	 * instead where a document holds nothing on the steps before them.
	 *
	 * @param steps
	 *   the steps, never empty
	 * @param target
	 *   the target the search named that the chain was built from, or
	 *   {@code null} when the chain stands for a field the index declared a
	 *   unit on
	 */
	private record Chain(
		ImmutableList<Step> steps,
		TextQuery.Target target
	) {
		UnitField head() {
			return steps.get(0).field();
		}

		/**
		 * Whether every step of this chain can be a filter in the given
		 * position, see {@link Step#heldInside}.
		 */
		boolean heldInside(String path) {
			return steps.allSatisfy(step -> step.heldInside(path));
		}
	}

	/**
	 * One position a text clause sits in.
	 *
	 * @param clause
	 *   the clause
	 * @param nestedPath
	 *   the path of the {@code nested} clause it sits inside, or {@code null}
	 *   when it runs against the document
	 */
	private record Place(
		TextQuery clause,
		String nestedPath
	) {
	}

	private Interpretation() {
	}

	/**
	 * Read the text of a search, or answer {@code null} when there is nothing
	 * to read - no text a person typed, no field with a unit and no field
	 * whose values are read, or a text holding no number next to anything
	 * that says it is one and no value of a field.
	 *
	 * @param schema
	 *   the schema of the index searched
	 * @param locale
	 *   the locale of the search, or {@code null} for the default
	 * @param clauses
	 *   the clauses of the search, without its filters
	 * @param values
	 *   what reads the words as values of fields, or {@code null} when no
	 *   field is read that way
	 * @return
	 * @throws IndexException
	 *   if the search names a target that can not be read on - a field that
	 *   does not exist, one without a unit, a fallback in another unit than
	 *   the field it stands in for, or a field outside the path of the
	 *   {@code nested} clause the text sits inside
	 * @throws IOException
	 *   if the values of a field cannot be read
	 */
	static Outcome read(
		IndexSchema schema,
		String locale,
		ImmutableList<Query> clauses,
		ValueReader values
	) throws IOException {
		var places = Lists.mutable.<Place>empty();
		collect(clauses, null, places);
		if(places.isEmpty()) {
			return null;
		}

		/*
		 * One typed text, however many places it was put. Different texts
		 * were put together by a caller, and there is no one search box to
		 * read or to report on.
		 */
		var clause = places.get(0).clause();
		var matcher = clause.matcher();
		for(var place : places) {
			if(!sameText(place.clause(), clause)) {
				return null;
			}
		}

		/*
		 * The targets a search named are resolved before the text is looked
		 * at, so that naming a field that can not be read on is refused
		 * whether or not the text held a number this time.
		 */
		var support = Locales.get(locale).orElse(Locales.getDefault());
		var known = clause.targets().isEmpty()
			? declared(schema, support)
			: named(schema, support, clause.targets(), places);

		/*
		 * A reading lands on the chains some position can hold. The unit of
		 * a declared field no position can hold is still a unit and never a
		 * word: a number typed with it is read, finds no chain, and stays
		 * text. Dropping the spelling would leave the number bare, and a
		 * bare number is read as a price.
		 */
		var chains = known.select(chain -> heldSomewhere(chain, places));
		var readsValues = values != null && !values.isEmpty();
		if(chains.isEmpty() && !readsValues) {
			return null;
		}

		var typed = UserText.parse(matcher.text());
		if(typed.isEmpty()) {
			return null;
		}

		var spellings = Sets.mutable.<String>empty();
		for(var chain : known) {
			for(var step : chain.steps()) {
				spellings.addAll(step.field().spellings().castToSet());
			}
		}

		var bareNumberIsCurrency = readsBareNumbers(chains, !clause.targets().isEmpty());

		var reader = new QuantityReader(support, spellings);
		var parts = typed.parts();

		var read = Lists.mutable.<Reading>empty();
		var covered = new boolean[parts.size()];

		/*
		 * Loose words typed next to each other are read together; a quoted
		 * phrase or an exclusion between them ends the run, as it was typed on
		 * purpose and is never part of a number.
		 */
		var run = 0;
		while(run < parts.size()) {
			if(!isLoose(parts.get(run))) {
				run++;
				continue;
			}

			var runEnd = run;
			while(runEnd < parts.size() && isLoose(parts.get(runEnd))) {
				runEnd++;
			}

			var words = Lists.mutable.<String>empty();
			for(var i = run; i < runEnd; i++) {
				words.add(parts.get(i).text());
			}

			if(chains.notEmpty()) {
				for(var quantity : reader.read(words)) {
					var reading = reading(chains, bareNumberIsCurrency, quantity, words, run);
					if(reading != null) {
						read.add(reading);
						for(var i = reading.start(); i < reading.end(); i++) {
							covered[i] = true;
						}
					}
				}
			}

			run = runEnd;
		}

		if(readsValues) {
			readValues(values, parts, covered, places, read);
		}

		if(read.isEmpty()) {
			return null;
		}

		read.sortThisByInt(Reading::start);

		var remaining = Lists.mutable.<UserText.Part>empty();
		for(var i = 0; i < parts.size(); i++) {
			if(!covered[i]) {
				remaining.add(parts.get(i));
			}
		}
		var left = new UserText(remaining.toImmutable());

		var filters = Lists.mutable.<SearchResult.Interpreted.Filter>empty();
		for(var reading : read) {
			filters.addAll(reading.filters().castToList());
		}

		return new Outcome(
			replace(clauses, null, place -> replacement(place, left, read)),
			new SearchResult.Interpreted(filters.toImmutable(), left.text())
		);
	}

	/**
	 * Get what stands in for a text clause in one position: the text that is
	 * left, and every reading, all of which have to hold. One clause, so it
	 * means the same thing whatever list it is put back into.
	 */
	private static Query replacement(Place place, UserText left, ListIterable<Reading> read) {
		var parts = Lists.mutable.<Query>empty();
		if(!left.isEmpty()) {
			var clause = place.clause();
			parts.add(clause.withMatcher(clause.matcher().withText(left.text())));
		}

		for(var reading : read) {
			parts.add(reading.clause(place));
		}

		return parts.size() == 1 ? parts.get(0) : AndQuery.of(parts);
	}

	private static boolean isLoose(UserText.Part part) {
		return part.kind() == UserText.Kind.WORD && !part.exclude();
	}

	/**
	 * Read the values of fields among the loose words no number was read
	 * from. Words next to each other are read together; a quoted phrase, an
	 * exclusion or a number between them ends the run.
	 *
	 * A value is read only on a field some position can be a filter on. A
	 * span that is a value of no such field stays text.
	 */
	private static void readValues(
		ValueReader values,
		ListIterable<UserText.Part> parts,
		boolean[] covered,
		ListIterable<Place> places,
		MutableList<Reading> into
	) throws IOException {
		var run = 0;
		while(run < parts.size()) {
			if(!isLoose(parts.get(run)) || covered[run]) {
				run++;
				continue;
			}

			var runEnd = run;
			while(runEnd < parts.size() && isLoose(parts.get(runEnd)) && !covered[runEnd]) {
				runEnd++;
			}

			var words = Lists.mutable.<String>empty();
			for(var i = run; i < runEnd; i++) {
				words.add(parts.get(i).text());
			}

			var offset = run;
			for(var match : values.read(words, field -> heldSomewhere(field, places))) {
				var filters = Lists.mutable.<SearchResult.Interpreted.Filter>empty();
				var alternatives = Lists.mutable.<Alternative>empty();
				var typed = words.subList(match.start(), match.end()).toImmutable();

				for(var hit : match.hits()) {
					filters.add(new SearchResult.Interpreted.Filter(
						SearchResult.Interpreted.Kind.VALUE,
						hit.field().name(),
						new EqualsMatcher(hit.value()),
						typed
					));
					alternatives.add(new ValueOn(hit.field(), hit.value()));
				}

				into.add(new Reading(
					offset + match.start(),
					offset + match.end(),
					typed,
					filters.toImmutable(),
					alternatives.toImmutable()
				));

				for(var i = offset + match.start(); i < offset + match.end(); i++) {
					covered[i] = true;
				}
			}

			run = runEnd;
		}
	}

	/**
	 * Whether a filter on a field of a value dictionary can hold in at least
	 * one of the given positions, see {@link ValueOn#heldInside}.
	 */
	private static boolean heldSomewhere(ValueDictionaries.Entry field, ListIterable<Place> places) {
		return places.anySatisfy(
			place -> place.nestedPath() == null || place.nestedPath().equals(field.nestedPath())
		);
	}

	/**
	 * Whether a clause is text a person typed that may be read.
	 */
	private static boolean isReadable(Query clause) {
		return clause instanceof TextQuery q
			&& q.matcher().match() == TextMatcher.Match.USER
			&& q.matcher().interpret() == TextMatcher.Interpret.AUTO;
	}

	/**
	 * Whether two text clauses hold the same typed text and would be read on
	 * the same targets, so that one reading serves both.
	 */
	private static boolean sameText(TextQuery a, TextQuery b) {
		return Objects.equals(a.matcher().text(), b.matcher().text())
			&& a.targets().equals(b.targets());
	}

	/**
	 * Whether a chain can be a filter in at least one of the given positions.
	 */
	private static boolean heldSomewhere(Chain chain, ListIterable<Place> places) {
		return places.anySatisfy(place -> chain.heldInside(place.nestedPath()));
	}

	/**
	 * Gather every text clause a search can read, wherever it sits, with the
	 * position it sits in.
	 *
	 * The filters of a search are not walked, and neither is the filter of a
	 * {@code knn} or a {@code fuse}: a filter holds nothing a person typed.
	 */
	private static void collect(
		ListIterable<Query> clauses,
		String nestedPath,
		MutableList<Place> found
	) {
		for(var clause : clauses) {
			if(isReadable(clause)) {
				found.add(new Place((TextQuery) clause, nestedPath));
				continue;
			}

			switch(clause) {
				case AndQuery q -> collect(q.clauses(), nestedPath, found);
				case OrQuery q -> collect(q.clauses(), nestedPath, found);
				case NotQuery q -> collect(q.clauses(), nestedPath, found);
				case BoostQuery q -> collect(q.clauses(), nestedPath, found);
				case NestedQuery q -> collect(q.clauses(), q.path(), found);
				case FuseQuery q -> {
					for(var ranking : q.rankings()) {
						collect(ranking.clauses(), nestedPath, found);
					}
				}
				default -> {
					// Holds no text clause of its own
				}
			}
		}
	}

	/**
	 * Decide whether a number without a unit is read, and as what.
	 *
	 * A bound without a unit is a price, so it goes to the fields holding a
	 * currency - if that says one thing. Among the fields the index declares,
	 * that is exactly one field with a currency; with several, nothing says
	 * which was meant. Among the targets a search named, the caller chose
	 * every one of them, so it is every target with a currency as long as
	 * they hold the same currency - a number can not mean kronor on one
	 * product and euro on the next.
	 */
	private static boolean readsBareNumbers(ListIterable<Chain> chains, boolean named) {
		var currencies = Sets.mutable.<String>empty();
		var count = 0;
		for(var chain : chains) {
			if(chain.head().currency()) {
				currencies.add(chain.head().unit());
				count++;
			}
		}

		return named ? currencies.size() == 1 : count == 1;
	}

	/**
	 * Find the number fields of the index that declare a unit, each a chain
	 * of its own with nothing to pin it and nothing to fall back to. A field
	 * named by a pattern stands for names not yet known, so it is left out.
	 */
	private static ImmutableList<Chain> declared(IndexSchema schema, LocaleSupport locale) {
		var fields = Lists.mutable.<UnitField>empty();

		for(var field : schema.getFields()) {
			if(field.isObject()) {
				var nested = field.isNestedObject() ? field.getName() : null;
				for(var inner : schema.getNestedFields(field.getName())) {
					collectDeclared(inner, nested, locale, fields);
				}
			} else {
				collectDeclared(field, null, locale, fields);
			}
		}

		return fields.collect(field -> new Chain(
			Lists.immutable.of(new Step(field, Lists.immutable.empty())),
			null
		)).toImmutable();
	}

	private static void collectDeclared(
		Field field,
		String nestedPath,
		LocaleSupport locale,
		MutableList<UnitField> into
	) {
		if(field.getName().contains("*")) {
			return;
		}

		var unitField = unitField(field, field.getName(), nestedPath, locale);
		if(unitField != null) {
			into.add(unitField);
		}
	}

	/**
	 * Resolve the targets a search named into the chains they describe,
	 * refusing any that can not be read on - or that can not be read in one
	 * of the positions the text sits in.
	 */
	private static ImmutableList<Chain> named(
		IndexSchema schema,
		LocaleSupport locale,
		ListIterable<TextQuery.Target> targets,
		ListIterable<Place> places
	) {
		var chains = Lists.mutable.<Chain>empty();

		for(var target : targets) {
			var steps = Lists.mutable.<Step>empty();
			var head = resolve(schema, locale, target.field());
			steps.add(new Step(head, target.when()));
			collectFallbacks(schema, locale, head, target.fallback(), steps);

			var chain = new Chain(steps.toImmutable(), target);
			for(var place : places) {
				requireHeld(chain, place);
			}

			chains.add(chain);
		}

		return chains.toImmutable();
	}

	/**
	 * Refuse a named chain that can not be a filter in the given position,
	 * the way the compiler refuses a {@code field} clause on a field outside
	 * the path of the {@code nested} clause it sits inside.
	 */
	private static void requireHeld(Chain chain, Place place) {
		for(var step : chain.steps()) {
			if(!step.heldInside(place.nestedPath())) {
				throw new IndexException(
					QueryCompiler.FIELD_NOT_IN_PATH,
					"name", step.field().name(),
					"path", place.nestedPath()
				);
			}
		}
	}

	private static void collectFallbacks(
		IndexSchema schema,
		LocaleSupport locale,
		UnitField head,
		ListIterable<TextQuery.Target> fallbacks,
		MutableList<Step> into
	) {
		for(var fallback : fallbacks) {
			var field = resolve(schema, locale, fallback.field());
			if(!field.unit().equals(head.unit())) {
				throw new IndexException(
					FALLBACK_UNIT_DIFFERS,
					"name", fallback.field(),
					"unit", field.unit(),
					"field", head.name(),
					"expected", head.unit()
				);
			}

			into.add(new Step(field, fallback.when()));
			collectFallbacks(schema, locale, head, fallback.fallback(), into);
		}
	}

	/**
	 * Find the field a target names, wherever it sits: inside a nested list,
	 * inside a flattened object or at the root. A name a pattern accepts
	 * resolves to the pattern's field and is read under the name given, the
	 * way a {@code field} clause on it would be.
	 *
	 * @throws IndexFieldNotFoundException
	 *   if nothing in the index has the name
	 * @throws IndexException
	 *   if the field declares no unit
	 */
	private static UnitField resolve(IndexSchema schema, LocaleSupport locale, String name) {
		var nested = schema.getNestedField(name);
		Field field;
		String nestedPath;
		if(nested.isPresent()) {
			field = nested.get().field();
			nestedPath = nested.get().path();
		} else {
			field = schema.getField(name).orElseThrow(() -> new IndexFieldNotFoundException(name));
			nestedPath = null;
		}

		var unitField = unitField(field, name, nestedPath, locale);
		if(unitField == null) {
			throw new IndexException(TARGET_WITHOUT_UNIT, "name", name);
		}

		return unitField;
	}

	/**
	 * Describe a field as something a number can be read on, or {@code null}
	 * when it is not a number field or declares no unit.
	 */
	private static UnitField unitField(
		Field field,
		String name,
		String nestedPath,
		LocaleSupport locale
	) {
		if(!(field.getType() instanceof NumberFieldType type)) {
			return null;
		}

		var unit = type.getUnit(field.getDef().getType());
		if(unit == null) {
			return null;
		}

		return new UnitField(
			name,
			nestedPath,
			type,
			unit,
			Units.spellingsOf(unit, locale),
			Units.isCurrency(unit)
		);
	}

	/**
	 * One way a reading is a filter: on a chain of number fields, or on a
	 * value of a field.
	 */
	private interface Alternative {
		/**
		 * Whether this can be a filter in the given position, see
		 * {@link Step#heldInside}.
		 *
		 * @param path
		 *   the path of the {@code nested} clause the reading sits inside, or
		 *   {@code null} for a reading on the document
		 */
		boolean heldInside(String path);

		/**
		 * Get the filter in the given position.
		 *
		 * @param insidePath
		 *   the path of the {@code nested} clause the reading sits inside, or
		 *   {@code null} for a reading on the document
		 */
		Query clause(String insidePath);
	}

	/**
	 * A quantity as a filter on one chain.
	 */
	private record QuantityOn(Chain chain, QuantityReader.Quantity quantity) implements Alternative {
		@Override
		public boolean heldInside(String path) {
			return chain.heldInside(path);
		}

		@Override
		public Query clause(String insidePath) {
			return Interpretation.clause(chain, quantity, insidePath);
		}
	}

	/**
	 * Words as one value of one field. For a field inside a list the filter
	 * runs against one value of the list: wrapped in a {@code nested} clause
	 * where the reading is a filter on the document, and as it is where the
	 * reading already sits inside a {@code nested} clause for the path.
	 */
	private record ValueOn(ValueDictionaries.Entry field, String value) implements Alternative {
		@Override
		public boolean heldInside(String path) {
			return path == null || path.equals(field.nestedPath());
		}

		@Override
		public Query clause(String insidePath) {
			Query filter = Query.field(field.name(), new EqualsMatcher(value));
			if(field.nestedPath() != null && insidePath == null) {
				return NestedQuery.of(field.nestedPath(), Lists.immutable.of(filter));
			}

			return filter;
		}
	}

	/**
	 * Words read as filters, on whatever they can be a filter on.
	 *
	 * @param start
	 *   index of the first part it was read from
	 * @param end
	 *   index after the last part it was read from
	 * @param words
	 *   the words it was read from, as typed
	 * @param filters
	 *   the filters, one per alternative
	 * @param alternatives
	 *   the ways the words are a filter, aligned with {@code filters}
	 */
	private record Reading(
		int start,
		int end,
		ImmutableList<String> words,
		ImmutableList<SearchResult.Interpreted.Filter> filters,
		ImmutableList<Alternative> alternatives
	) {
		/**
		 * Get the clause this reading stands for in one position: any of the
		 * filters that position can hold, or the words it came from found as
		 * text. Where the position can hold none of the filters, the words
		 * alone.
		 */
		Query clause(Place place) {
			var text = place.clause();
			var alternatives = Lists.mutable.<Query>empty();
			for(var alternative : this.alternatives) {
				if(alternative.heldInside(place.nestedPath())) {
					alternatives.add(BoostQuery.of(
						READING_WEIGHT,
						alternative.clause(place.nestedPath())
					));
				}
			}

			/*
			 * The words as text, exactly as typed: a number is not a word
			 * still being typed, and a unit is not one with a mistake in it.
			 */
			alternatives.add(new TextQuery(
				text.matcher()
					.withText(words.makeString(" "))
					.withMatch(TextMatcher.Match.ALL)
					.withPrefix(TextMatcher.Prefix.OFF)
					.withTypos(TextMatcher.Typos.OFF)
					.withRelax(TextMatcher.Relax.OFF)
					.withInterpret(TextMatcher.Interpret.OFF),
				text.fields(),
				text.combine()
			));

			return alternatives.size() == 1 ? alternatives.get(0) : OrQuery.of(alternatives);
		}
	}

	/**
	 * Turn a quantity into filters on the chains it names, or {@code null}
	 * when no chain can hold it.
	 *
	 * A unit names the chains whose first field declares it. A number without
	 * a unit names the chains holding a currency, when
	 * {@link #readsBareNumbers} said that means one thing; otherwise nothing
	 * says which was meant and the number stays a word.
	 */
	private static Reading reading(
		ListIterable<Chain> chains,
		boolean bareNumberIsCurrency,
		QuantityReader.Quantity quantity,
		ListIterable<String> words,
		int offset
	) {
		var filters = Lists.mutable.<SearchResult.Interpreted.Filter>empty();
		var on = Lists.mutable.<Alternative>empty();
		var typed = Lists.mutable.<String>empty();
		for(var i = quantity.start(); i < quantity.end(); i++) {
			typed.add(words.get(i));
		}

		for(var chain : chains) {
			var head = chain.head();
			var named = quantity.unit() == null
				? head.currency() && bareNumberIsCurrency
				: head.spellings().contains(quantity.unit());
			if(!named) {
				continue;
			}

			var headMatcher = matcherFor(head.type(), quantity);
			if(headMatcher == null) {
				continue;
			}

			var target = chain.target();
			filters.add(new SearchResult.Interpreted.Filter(
				SearchResult.Interpreted.Kind.NUMBER,
				head.name(),
				headMatcher,
				typed.toImmutable(),
				target == null ? null : target.when(),
				target == null ? null : target.fallback()
			));

			on.add(new QuantityOn(chain, quantity));
		}

		if(filters.isEmpty()) {
			return null;
		}

		return new Reading(
			offset + quantity.start(),
			offset + quantity.end(),
			typed.toImmutable(),
			filters.toImmutable(),
			on.toImmutable()
		);
	}

	/**
	 * Get the clause a quantity is on a chain: the filter on its first step,
	 * or on each later step for a document holding nothing on the steps
	 * before it. A step whose type can not hold the quantity is skipped, and
	 * a document holding nothing on it falls through to the next.
	 *
	 * Inside a {@code nested} clause the same holds of one value: a step is
	 * read where that value holds nothing on the steps before it.
	 *
	 * @param chain
	 * @param quantity
	 * @param insidePath
	 *   the path of the {@code nested} clause the reading sits inside, or
	 *   {@code null} for a reading on the document
	 */
	private static Query clause(Chain chain, QuantityReader.Quantity quantity, String insidePath) {
		var alternatives = Lists.mutable.<Query>empty();
		var absent = Lists.mutable.<Query>empty();

		for(var step : chain.steps()) {
			var matcher = matcherFor(step.field().type(), quantity);
			if(matcher != null) {
				var filter = step.clause(matcher, insidePath);
				alternatives.add(
					absent.isEmpty() ? filter : AndQuery.of(absent.toImmutable().newWith(filter))
				);
			}

			absent.add(NotQuery.of(step.clause(new AnyMatcher(), insidePath)));
		}

		return alternatives.size() == 1 ? alternatives.get(0) : OrQuery.of(alternatives);
	}

	/**
	 * Get the matcher a quantity is on a field of the given type, or
	 * {@code null} when the type cannot hold it.
	 *
	 * A type holding whole numbers takes a bound with a fraction as the
	 * nearest whole number on the right side of it: below {@code 99.5} is
	 * below {@code 100}, and at least {@code 99.5} is at least {@code 100}.
	 * Exactly {@code 99.5} is no whole number at all.
	 */
	private static Matcher matcherFor(NumberFieldType type, QuantityReader.Quantity quantity) {
		var lower = quantity.lower();
		var upper = quantity.upper();

		if(type.holdsWholeNumbers()) {
			if(lower instanceof Double d) {
				lower = quantity.lowerInclusive() ? (long) Math.ceil(d) : (long) Math.floor(d);
			}
			if(upper instanceof Double d) {
				upper = quantity.upperInclusive() ? (long) Math.floor(d) : (long) Math.ceil(d);
			}
		}

		if(lower != null) {
			lower = type.read(lower);
			if(lower == null) {
				return null;
			}
		}

		if(upper != null) {
			upper = type.read(upper);
			if(upper == null) {
				return null;
			}
		}

		if(lower != null && upper != null) {
			var order = Double.compare(lower.doubleValue(), upper.doubleValue());
			if(order > 0) {
				return null;
			}

			if(order == 0 && quantity.lowerInclusive() && quantity.upperInclusive()) {
				return new EqualsMatcher(lower);
			}
		}

		return new RangeMatcher(
			lower,
			quantity.lowerInclusive(),
			upper,
			quantity.upperInclusive()
		);
	}

	/**
	 * What stands in for a text clause in a position.
	 */
	@FunctionalInterface
	private interface Replacing {
		Query at(Place place);
	}

	/**
	 * Rebuild the clauses of the search with every text clause that can be
	 * read replaced, in the same places {@link #collect} found them. Only the
	 * branches a clause sits in are rebuilt, so everything else stays the
	 * instance it was.
	 */
	private static ImmutableList<Query> replace(
		ImmutableList<Query> clauses,
		String nestedPath,
		Replacing replacing
	) {
		var result = Lists.mutable.<Query>empty();
		var changed = false;

		for(var candidate : clauses) {
			var replaced = replace(candidate, nestedPath, replacing);
			changed |= replaced != candidate;
			result.add(replaced);
		}

		return changed ? result.toImmutable() : clauses;
	}

	private static Query replace(Query candidate, String nestedPath, Replacing replacing) {
		if(isReadable(candidate)) {
			return replacing.at(new Place((TextQuery) candidate, nestedPath));
		}

		return switch(candidate) {
			case AndQuery q -> {
				var inner = replace(q.clauses(), nestedPath, replacing);
				yield inner == q.clauses() ? q : AndQuery.of(inner);
			}
			case OrQuery q -> {
				var inner = replace(q.clauses(), nestedPath, replacing);
				yield inner == q.clauses() ? q : OrQuery.of(inner);
			}
			case NotQuery q -> {
				var inner = replace(q.clauses(), nestedPath, replacing);
				yield inner == q.clauses() ? q : NotQuery.of(inner);
			}
			case BoostQuery q -> {
				var inner = replace(q.clauses(), nestedPath, replacing);
				yield inner == q.clauses() ? q : BoostQuery.of(q.weight(), inner);
			}
			case NestedQuery q -> {
				var inner = replace(q.clauses(), q.path(), replacing);
				yield inner == q.clauses() ? q : new NestedQuery(q.path(), inner, q.score());
			}
			case FuseQuery q -> {
				var rankings = Lists.mutable.<FuseQuery.Ranking>empty();
				var changed = false;
				for(var ranking : q.rankings()) {
					var inner = replace(ranking.clauses(), nestedPath, replacing);
					changed |= inner != ranking.clauses();
					rankings.add(
						inner == ranking.clauses()
							? ranking
							: new FuseQuery.Ranking(inner, ranking.weight())
					);
				}

				yield changed
					? new FuseQuery(rankings.toImmutable(), q.depth(), q.rankConstant(), q.filter())
					: q;
			}
			default -> candidate;
		};
	}
}
