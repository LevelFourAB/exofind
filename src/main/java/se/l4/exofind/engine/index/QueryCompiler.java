package se.l4.exofind.engine.index;

import java.util.Locale;

import org.apache.lucene.queries.function.FunctionScoreQuery;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DisjunctionMaxQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.join.BitSetProducer;
import org.apache.lucene.search.join.QueryBitSetProducer;
import org.apache.lucene.search.join.ScoreMode;
import org.apache.lucene.search.join.ToChildBlockJoinQuery;
import org.apache.lucene.search.join.ToParentBlockJoinQuery;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.ImmutableMap;
import org.eclipse.collections.api.set.ImmutableSet;
import org.eclipse.collections.api.tuple.Pair;

import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.index.locales.Locales;
import se.l4.exofind.engine.index.schema.Field;
import se.l4.exofind.engine.index.schema.IndexSchema;
import se.l4.exofind.engine.index.schema.RankingConfig;
import se.l4.exofind.engine.query.AndQuery;
import se.l4.exofind.engine.query.BoostQuery;
import se.l4.exofind.engine.query.DecaySignal;
import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.FieldQuery;
import se.l4.exofind.engine.query.FieldSort;
import se.l4.exofind.engine.query.GeoDistanceSort;
import se.l4.exofind.engine.query.KnnQuery;
import se.l4.exofind.engine.query.NestedQuery;
import se.l4.exofind.engine.query.NotQuery;
import se.l4.exofind.engine.query.OrQuery;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.RankingSignal;
import se.l4.exofind.engine.query.SaturationSignal;
import se.l4.exofind.engine.query.ScoreSort;
import se.l4.exofind.engine.query.SortBy;
import se.l4.exofind.engine.query.TextQuery;
import se.l4.exofind.engine.query.matchers.TextMatcher;
import se.l4.exofind.engine.query.matchers.UserText;

/**
 * Turns the clauses of a search into something Lucene can run.
 *
 * The clauses know nothing about Lucene and nothing about what a value is
 * written under - each one is handed to the type of the field it names, which
 * is what decides whether it means anything there. What this class decides is
 * how the pieces are put together, and that comes down to how each clause
 * takes part:
 *
 * <ul>
 * <li>a clause that does not score is a filter, so ticking one can never
 * reshuffle what is left
 * <li>a clause that scores has to be satisfied and counts towards the ranking
 * <li>a boost is optional, lifting what it matches and leaving the rest alone
 * </ul>
 *
 * An instance walks one search at a time and is not safe to share between
 * threads, as it carries the encounter that field types are handed.
 */
public class QueryCompiler {
	private static final ErrorType NO_SEARCHABLE_FIELDS = ErrorType
		.withCode("index:query:no_searchable_fields")
		.withMessage(
			"Searching for text needs at least one field that is defined for matching"
		);

	private static final ErrorType NESTED_FIELD_OUTSIDE = ErrorType
		.withCode("index:query:nested:outside")
		.withArguments("name", "path")
		.withMessage(
			"Field `{{name}}` is inside the objects of `{{path}}` and can only be used inside a `nested` clause for that path"
		);

	private static final ErrorType FIELD_NOT_IN_PATH = ErrorType
		.withCode("index:query:nested:not_in_path")
		.withArguments("name", "path")
		.withMessage(
			"Field `{{name}}` is not inside the objects of `{{path}}`"
		);

	private static final ErrorType NESTED_UNSUPPORTED_CLAUSE = ErrorType
		.withCode("index:query:nested:unsupported_clause")
		.withArguments("type")
		.withMessage(
			"A `nested` clause holds what can run against a single value, which a `{{type}}` clause can not"
		);

	private static final ErrorType NESTED_SORT_UNSUPPORTED = ErrorType
		.withCode("index:query:nested:sort_unsupported")
		.withArguments("name")
		.withMessage(
			"Field `{{name}}` is inside an object and can be ordered by its value, but not in this way"
		);

	private static final ErrorType NESTED_ON_FLATTENED = ErrorType
		.withCode("index:query:nested:flattened")
		.withArguments("path")
		.withMessage(
			"The values of `{{path}}` are flattened, so their fields are matched directly "
			+ "and independently of which value they sit in; asking that conditions hold "
			+ "inside the same value takes the `nested` mode on the field"
		);

	/**
	 * The kinds of ordering that can read a value out of the objects of one
	 * document and order the document by it. Anything else - a distance from an
	 * origin, say - is refused rather than answered from the wrong document.
	 */
	private static final ImmutableSet<SortField.Type> NESTED_SORT_TYPES = Sets.immutable.of(
		SortField.Type.STRING,
		SortField.Type.INT,
		SortField.Type.LONG,
		SortField.Type.FLOAT,
		SortField.Type.DOUBLE
	);

	/**
	 * How much the fields a document matched besides its best one count when
	 * text is searched across several. Enough to separate a document that
	 * matched twice from one that matched once, not enough to reorder
	 * documents that matched their best field differently.
	 */
	private static final float TIE_BREAKER = 0.1f;

	private final IndexSchema schema;
	private final IndexEncounterImpl encounter;

	/**
	 * The locale the search asked for, or {@code null} when it asked for
	 * none. Only locale specific fields follow it - see {@link #select}.
	 */
	private final String locale;

	/**
	 * Finds the documents of the index among the values of object fields, for
	 * joining what a {@code nested} clause matched back to them.
	 */
	private final BitSetProducer nestedParents;

	/**
	 * The object field whose values names currently resolve inside, or
	 * {@code null} at the root of the search - see {@link #field}.
	 */
	private String nestedPath;

	public QueryCompiler(IndexSchema schema, String locale, BitSetProducer nestedParents) {
		this.schema = schema;
		this.locale = locale;
		this.nestedParents = nestedParents;

		this.encounter = new IndexEncounterImpl(schema.getResources());
	}

	/**
	 * Point the encounter at a field, in the locale the search reads it in.
	 *
	 * A locale specific field is searched in the variant the search's tag
	 * resolves to - matched as closely as the field's declared locales tell
	 * apart, so {@code nb-NO} reads a field holding Norwegian as {@code no} -
	 * and in its default locale when the field holds no variant the tag names,
	 * as a search across several fields should not fail because one of them
	 * never held the locale. Every other field has one variant, analyzed by
	 * the engine default, and the search locale changes nothing about it.
	 *
	 * Which documents that variant holds is decided when they are indexed: an
	 * index that falls back between locales has filled it for the documents
	 * that were never translated, so reading one variant is enough.
	 */
	private void select(String name, Field field) {
		if(field.isLocaleSpecific()) {
			var tag = locale == null
				? field.getDefaultLocale()
				: field.resolveLocale(locale).orElseGet(field::getDefaultLocale);

			// Declared locales are validated with the definition
			encounter.updateLocale(Locales.get(tag).orElseThrow());
		} else {
			encounter.updateLocale(Locales.getDefault());
		}

		encounter.updateValue(name, field.getDef());
	}

	/**
	 * Compile the clauses of a search, all of which have to be satisfied.
	 *
	 * @param clauses
	 * @return
	 */
	public org.apache.lucene.search.Query compile(ListIterable<Query> clauses) {
		return compileAll(clauses);
	}

	/**
	 * Whether everything the given clauses can match is a document of the
	 * index, so that a search of them needs nothing to keep the values of
	 * object fields out of its results.
	 *
	 * A clause naming a field of the index answers this on its own: the values
	 * of an object field are written as Lucene documents holding the fields
	 * inside the object and nothing else, so no value can satisfy a condition
	 * on a field of the index. A {@code nested} clause answers it too, having
	 * joined what it matched back to the documents holding it. What cannot
	 * answer it is anything that widens - a {@code not}, a boost, a text of
	 * nothing but exclusions - because what those match is decided by what
	 * they do not name.
	 *
	 * Said of clauses that all have to hold, so one of them answering for the
	 * whole is enough.
	 *
	 * @param clauses
	 * @return
	 *   {@code false} whenever it cannot be told, which is always safe - the
	 *   search then keeps the clause it would otherwise have done without
	 */
	public boolean matchesDocumentsOnly(ListIterable<Query> clauses) {
		return clauses.anySatisfy(this::documentsOnly);
	}

	/**
	 * Whether one clause answers {@link #matchesDocumentsOnly} on its own.
	 */
	private boolean documentsOnly(Query clause) {
		return switch(clause) {
			case FieldQuery q -> namesAField(q.field());
			case KnnQuery q -> namesAField(q.field());
			case TextQuery q -> textDocumentsOnly(q);

			// Joined back to the documents holding the values it matched
			case NestedQuery q -> true;

			case AndQuery q -> matchesDocumentsOnly(q.clauses());

			// Every way of matching has to answer, as any of them may be the one that did
			case OrQuery q -> q.clauses().notEmpty() && q.clauses().allSatisfy(this::documentsOnly);

			case NotQuery q -> false;
			case BoostQuery q -> false;
		};
	}

	/**
	 * Whether a text clause can only match documents of the index, which is
	 * decided by the fields it reads and by whether it asks for anything at
	 * all - text a person typed that holds nothing but exclusions runs against
	 * the whole index, values of object fields included.
	 */
	private boolean textDocumentsOnly(TextQuery clause) {
		if(clause.matcher().match() == TextMatcher.Match.USER
			&& !UserText.parse(clause.matcher().text())
				.parts()
				.anySatisfy(part -> !part.exclude()))
		{
			return false;
		}

		/*
		 * Naming no field reads the fields of the index, which are the ones a
		 * value of an object field does not have.
		 */
		return clause.fields().isEmpty()
			|| clause.fields().keysView().allSatisfy(this::namesAField);
	}

	/**
	 * Whether a name is one of the fields of the index rather than one inside
	 * an object field. A name that is neither is left to the compiling to
	 * refuse.
	 */
	private boolean namesAField(String name) {
		return nestedPath == null
			&& schema.getNestedField(name).isEmpty()
			&& schema.getField(name).filter(field -> !field.isObject()).isPresent();
	}

	/**
	 * Compile only the part of a search that takes part in ranking, which is
	 * the part highlighting reads its matches from. A document is highlighted
	 * for what ranked it, never for what only narrowed it - not for the
	 * category it happens to be in.
	 *
	 * @param clauses
	 * @return
	 *   the ranking part, or {@code null} when nothing in the search ranks
	 *   and there is nothing to highlight
	 */
	public org.apache.lucene.search.Query compileScoring(ListIterable<Query> clauses) {
		var scoring = pruneToScoring(clauses);
		if(scoring.isEmpty()) {
			return null;
		}

		return compileAll(scoring);
	}

	/**
	 * Keep the clauses that take part in ranking, descending into nesting to
	 * drop the filters it holds.
	 *
	 * A boost is kept whole: its clauses are the condition for the lift, so
	 * text among them ranks documents and deserves highlighting, while a
	 * filter among them lands in a field highlighting never reads. What the
	 * result is used for is extracting terms, never running, so dropping the
	 * branches of an {@code or} that only filter cannot change what matched.
	 *
	 * @param clauses
	 * @return
	 */
	private ListIterable<Query> pruneToScoring(ListIterable<Query> clauses) {
		return clauses
			.collect(this::pruneToScoring)
			.select(clause -> clause != null);
	}

	private Query pruneToScoring(Query clause) {
		if(!clause.scores()) {
			return null;
		}

		return switch(clause) {
			case AndQuery q -> AndQuery.of(pruneToScoring(q.clauses()));
			case OrQuery q -> OrQuery.of(pruneToScoring(q.clauses()));
			default -> clause;
		};
	}

	/**
	 * Resolve the Lucene field a highlight of the named field reads, in the
	 * locale this search reads the field in - the variant whose term vectors
	 * the terms of the search land in.
	 *
	 * @param name
	 * @return
	 * @throws IndexFieldNotFoundException
	 *   if the index has no field by the name
	 * @throws IndexFieldUsageException
	 *   if the field was not defined for highlighting
	 */
	public String highlightField(String name) {
		var field = field(name);
		select(name, field);

		return field.getType().getHighlightFieldName(encounter);
	}

	/**
	 * Resolve the counter that counts matches per value of the named field, in
	 * the locale this search reads the field in - the same variant matching
	 * and sorting resolve to.
	 *
	 * @param name
	 * @return
	 * @throws IndexFieldNotFoundException
	 *   if the index has no field by the name
	 * @throws IndexFieldUsageException
	 *   if the field was not defined for faceting
	 */
	public FacetCounter facetCounter(String name) {
		var field = namedField(name);
		if(!field.isFaceted()) {
			throw new IndexFieldUsageException(name, "facet");
		}

		select(name, field);
		return field.getType().createFacetCounter(encounter);
	}

	/**
	 * Resolve the counter that counts matches into the given buckets of the
	 * named field, in the locale this search reads the field in.
	 *
	 * @param name
	 * @param ranges
	 * @return
	 * @throws IndexFieldNotFoundException
	 *   if the index has no field by the name
	 * @throws IndexFieldUsageException
	 *   if the field was not defined for faceting
	 * @throws IndexInvalidQueryTypeException
	 *   if values of the field have no order to bucket
	 */
	public RangeFacetCounter rangeFacetCounter(
		String name,
		ListIterable<Facet.Range> ranges
	) {
		var field = namedField(name);
		if(!field.isFaceted()) {
			throw new IndexFieldUsageException(name, "facet");
		}

		select(name, field);
		return field.getType().createRangeFacetCounter(encounter, ranges);
	}

	/**
	 * Get whether values of the named field are read as paths through a tree,
	 * which is what decides whether a facet on it counts a level at a time or
	 * per whole value.
	 *
	 * @param name
	 * @return
	 * @throws IndexFieldNotFoundException
	 *   if the index has no field by the name
	 */
	public boolean isHierarchical(String name) {
		var field = namedField(name);

		select(name, field);
		return field.getType().isHierarchical(encounter);
	}

	/**
	 * Resolve the counter that counts matches per level of the named field, in
	 * the locale this search reads the field in.
	 *
	 * @param name
	 * @return
	 * @throws IndexFieldNotFoundException
	 *   if the index has no field by the name
	 * @throws IndexFieldUsageException
	 *   if the field was not defined for faceting, or holds no values that are
	 *   paths
	 */
	public HierarchyFacetCounter hierarchyFacetCounter(String name) {
		var field = namedField(name);
		if(!field.isFaceted()) {
			throw new IndexFieldUsageException(name, "facet");
		}

		select(name, field);
		return field.getType().createHierarchyFacetCounter(encounter);
	}

	/**
	 * Compile the order results come back in.
	 *
	 * The search decides the primary ordering - its own sort, or the best
	 * matches first when it gave none - and the tie breakers of the index are
	 * appended after it. They only ever decide the order within ties, where
	 * the search expressed no preference, so they can never disturb the order
	 * it did ask for.
	 *
	 * @param sort
	 * @param clauses
	 *   the clauses of the search, which a step ordering by a value inside an
	 *   object reads the values it may order by from
	 * @return
	 *   the order, or {@code null} to leave the best matches first
	 */
	public Sort compileSort(ListIterable<SortBy> sort, ListIterable<Query> clauses) {
		var tieBreakers = tieBreakersNotIn(sort);
		if(sort.isEmpty() && tieBreakers.isEmpty()) {
			// Nobody has an opinion beyond the score, which Lucene does fastest
			return null;
		}

		var fields = Lists.mutable.<SortField>empty();
		if(sort.isEmpty()) {
			fields.add(SortField.FIELD_SCORE);
		} else {
			for(var entry : sort) {
				fields.add(compileSort(entry, clauses));
			}
		}

		for(var breaker : tieBreakers) {
			fields.add(compileTieBreaker(breaker));
		}

		return new Sort(fields.toArray(new SortField[0]));
	}

	/**
	 * Get the tie breakers of the index that are not already part of a sort.
	 * A field the search sorts by leaves no ties for the same field to break,
	 * so repeating it would only cost the comparison.
	 *
	 * @param sort
	 * @return
	 */
	private ListIterable<RankingConfig.TieBreaker> tieBreakersNotIn(ListIterable<SortBy> sort) {
		var tieBreakers = schema.getTieBreakers();
		if(tieBreakers.isEmpty() || sort.isEmpty()) {
			return tieBreakers;
		}

		var named = Sets.mutable.<String>empty();
		for(var entry : sort) {
			if(entry instanceof FieldSort field) {
				named.add(field.field());
			} else if(entry instanceof GeoDistanceSort geo) {
				named.add(geo.field());
			}
		}

		return tieBreakers.reject(breaker -> named.contains(breaker.getField()));
	}

	private SortField compileTieBreaker(RankingConfig.TieBreaker breaker) {
		var field = field(breaker.getField());
		select(breaker.getField(), field);

		/*
		 * Anything but an explicit ascending reads as descending, the way
		 * recency and popularity do.
		 */
		return field.getType().createSortField(
			encounter,
			breaker.getDirection() == RankingConfig.TieBreaker.Direction.DIRECTION_ASCENDING
		);
	}

	/**
	 * Multiply the values the documents themselves carry into the score of a
	 * compiled search.
	 *
	 * The signals of the index are what a search ranks by unless it brought its
	 * own, which replace them whole - trying out a ranking means running the
	 * one being tried, not it added to the one in place. Every shape is
	 * bounded, so a signal lifts a document by at most its weight, and a
	 * document holding no value for one is left exactly as it matched.
	 *
	 * Only meaningful where relevance is the ordering: a search sorting by a
	 * field of its own reads the field rather than the score, so the caller
	 * leaves the query as it is.
	 *
	 * @param query
	 *   the compiled search
	 * @param signals
	 *   the signals the search asked to rank by, or {@code null} to rank by
	 *   the ones the index declares
	 * @return
	 *   the query, wrapped when anything ranks by a value, and unchanged when
	 *   nothing does
	 * @throws IndexFieldNotFoundException
	 *   if a signal names a field the index does not have
	 * @throws IndexFieldUsageException
	 *   if a signal names a field that was not defined for sorting, which is
	 *   the value it would read
	 * @throws IndexInvalidQueryTypeException
	 *   if a signal shapes a field in a way that means nothing for its type
	 */
	public org.apache.lucene.search.Query applySignals(
		org.apache.lucene.search.Query query,
		ListIterable<RankingSignal> signals
	) {
		var requested = signals == null ? schema.getSignals() : signals;
		if(requested.isEmpty()) {
			return query;
		}

		/*
		 * Read once, so that every document of one search is aged against the
		 * same clock however long ranking them takes.
		 */
		var now = System.currentTimeMillis();

		var applied = Lists.mutable.<RankingSignals.Applied>empty();
		for(var signal : requested) {
			var field = field(signal.field());
			if(!field.getType().isRankingSupported(signal)) {
				throw new IndexInvalidQueryTypeException(
					field.getDef().getType().getTypeCase().name().toLowerCase(Locale.ROOT),
					signal.type()
				);
			}

			select(signal.field(), field);
			applied.add(
				new RankingSignals.Applied(
					field.getType().createRankingSource(encounter),
					shapeOf(signal, now),
					signal.weight()
				)
			);
		}

		return FunctionScoreQuery.boostByValue(query, RankingSignals.of(applied));
	}

	/**
	 * Get what a signal makes of the value it reads. A new shape is a record in
	 * {@link RankingSignals}, an entry in its {@code permits} and a branch
	 * here.
	 *
	 * @param signal
	 * @param now
	 * @return
	 */
	private static RankingSignals.Shape shapeOf(RankingSignal signal, long now) {
		return switch(signal) {
			case SaturationSignal s -> new RankingSignals.Saturation(s.pivot());
			case DecaySignal s -> new RankingSignals.Decay(s.halfLife().toMillis(), now);
		};
	}

	private org.apache.lucene.search.Query compile(Query clause) {
		return switch(clause) {
			case FieldQuery q -> compileField(q);
			case TextQuery q -> compileText(q);
			case KnnQuery q -> compileKnn(q);
			case NestedQuery q -> compileNested(q);
			case AndQuery q -> compileAll(q.clauses());
			case OrQuery q -> compileAny(q.clauses());
			case NotQuery q -> compileNone(q.clauses());
			case BoostQuery q -> new org.apache.lucene.search.BoostQuery(
				compileAll(q.clauses()),
				q.weight()
			);
		};
	}

	/**
	 * Compile a condition on single values of an object field.
	 *
	 * The clauses run against the Lucene documents those values were written
	 * as, so they hold inside one value at a time; what they match is then
	 * joined back to the documents the values belong to. Empty clauses ask
	 * only that a value exists.
	 *
	 * A clause holding something that ranks lifts the document by what its
	 * values scored, which of them deciding is the clause's to say - so a
	 * product is as relevant as its best variant unless the search asked for
	 * another reading. Holding nothing that ranks leaves the document scored
	 * by the rest of the search, the way any filter does.
	 *
	 * @param clause
	 * @return
	 */
	private org.apache.lucene.search.Query compileNested(NestedQuery clause) {
		var field = field(clause.path());
		if(!field.isObject()) {
			throw new IndexInvalidQueryTypeException(
				field.getDef().getType().getTypeCase().name().toLowerCase(Locale.ROOT),
				"nested"
			);
		}

		if(!field.isNestedObject()) {
			throw new IndexException(NESTED_ON_FLATTENED, "path", clause.path());
		}

		requireNestedSupported(clause.clauses());

		var scores = clause.scores();

		return new ToParentBlockJoinQuery(
			valuesOf(clause.path(), clause.clauses(), scores),
			nestedParents,
			scores ? scoreModeOf(clause.score()) : ScoreMode.None
		);
	}

	/**
	 * Compile the query matching the values of an object field that satisfy
	 * the given clauses - the Lucene documents the values were written as,
	 * never the documents holding them.
	 *
	 * @param path
	 *   name of the object field, which names inside the clauses resolve under
	 * @param clauses
	 *   what has to hold inside a single value
	 * @param scores
	 *   whether what the clauses matched decides a score, or only narrows
	 * @return
	 */
	private org.apache.lucene.search.Query valuesOf(
		String path,
		ListIterable<Query> clauses,
		boolean scores
	) {
		var enclosing = nestedPath;
		nestedPath = path;
		try {
			return new BooleanQuery.Builder()
				.add(NestedDocuments.childrenQuery(path), BooleanClause.Occur.FILTER)
				.add(
					compileAll(clauses),
					scores ? BooleanClause.Occur.MUST : BooleanClause.Occur.FILTER
				)
				.build();
		} finally {
			nestedPath = enclosing;
		}
	}

	/**
	 * Get how Lucene rolls the values that matched into the score of the
	 * document holding them.
	 */
	private static ScoreMode scoreModeOf(NestedQuery.Score score) {
		return switch(score) {
			case MAX -> ScoreMode.Max;
			case MIN -> ScoreMode.Min;
			case AVG -> ScoreMode.Avg;
			case TOTAL -> ScoreMode.Total;
		};
	}

	/**
	 * Check that everything inside a {@code nested} clause is something that
	 * can run against a single value. A clause that only means something for
	 * the documents of the index - another {@code nested}, or a {@code knn}
	 * picking the nearest documents - is refused before compiling starts,
	 * because there are no such documents to run it against.
	 *
	 * @param clauses
	 */
	private void requireNestedSupported(ListIterable<Query> clauses) {
		for(var clause : clauses) {
			switch(clause) {
				case FieldQuery q -> {
					// Runs against the fields of the value
				}
				case TextQuery q -> {
					// Covers the fields of the path, one value at a time
				}
				case AndQuery q -> requireNestedSupported(q.clauses());
				case OrQuery q -> requireNestedSupported(q.clauses());
				case NotQuery q -> requireNestedSupported(q.clauses());
				case BoostQuery q -> requireNestedSupported(q.clauses());
				default -> throw new IndexException(
					NESTED_UNSUPPORTED_CLAUSE,
					"type", clause.type()
				);
			}
		}
	}

	/**
	 * Compile the query matching the values of an object field that a search
	 * matched - the values of the documents it found, narrowed the same way the
	 * search narrowed them.
	 *
	 * This is what counting a facet over a field inside an object runs against,
	 * so a count is of the values the search actually matched rather than of
	 * every value the matching documents happen to hold. The narrowing is read
	 * off the search itself, see {@link #requiredValues}.
	 *
	 * @param path
	 *   name of the object field
	 * @param documents
	 *   the compiled search, matching documents of the index
	 * @param clauses
	 *   the clauses of the search, for the conditions it put on the values
	 * @return
	 */
	public org.apache.lucene.search.Query compileNestedValues(
		String path,
		org.apache.lucene.search.Query documents,
		ListIterable<Query> clauses
	) {
		return new BooleanQuery.Builder()
			.add(
				new ToChildBlockJoinQuery(documents, nestedParents),
				BooleanClause.Occur.FILTER
			)
			.add(matchedValues(path, clauses), BooleanClause.Occur.FILTER)
			.build();
	}

	/**
	 * Compile the query matching the values of an object field that a search
	 * asked something of, whichever document they belong to.
	 */
	private org.apache.lucene.search.Query matchedValues(
		String path,
		ListIterable<Query> clauses
	) {
		var required = Lists.mutable.<Query>empty();
		requiredValues(clauses, path, required);

		return valuesOf(path, required, false);
	}

	/**
	 * Gather what a search asked of the values of one object field, out of the
	 * {@code nested} clauses every result of it had to satisfy.
	 *
	 * This is what makes ordering by and counting a value inside an object
	 * describe the same values the search matched. Only clauses every result
	 * satisfied are gathered: inside an {@code or} a clause is one of several
	 * ways to match, inside a {@code not} it is what somebody asked to be rid
	 * of, and inside a {@code boost} it never narrowed anything - a result may
	 * hold no value satisfying any of them, so none of them says which value a
	 * document was found by.
	 *
	 * @param clauses
	 * @param path
	 * @param required
	 *   where the clauses of every {@code nested} clause on the path are
	 *   gathered
	 */
	private static void requiredValues(
		ListIterable<Query> clauses,
		String path,
		MutableList<Query> required
	) {
		for(var clause : clauses) {
			if(clause instanceof AndQuery q) {
				requiredValues(q.clauses(), path, required);
			} else if(clause instanceof NestedQuery q && q.path().equals(path)) {
				required.addAllIterable(q.clauses());
			}
		}
	}

	/**
	 * Compile clauses that all have to be satisfied. No clauses is not a
	 * narrowing at all, which is how a search with nothing in it lists an
	 * index.
	 *
	 * @param clauses
	 * @return
	 */
	private org.apache.lucene.search.Query compileAll(ListIterable<Query> clauses) {
		if(clauses.isEmpty()) {
			return new MatchAllDocsQuery();
		}

		/*
		 * A single clause is the query, with one exception - a boost on its own
		 * still has to be wrapped, as being optional is the whole of what it
		 * says.
		 */
		if(clauses.size() == 1 && !(clauses.get(0) instanceof BoostQuery)) {
			return compile(clauses.get(0));
		}

		var builder = new BooleanQuery.Builder();
		for(var clause : clauses) {
			builder.add(compile(clause), occurOf(clause));
		}

		return builder.build();
	}

	/**
	 * Compile clauses where one is enough. No clauses narrows to nothing
	 * rather than to everything - a list of alternatives that turned out to
	 * hold none is not the same as no list at all.
	 *
	 * @param clauses
	 * @return
	 */
	private org.apache.lucene.search.Query compileAny(ListIterable<Query> clauses) {
		if(clauses.isEmpty()) {
			return new MatchNoDocsQuery();
		}

		var builder = new BooleanQuery.Builder();
		builder.setMinimumNumberShouldMatch(1);

		for(var clause : clauses) {
			builder.add(compile(clause), BooleanClause.Occur.SHOULD);
		}

		return builder.build();
	}

	/**
	 * Compile clauses that documents must not satisfy. Excluding brings
	 * nothing in on its own, so everything is let through first and the
	 * exclusions are taken out of it.
	 *
	 * @param clauses
	 * @return
	 */
	private org.apache.lucene.search.Query compileNone(ListIterable<Query> clauses) {
		if(clauses.isEmpty()) {
			return new MatchAllDocsQuery();
		}

		var builder = new BooleanQuery.Builder();
		builder.add(new MatchAllDocsQuery(), BooleanClause.Occur.FILTER);

		for(var clause : clauses) {
			builder.add(compile(clause), BooleanClause.Occur.MUST_NOT);
		}

		return builder.build();
	}

	/**
	 * Get how a clause takes part in the query it sits in.
	 *
	 * @param clause
	 * @return
	 */
	private static BooleanClause.Occur occurOf(Query clause) {
		if(clause instanceof BoostQuery) {
			return BooleanClause.Occur.SHOULD;
		}

		return clause.scores()
			? BooleanClause.Occur.MUST
			: BooleanClause.Occur.FILTER;
	}

	private org.apache.lucene.search.Query compileField(FieldQuery clause) {
		var field = field(clause.field());
		select(clause.field(), field);

		if(clause.matcher() instanceof TextMatcher matcher
			&& matcher.match() == TextMatcher.Match.USER) {
			return compileUserField(clause.field(), field, matcher);
		}

		return field.getType().createQuery(encounter, clause.matcher());
	}

	/**
	 * Compile text somebody typed into a search box against a single field.
	 *
	 * A field that holds no order to ask for answers a quoted part as the
	 * loose words inside it - the quotes were typed by somebody who cannot be
	 * expected to know which fields a search covers, so they narrow what is
	 * asked for where that means something and are let go where it does not.
	 *
	 * @param name
	 * @param field
	 * @param matcher
	 * @return
	 */
	private org.apache.lucene.search.Query compileUserField(
		String name,
		Field field,
		TextMatcher matcher
	) {
		var typed = UserText.parse(matcher.text());
		if(typed.isEmpty()) {
			return field.getType().createQuery(
				encounter,
				matcher.withMatch(TextMatcher.Match.ALL)
			);
		}

		var phrases = field.getType().isPhraseSearchable(encounter);
		var clauses = Lists.mutable.<Query>empty();

		for(var part : typed.required(matcher)) {
			clauses.add(Query.field(name, phrases ? part : withoutPhrase(part)));
		}

		var excluded = typed.excluded(matcher)
			.collect(part -> (Query) Query.field(name, phrases ? part : withoutPhrase(part)));
		if(!excluded.isEmpty()) {
			clauses.add(NotQuery.of(excluded));
		}

		return compileAll(clauses);
	}

	/**
	 * Get a matcher asking for the words of a phrase without asking for their
	 * order, for the fields that hold no order to ask for.
	 */
	private static TextMatcher withoutPhrase(TextMatcher matcher) {
		return matcher.match() == TextMatcher.Match.PHRASE
			? matcher.withMatch(TextMatcher.Match.ALL)
			: matcher;
	}

	/**
	 * Compile a nearest-neighbour search of one field.
	 *
	 * The pre-filter narrows which documents may be neighbours before the
	 * nearest are picked, so a filtered search still returns up to {@code k}
	 * results - a filter beside the clause would only intersect the top-k
	 * afterwards. It is compiled first, because compiling its clauses points
	 * the shared encounter at each of their fields.
	 *
	 * @param clause
	 * @return
	 */
	private org.apache.lucene.search.Query compileKnn(KnnQuery clause) {
		var field = field(clause.field());

		var filter = clause.filter().isEmpty()
			? null
			: compileAll(clause.filter());

		select(clause.field(), field);
		return field.getType().createKnnQuery(encounter, clause.vector(), clause.k(), filter);
	}

	/**
	 * Compile a search for text over several fields, combined the way the
	 * clause asks - word by word across the fields, or field by field with a
	 * document ranked by the field it matched best.
	 *
	 * @param clause
	 * @return
	 */
	private org.apache.lucene.search.Query compileText(TextQuery clause) {
		if(clause.matcher().match() == TextMatcher.Match.USER) {
			return compileUserText(clause);
		}

		var fields = clause.fields().isEmpty()
			? defaultTextFields(clause.matcher())
			: clause.fields();

		if(fields.isEmpty()) {
			throw new IndexException(NO_SEARCHABLE_FIELDS);
		}

		var weighted = fields.keyValuesView().toList();
		if(weighted.size() == 1) {
			var only = weighted.get(0);
			return compileText(clause.matcher(), only.getOne(), only.getTwo());
		}

		if(clause.combine() == TextQuery.Combine.TERM) {
			return compileTextByTerm(clause.matcher(), weighted);
		}

		/*
		 * A document is ranked by the field it matched best rather than by all
		 * of them added up. The same words tend to turn up in a name and again
		 * in the text below it, and adding those together would rank a
		 * document that repeats itself above one that is simply a better match.
		 */
		return new DisjunctionMaxQuery(
			weighted.collect(field -> compileText(clause.matcher(), field.getOne(), field.getTwo())),
			TIE_BREAKER
		);
	}

	/**
	 * Compile text somebody typed into a search box, as the clauses the quotes
	 * and exclusions in it stand for.
	 *
	 * Every part is the same text search the caller wrote, asked of the same
	 * fields and combined the same way - what a person typed decides which
	 * clauses there are, never how each of them behaves. The exclusions are
	 * one {@code not}, so a document is dropped as soon as any of them matches
	 * it, and a text of nothing but exclusions still runs against the whole
	 * index.
	 *
	 * @param clause
	 * @return
	 */
	private org.apache.lucene.search.Query compileUserText(TextQuery clause) {
		var matcher = clause.matcher();
		var typed = UserText.parse(matcher.text());

		if(typed.isEmpty()) {
			/*
			 * Nothing that can be searched for was typed. Answered the way a
			 * text that analysis leaves nothing of is - an empty search box
			 * asked for nothing, which is not the same as asking for
			 * everything.
			 */
			return compileText(
				clause.withMatcher(matcher.withMatch(TextMatcher.Match.ALL))
			);
		}

		var clauses = Lists.mutable.<Query>empty();
		for(var part : typed.required(matcher)) {
			clauses.add(userTextPart(clause, part));
		}

		var excluded = typed.excluded(matcher)
			.collect(part -> (Query) userTextPart(clause, part));
		if(!excluded.isEmpty()) {
			clauses.add(NotQuery.of(excluded));
		}

		return compileAll(clauses);
	}

	/**
	 * Build the clause one part of a typed text stands for.
	 *
	 * A quoted part covers fewer fields than loose words do, the same way a
	 * phrase written as its own clause does - a field that only completes text
	 * has no order to ask for. Here the fields were not named for this part
	 * but for the whole text, so the ones that cannot answer it are left out
	 * rather than refused, and a quoted part with no field left to ask is
	 * answered as the loose words inside it.
	 */
	private TextQuery userTextPart(TextQuery clause, TextMatcher part) {
		if(part.match() != TextMatcher.Match.PHRASE) {
			return new TextQuery(part, clause.fields(), clause.combine());
		}

		var fields = clause.fields().isEmpty()
			? defaultTextFields(part)
			: phraseFields(clause.fields());

		return fields.isEmpty()
			? new TextQuery(withoutPhrase(part), clause.fields(), clause.combine())
			: new TextQuery(part, fields, clause.combine());
	}

	/**
	 * Get the named fields that can answer for the order of their words.
	 */
	private ImmutableMap<String, Float> phraseFields(ImmutableMap<String, Float> fields) {
		var result = Maps.mutable.<String, Float>empty();

		for(var entry : fields.keyValuesView()) {
			var field = field(entry.getOne());
			select(entry.getOne(), field);

			if(field.getType().isPhraseSearchable(encounter)) {
				result.put(entry.getOne(), entry.getTwo());
			}
		}

		return result.toImmutable();
	}

	/**
	 * The words one field asks a text search for, weighted by what the field
	 * counts.
	 */
	private record TextFieldTerms(
		float weight,
		ListIterable<org.apache.lucene.search.Query> terms
	) {
	}

	/**
	 * Compile a search for text word by word: each word has to be found in
	 * some field, rather than every word in one.
	 *
	 * Fields whose analysis cut the text into the same number of words are
	 * combined per word, each word a choice between the fields with the best
	 * of them counting. Fields that heard a different number of words - a
	 * chain that decompounds, or drops a stopword the others keep - cannot say
	 * which of their words is which of another field's, so each group of
	 * fields that agree is combined on its own and a document is ranked by the
	 * group that matched it best. A field alone in its group behaves as it
	 * does under {@link TextQuery.Combine#FIELD}.
	 *
	 * @param matcher
	 * @param weighted
	 *   the fields to search with the weight the search gave each of them,
	 *   {@code null} for the weight of its definition
	 * @return
	 */
	private org.apache.lucene.search.Query compileTextByTerm(
		TextMatcher matcher,
		ListIterable<Pair<String, Float>> weighted
	) {
		var alternatives = Lists.mutable.<org.apache.lucene.search.Query>empty();
		var perField = Lists.mutable.<TextFieldTerms>empty();
		var exact = Lists.mutable.<org.apache.lucene.search.Query>empty();

		for(var pair : weighted) {
			var name = pair.getOne();
			var field = field(name);
			select(name, field);

			var weight = pair.getTwo() != null
				? pair.getTwo()
				: field.getType().getTextWeight(encounter);

			var terms = field.getType().createTextTermQueries(encounter, matcher);
			if(terms == null) {
				// The type cannot answer per word, so the field matches on its own
				alternatives.add(
					boosted(field.getType().createQuery(encounter, matcher), weight)
				);
			} else if(!terms.isEmpty()) {
				// A field where nothing survived analysis has nothing to offer any word
				perField.add(new TextFieldTerms(weight, terms));

				/*
				 * A value taken whole is no single word, so it has no place in
				 * the combining below and is gathered for withExact instead.
				 * Only for the fields that answered per word - the ones above
				 * match on their own and already carry it.
				 */
				var whole = field.getType().createTextExactQuery(encounter, matcher);
				if(whole != null) {
					exact.add(boosted(whole, weight));
				}
			}
		}

		var occur = matcher.match() == TextMatcher.Match.ALL
			? BooleanClause.Occur.MUST
			: BooleanClause.Occur.SHOULD;

		for(int size : perField.collect(f -> f.terms().size()).distinct()) {
			var group = perField.select(f -> f.terms().size() == size);

			var builder = new BooleanQuery.Builder();
			for(var i = 0; i < size; i++) {
				var position = i;
				builder.add(
					group.size() == 1
						? group.get(0).terms().get(i)
						: new DisjunctionMaxQuery(
							group.collect(f -> boosted(f.terms().get(position), f.weight())),
							TIE_BREAKER
						),
					occur
				);
			}

			alternatives.add(
				group.size() == 1
					? boosted(builder.build(), group.get(0).weight())
					: builder.build()
			);
		}

		if(alternatives.isEmpty()) {
			// Nothing survived analysis in any field, such as a query of only stopwords
			return new MatchNoDocsQuery();
		}

		return withExact(
			alternatives.size() == 1
				? alternatives.get(0)
				: new DisjunctionMaxQuery(alternatives, TIE_BREAKER),
			exact
		);
	}

	/**
	 * Add what the fields holding, whole, the text that was typed count for.
	 *
	 * Optional beside the words, which have to be found either way, so this
	 * only ever reorders what the search already matched. Counted once however
	 * many fields held the value whole - an airport whose name and whose
	 * municipality are both {@code London} is named London once, not twice -
	 * which is the same reason the fields themselves are a disjunction rather
	 * than a sum.
	 *
	 * @param matched
	 *   the query for the words, whichever way they were combined
	 * @param exact
	 *   the whole-value queries of the fields that offered one, already
	 *   weighted by what each field counts
	 * @return
	 */
	private static org.apache.lucene.search.Query withExact(
		org.apache.lucene.search.Query matched,
		ListIterable<org.apache.lucene.search.Query> exact
	) {
		if(exact.isEmpty()) {
			return matched;
		}

		return new BooleanQuery.Builder()
			.add(matched, BooleanClause.Occur.MUST)
			.add(
				exact.size() == 1
					? exact.getFirst()
					: new DisjunctionMaxQuery(exact.toList(), TIE_BREAKER),
				BooleanClause.Occur.SHOULD
			)
			.build();
	}

	/**
	 * Wrap a query so its hits count the given amount, unless they already do.
	 */
	private static org.apache.lucene.search.Query boosted(
		org.apache.lucene.search.Query query,
		float weight
	) {
		return weight == 1f
			? query
			: new org.apache.lucene.search.BoostQuery(query, weight);
	}

	/**
	 * Compile the search of one field for text, weighted by what the search
	 * said the field counts for, or by what the definition of the field says
	 * when the search left it to the index.
	 *
	 * @param matcher
	 * @param name
	 * @param weight
	 *   the weight the search gave the field, {@code null} for the weight of
	 *   the definition
	 * @return
	 */
	private org.apache.lucene.search.Query compileText(
		TextMatcher matcher,
		String name,
		Float weight
	) {
		var field = field(name);
		select(name, field);

		var resolved = weight != null
			? weight
			: field.getType().getTextWeight(encounter);

		return boosted(field.getType().createQuery(encounter, matcher), resolved);
	}

	/**
	 * Get the fields a search covers when it does not name any, which is every
	 * field that was defined for matching. Each is left to count what its
	 * definition says it does.
	 *
	 * A phrase covers fewer fields than loose words do - a field that only
	 * completes text has no order to ask for - so a search naming no fields
	 * skips the fields that cannot answer it, rather than failing over one it
	 * never asked about. Naming such a field outright is still refused.
	 *
	 * Fields whose name is a pattern are left out - a search covers fields that
	 * exist, and which ones a pattern stands for is only known once a document
	 * has been indexed under it.
	 *
	 * Inside a {@code nested} clause the fields covered are the ones inside its
	 * path, for the same reason a name resolves there and nowhere else - the
	 * clause runs against a single value, and the fields of the index are not
	 * part of one.
	 *
	 * @param matcher
	 * @return
	 */
	private ImmutableMap<String, Float> defaultTextFields(TextMatcher matcher) {
		var phrase = matcher.match() == TextMatcher.Match.PHRASE;
		var fields = Maps.mutable.<String, Float>empty();

		var covered = nestedPath == null
			? schema.getFields()
			: schema.getNestedFields(nestedPath);

		for(var field : covered) {
			if(field.nameHasWildcard()) {
				continue;
			}

			select(field.getName(), field);
			var searchable = phrase
				? field.getType().isPhraseSearchable(encounter)
				: field.getType().isTextSearchable(encounter);
			if(searchable) {
				fields.put(field.getName(), null);
			}
		}

		return fields.toImmutable();
	}

	private SortField compileSort(SortBy sort, ListIterable<Query> clauses) {
		return switch(sort) {
			/*
			 * Lucene orders the best matches first unless told to reverse, the
			 * opposite of how it treats the value of a field.
			 */
			case ScoreSort s -> new SortField(
				null,
				SortField.Type.SCORE,
				s.order() == SortBy.Order.ASCENDING
			);

			case FieldSort s -> {
				var nested = schema.getNestedField(s.field());
				var field = nested.isPresent() ? nested.get().field() : field(s.field());
				if(!field.isSorted()) {
					throw new IndexFieldUsageException(s.field(), "sort");
				}

				var ascending = s.order() == SortBy.Order.ASCENDING;

				select(s.field(), field);
				var ordering = field.getType().createSortField(encounter, ascending);

				yield nested.isPresent()
					? valueSort(ordering, s.field(), nested.get().path(), ascending, clauses)
					: ordering;
			}

			case GeoDistanceSort s -> {
				var nested = schema.getNestedField(s.field());
				var field = nested.isPresent() ? nested.get().field() : field(s.field());
				if(!field.isSorted()) {
					throw new IndexFieldUsageException(s.field(), "sort");
				}

				select(s.field(), field);
				var ordering = field.getType().createDistanceSortField(
					encounter,
					s.latitude(),
					s.longitude()
				);

				yield nested.isPresent()
					? valueSort(ordering, s.field(), nested.get().path(), true, clauses)
					: ordering;
			}
		};
	}

	/**
	 * Order documents by a value inside their objects.
	 *
	 * A document holds several values and one of them has to stand for it. The
	 * one that does is the end the ordering asks for - the smallest of them
	 * when the smallest goes first - so ordering by price ascending is ordering
	 * products by their cheapest value, which is what asking for it means.
	 *
	 * Only the values the search matched take part, read off its {@code nested}
	 * clauses by {@link #requiredValues}. Ordering by the values a search never
	 * asked about is the way this goes quietly wrong: a page of the cheapest
	 * variants in a colour, ordered by the cheapest variant in any colour, is
	 * out of order in a way nothing on it shows.
	 *
	 * @param ordering
	 *   how the values themselves are ordered, as the type of the field builds it
	 * @param name
	 *   the field as the search named it, for pointing at it in an error
	 * @param path
	 *   name of the object field the values belong to
	 * @param ascending
	 *   which way the documents are ordered, which decides which value stands
	 *   for a document
	 * @param clauses
	 *   the clauses of the search
	 * @return
	 */
	private SortField valueSort(
		SortField ordering,
		String name,
		String path,
		boolean ascending,
		ListIterable<Query> clauses
	) {
		if(!NESTED_SORT_TYPES.contains(ordering.getType())) {
			throw new IndexException(NESTED_SORT_UNSUPPORTED, "name", name);
		}

		var sort = new NestedSortField(
			ordering.getField(),
			ordering.getType(),
			ordering.getReverse(),
			!ascending,
			nestedParents,
			new QueryBitSetProducer(matchedValues(path, clauses))
		);

		/*
		 * Where a document holding no matching value ends up is the field's to
		 * say, the same as for a document holding no value at all.
		 */
		if(ordering.getMissingValue() != null) {
			sort.setMissingValue(ordering.getMissingValue());
		}

		return sort;
	}

	/**
	 * Resolve the field a clause names, in the scope the clause sits in.
	 *
	 * The fields inside an object only exist per value, so inside a
	 * {@code nested} clause names resolve to the fields of its path and
	 * nothing else; outside one they resolve to the fields of the index. A
	 * name that exists in the other scope is refused with an error saying so,
	 * because to a caller it would otherwise look identical to a field that
	 * does not exist.
	 *
	 * @param name
	 * @return
	 */
	/**
	 * Resolve a field a search names outside its clauses, which is where an
	 * ordering and a facet name one.
	 *
	 * A field inside an object is named by its dotted path and needs no
	 * {@code nested} clause here, because what it says is about the document
	 * rather than about a single value: which of the values a document is
	 * ordered by, how many documents hold each value. Which values those are is
	 * decided by the clauses of the search, not by where the name is written.
	 *
	 * @param name
	 * @return
	 */
	private Field namedField(String name) {
		var nested = schema.getNestedField(name);
		if(nested.isPresent()) {
			return nested.get().field();
		}

		return field(name);
	}

	private Field field(String name) {
		var nested = schema.getNestedField(name);

		if(nestedPath != null) {
			if(nested.isPresent()) {
				if(!nested.get().path().equals(nestedPath)) {
					throw new IndexException(
						FIELD_NOT_IN_PATH,
						"name", name,
						"path", nestedPath
					);
				}

				return nested.get().field();
			}

			if(schema.getField(name).isPresent()) {
				throw new IndexException(FIELD_NOT_IN_PATH, "name", name, "path", nestedPath);
			}

			throw new IndexFieldNotFoundException(name);
		}

		if(nested.isPresent()) {
			throw new IndexException(
				NESTED_FIELD_OUTSIDE,
				"name", name,
				"path", nested.get().path()
			);
		}

		return schema.getField(name)
			.orElseThrow(() -> new IndexFieldNotFoundException(name));
	}
}
