package se.l4.exofind.engine.api.v1alpha1.search;

import java.time.Duration;
import java.util.List;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MapIterable;

import se.l4.exofind.engine.api.v1alpha1.search.model.Clause;
import se.l4.exofind.engine.api.v1alpha1.search.model.Matcher;
import se.l4.exofind.engine.api.v1alpha1.search.model.SearchRequest;
import se.l4.exofind.engine.api.v1alpha1.search.model.Signal;
import se.l4.exofind.engine.api.v1alpha1.search.model.Sort;
import se.l4.exofind.engine.errors.ErrorMessage;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.Location;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.locales.Locales;
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
import se.l4.exofind.engine.query.SortKey;
import se.l4.exofind.engine.query.TextQuery;
import se.l4.exofind.engine.query.matchers.AnyMatcher;
import se.l4.exofind.engine.query.matchers.DistanceMatcher;
import se.l4.exofind.engine.query.matchers.EqualsMatcher;
import se.l4.exofind.engine.query.matchers.InMatcher;
import se.l4.exofind.engine.query.matchers.PrefixMatcher;
import se.l4.exofind.engine.query.matchers.RangeMatcher;
import se.l4.exofind.engine.query.matchers.TextMatcher;
import se.l4.exofind.engine.query.matchers.UnderMatcher;

/**
 * Translates a search received over the API into the request the engine
 * runs.
 *
 * The wire contract is kept apart from {@link se.l4.exofind.engine.query.SearchRequest}
 * the same way the admin records stay apart from the stored protos - this
 * class is where the two meet, and the only place that has to change when one
 * of them moves.
 *
 * The whole tree is validated before anything runs, collecting every problem
 * with a JSON Pointer path into the request body rather than stopping at the
 * first - a query with two bad clauses reports both. What the mapper checks
 * is the shape of the request; whether a field exists and can be used the way
 * the query asks is the index's to judge, when the request runs.
 */
public class SearchRequestMapper {
	/**
	 * How many page entries a response may hold when the request does not
	 * say.
	 */
	public static final int DEFAULT_PAGES_MAX = 9;

	private static final ErrorType LIMIT_NEGATIVE = ErrorType.withCode("search:limit:negative")
		.withMessage("A search can not return fewer than no results");

	private static final ErrorType OFFSET_NEGATIVE = ErrorType.withCode("search:offset:negative")
		.withMessage("A search can not skip fewer than no results");

	private static final ErrorType PAGE_CONFLICTING = ErrorType.withCode("search:page:conflicting")
		.withMessage(
			"Use at most one of `offset`, `after` and `before` to say where the results start"
		);

	private static final ErrorType PAGE_TOO_DEEP = ErrorType.withCode("search:page:too_deep")
		.withArguments("max")
		.withMessage(
			"The results asked for end past result {{max}}, which is as deep as paging goes"
		);

	private static final ErrorType CURSOR_INVALID = ErrorType.withCode("search:cursor:invalid")
		.withMessage("The cursor is not one this engine handed out");

	private static final ErrorType CURSOR_SORT_MISMATCH =
		ErrorType.withCode("search:cursor:sort_mismatch")
			.withMessage(
				"The cursor was handed out under a different sort, so it does not name a position in these results"
			);

	private static final ErrorType PAGES_WITHOUT_LIMIT =
		ErrorType.withCode("search:pages:without_limit")
			.withMessage("Numbered pages need a limit above zero");

	private static final ErrorType PAGES_WITHOUT_OFFSET =
		ErrorType.withCode("search:pages:without_offset")
			.withMessage(
				"Numbered pages need a position with a number - start from `offset` or a page's cursor rather than `next`/`previous`"
			);

	private static final ErrorType PAGES_INVALID_MAX =
		ErrorType.withCode("search:pages:invalid_max")
			.withMessage("The number of page entries has to be above zero");

	private static final ErrorType SIGNAL_FIELD_REQUIRED =
		ErrorType.withCode("search:signal:field_required")
			.withMessage("The name of the field to read the value from is required");

	private static final ErrorType SIGNAL_SHAPE_INVALID =
		ErrorType.withCode("search:signal:shape_invalid")
			.withMessage(
				"A ranking signal has to be exactly one shape - `saturation`, or `decay`"
			);

	private static final ErrorType SIGNAL_PIVOT_INVALID =
		ErrorType.withCode("search:signal:pivot_invalid")
			.withMessage("The `pivot` of a saturation signal has to be a number above zero");

	private static final ErrorType SIGNAL_HALF_LIFE_INVALID =
		ErrorType.withCode("search:signal:half_life_invalid")
			.withMessage("The `halfLife` of a decay signal has to be a number of seconds above zero");

	private static final ErrorType SIGNAL_WEIGHT_INVALID =
		ErrorType.withCode("search:signal:weight_invalid")
			.withMessage("The `weight` of a ranking signal can not be less than nothing");

	private static final ErrorType REQUIRED = ErrorType.withCode("search:required")
		.withMessage("A value is required here");

	private static final ErrorType CLAUSE_FIELD_REQUIRED =
		ErrorType.withCode("search:clause:field_required")
			.withMessage("The name of the field to match is required");

	private static final ErrorType CLAUSE_MATCH_REQUIRED =
		ErrorType.withCode("search:clause:match_required")
			.withMessage("What to look for in the field is required");

	private static final ErrorType CLAUSE_TEXT_REQUIRED =
		ErrorType.withCode("search:clause:text_required")
			.withMessage("The text to search for is required");

	private static final ErrorType CLAUSE_SLOP_INVALID =
		ErrorType.withCode("search:clause:slop_invalid")
			.withMessage("How far apart the words of a phrase may sit can not be negative");

	private static final ErrorType CLAUSE_SLOP_NOT_APPLICABLE =
		ErrorType.withCode("search:clause:slop_not_applicable")
			.withMessage(
				"Only a phrase has words that sit apart - set `match` to `phrase`, or to `user` to read the quotes in what was typed"
			);

	private static final ErrorType CLAUSE_PATH_REQUIRED =
		ErrorType.withCode("search:clause:path_required")
			.withMessage("The name of the object field to match inside is required");

	private static final ErrorType CLAUSE_VECTOR_REQUIRED =
		ErrorType.withCode("search:clause:vector_required")
			.withMessage("The vector to find the neighbours of is required");

	private static final ErrorType CLAUSE_K_INVALID =
		ErrorType.withCode("search:clause:k_invalid")
			.withMessage("How many neighbours to return is required, above zero");

	private static final ErrorType CLAUSE_WEIGHT_INVALID =
		ErrorType.withCode("search:clause:weight_invalid")
			.withMessage("How much the clauses count is required, zero or above");

	private static final ErrorType MATCHER_VALUE_REQUIRED =
		ErrorType.withCode("search:matcher:value_required")
			.withMessage("A value to look for is required");

	private static final ErrorType MATCHER_RANGE_EMPTY =
		ErrorType.withCode("search:matcher:range_empty")
			.withMessage("A range needs at least one bound");

	private static final ErrorType MATCHER_RANGE_CONFLICTING =
		ErrorType.withCode("search:matcher:range_conflicting")
			.withMessage("Use only one of `gte` and `gt`, and only one of `lte` and `lt`");

	private static final ErrorType FACET_FIELD_REQUIRED =
		ErrorType.withCode("search:facet:field_required")
			.withMessage("The name of the field to count is required");

	private static final ErrorType FACET_NAME_INVALID =
		ErrorType.withCode("search:facet:name_invalid")
			.withMessage("The name of a facet can not be blank - leave it out to key the counts by the field");

	private static final ErrorType FACET_NAME_DUPLICATE =
		ErrorType.withCode("search:facet:duplicate_name")
			.withMessage(
				"Two facets are keyed by the same name - name one of them to tell their counts apart"
			);

	private static final ErrorType FACET_LIMIT_INVALID =
		ErrorType.withCode("search:facet:limit_invalid")
			.withArguments("max")
			.withMessage("A facet brings back between 1 and {{max}} values");

	private static final ErrorType FACET_RANGES_REQUIRED =
		ErrorType.withCode("search:facet:ranges_required")
			.withMessage("Counting into buckets needs at least one bucket - leave `ranges` out to count per value");

	private static final ErrorType FACET_RANGES_TOO_MANY =
		ErrorType.withCode("search:facet:ranges_too_many")
			.withArguments("max")
			.withMessage("A facet counts into at most {{max}} buckets");

	private static final ErrorType FACET_RANGES_CONFLICTING =
		ErrorType.withCode("search:facet:ranges_conflicting")
			.withMessage("Counting into buckets answers one count per bucket in the order given, so neither `limit` nor `order` can be combined with `ranges`");

	private static final ErrorType FACET_PATH_INVALID =
		ErrorType.withCode("search:facet:path_invalid")
			.withMessage(
				"The path of a facet names the level to count the children of - leave it out to count from the top"
			);

	private static final ErrorType FACET_DEPTH_INVALID =
		ErrorType.withCode("search:facet:depth_invalid")
			.withArguments("max")
			.withMessage("A facet counts between 1 and {{max}} levels of a tree");

	private static final ErrorType FACET_RANGES_ON_A_TREE =
		ErrorType.withCode("search:facet:ranges_on_a_tree")
			.withMessage(
				"Counting into buckets answers one count per bucket, so neither `path` nor `depth` can be combined with `ranges`"
			);

	private static final ErrorType FACET_RANGE_EMPTY =
		ErrorType.withCode("search:facet:range_empty")
			.withMessage("A bucket needs at least one bound");

	private static final ErrorType SORT_FIELD_REQUIRED =
		ErrorType.withCode("search:sort:field_required")
			.withMessage("The name of the field to sort by is required");

	private static final ErrorType SORT_ORIGIN_REQUIRED =
		ErrorType.withCode("search:sort:origin_required")
			.withMessage("The `lat` and `lon` of the origin to measure from are required");

	private static final ErrorType MATCHER_ORIGIN_REQUIRED =
		ErrorType.withCode("search:matcher:origin_required")
			.withMessage("The `lat` and `lon` of the origin to measure from are required");

	private static final ErrorType MATCHER_RADIUS_REQUIRED =
		ErrorType.withCode("search:matcher:radius_required")
			.withMessage("How far from the origin values may be is required, in meters");

	private static final ErrorType LOCALE_UNSUPPORTED =
		ErrorType.withCode("search:locale:unsupported")
			.withArguments("locale")
			.withMessage("This engine has no rules for locale `{{locale}}`");

	private static final ErrorType HIGHLIGHT_FIELDS_REQUIRED =
		ErrorType.withCode("search:highlight:fields_required")
			.withMessage("Highlighting needs at least one field to highlight");

	private static final ErrorType HIGHLIGHT_FIELD_REQUIRED =
		ErrorType.withCode("search:highlight:field_required")
			.withMessage("The name of a field to highlight can not be empty");

	private static final ErrorType HIGHLIGHT_FRAGMENTS_INVALID =
		ErrorType.withCode("search:highlight:fragments_invalid")
			.withMessage("How many fragments to return has to be above zero");

	private static final ErrorType HIGHLIGHT_LENGTH_INVALID =
		ErrorType.withCode("search:highlight:length_invalid")
			.withMessage("How long a fragment aims to be has to be between 1 and 10000 characters");

	private static final ErrorType MATCHED_FIELD_REQUIRED =
		ErrorType.withCode("search:matched:field_required")
			.withMessage("The name of an object field to answer matched values for is required");

	private static final ErrorType MATCHED_LIMIT_INVALID =
		ErrorType.withCode("search:matched:limit_invalid")
			.withArguments("max")
			.withMessage("Matched values come back between 1 and {{max}} per field");

	private static final ErrorType MATCHED_FIELDS_EMPTY =
		ErrorType.withCode("search:matched:fields_empty")
			.withMessage("Bringing back only some fields of the values needs at least one field named");

	private static final ErrorType MATCHED_FIELD_NOT_INSIDE =
		ErrorType.withCode("search:matched:field_not_inside")
			.withArguments("field", "path")
			.withMessage("Field `{{field}}` is not inside `{{path}}` - the fields of the values are named by their dotted paths");

	/**
	 * The longest a fragment may aim to be. The engine reads whole stored
	 * values to cut fragments from, so the cap is what keeps a request from
	 * asking for fragments the size of documents.
	 */
	private static final int HIGHLIGHT_MAX_LENGTH = 10_000;

	private SearchRequestMapper() {
	}

	/**
	 * What a request was mapped into, together with what building the
	 * response needs to know about how it was asked.
	 *
	 * @param request
	 *   the request the engine runs
	 * @param fingerprint
	 *   fingerprint of the effective sort, for the cursors of the response
	 * @param pagesMax
	 *   how many page entries the response may hold, or {@code null} when
	 *   numbered pages were not asked for
	 */
	public record Mapped(
		se.l4.exofind.engine.query.SearchRequest request,
		int fingerprint,
		Integer pagesMax
	) {
	}

	/**
	 * Convert a search received over the API into the request the engine
	 * runs.
	 *
	 * @param body
	 *   the request as received, or {@code null} for a request without a
	 *   body - which matches everything, the way an empty request does
	 * @param maxPageDepth
	 *   how deep into the results offset paging may reach
	 * @return
	 * @throws ValidationException
	 *   when the request is not one that can be run, carrying every problem
	 *   found
	 */
	public static Mapped toEngine(SearchRequest body, int maxPageDepth) {
		if(body == null) {
			body = new SearchRequest(
				null, null, null, null, null, null, null, null, null, null, null, null, null, null,
				null
			);
		}

		var errors = Lists.mutable.<ErrorMessage>empty();

		var limit = se.l4.exofind.engine.query.SearchRequest.DEFAULT_LIMIT;
		if(body.limit() != null) {
			if(body.limit() < 0) {
				errors.add(LIMIT_NEGATIVE.toMessage(Location.create("/limit")));
			} else {
				limit = body.limit();
			}
		}

		var sort = toSort(body.sort(), errors);
		var fingerprint = SearchCursor.fingerprintOf(sort);

		var position = resolvePosition(body, fingerprint, errors);

		var query = toClauses(body.query(), "/query", errors);
		var signals = toSignals(body.signals(), errors);
		var filters = toFilters(body.filters(), errors);
		var facets = toFacets(body.facets(), errors);
		var highlight = toHighlight(body.highlight(), errors);
		var matched = toMatched(body.matched(), errors);

		if(body.locale() != null && !Locales.isSupported(body.locale())) {
			errors.add(
				LOCALE_UNSUPPORTED.toMessage(Location.create("/locale"), "locale", body.locale())
			);
		}

		Integer pagesMax = null;
		if(body.pages() != null) {
			if(limit == 0) {
				errors.add(PAGES_WITHOUT_LIMIT.toMessage(Location.create("/pages")));
			}

			if(position.offset() == null) {
				// A keyset position has no number to count pages from
				errors.add(PAGES_WITHOUT_OFFSET.toMessage(Location.create("/pages")));
			}

			var max = body.pages().max() == null ? DEFAULT_PAGES_MAX : body.pages().max();
			if(max <= 0) {
				errors.add(PAGES_INVALID_MAX.toMessage(Location.create("/pages/max")));
			} else if(position.offset() != null) {
				pagesMax = max;
			}
		}

		/*
		 * The cap is on what skipping costs, and a keyset position skips
		 * nothing - which is exactly what makes `next`/`previous` the way
		 * past it.
		 */
		if(position.offset() != null && (long) position.offset() + limit > maxPageDepth) {
			errors.add(PAGE_TOO_DEEP.toMessage(Location.create(""), "max", maxPageDepth));
		}

		if(errors.notEmpty()) {
			throw new ValidationException(errors);
		}

		/*
		 * Numbering pages needs the whole count - a page can not be numbered
		 * against a lower bound.
		 */
		var total = pagesMax != null || body.total() == SearchRequest.Total.EXACT
			? se.l4.exofind.engine.query.SearchRequest.Total.EXACT
			: se.l4.exofind.engine.query.SearchRequest.Total.ESTIMATE;

		var request = se.l4.exofind.engine.query.SearchRequest.create()
			.withQuery(query)
			.withFilters(filters)
			.withFacets(facets)
			.withSort(sort)
			.withLocale(body.locale())
			.withLimit(limit)
			.withOffset(position.offset() == null ? 0 : position.offset())
			.withAfter(position.after())
			.withBefore(position.before())
			.withTotal(total)
			.withSignals(signals);

		if(body.fields() != null) {
			request = request.withFields(body.fields());
		}

		if(highlight != null) {
			request = request.withHighlight(highlight);
		}

		if(matched != null) {
			request = request.withMatched(matched);
		}

		return new Mapped(request.build(), fingerprint, pagesMax);
	}

	/**
	 * Convert the clauses of a request that names documents without searching
	 * them into the query the engine runs.
	 *
	 * @param clauses
	 *   the clauses as received, or {@code null} for none - which matches every
	 *   document
	 * @param path
	 *   where the clauses sit in the request, which is what the problems found
	 *   in them point at
	 * @return
	 * @throws ValidationException
	 *   when a clause is not one that can be run, carrying every problem found
	 */
	public static ImmutableList<Query> toQuery(List<Clause> clauses, String path) {
		var errors = Lists.mutable.<ErrorMessage>empty();
		var query = toClauses(clauses, path, errors);

		if(errors.notEmpty()) {
			throw new ValidationException(errors);
		}

		return query;
	}

	/**
	 * Map the ticked refinements, or collect what is wrong with them.
	 */
	private static ImmutableList<FieldQuery> toFilters(
		List<SearchRequest.Filter> filters,
		MutableList<ErrorMessage> errors
	) {
		if(filters == null) {
			return Lists.immutable.empty();
		}

		var result = Lists.mutable.<FieldQuery>empty();
		for(var i = 0; i < filters.size(); i++) {
			var path = "/filters/" + i;
			var filter = filters.get(i);

			if(filter == null) {
				errors.add(REQUIRED.toMessage(Location.create(path)));
				continue;
			}

			var valid = true;
			if(filter.field() == null || filter.field().isBlank()) {
				errors.add(CLAUSE_FIELD_REQUIRED.toMessage(Location.create(path + "/field")));
				valid = false;
			}

			if(filter.match() == null) {
				errors.add(CLAUSE_MATCH_REQUIRED.toMessage(Location.create(path + "/match")));
				continue;
			}

			var matcher = toMatcher(filter.match(), path + "/match", errors);
			if(matcher != null && valid) {
				result.add(new FieldQuery(filter.field(), matcher));
			}
		}

		return result.toImmutable();
	}

	/**
	 * Map what to count, or collect what is wrong with it.
	 */
	private static ImmutableList<Facet> toFacets(
		List<SearchRequest.Facet> facets,
		MutableList<ErrorMessage> errors
	) {
		if(facets == null) {
			return Lists.immutable.empty();
		}

		var names = Sets.mutable.<String>empty();
		var result = Lists.mutable.<Facet>empty();
		for(var i = 0; i < facets.size(); i++) {
			var path = "/facets/" + i;
			var facet = facets.get(i);

			if(facet == null) {
				errors.add(REQUIRED.toMessage(Location.create(path)));
				continue;
			}

			var valid = true;
			if(facet.field() == null || facet.field().isBlank()) {
				errors.add(FACET_FIELD_REQUIRED.toMessage(Location.create(path + "/field")));
				valid = false;
			}

			if(facet.name() != null && facet.name().isBlank()) {
				errors.add(FACET_NAME_INVALID.toMessage(Location.create(path + "/name")));
				valid = false;
			}

			var limit = Facet.DEFAULT_LIMIT;
			if(facet.limit() != null) {
				if(facet.limit() < 1 || facet.limit() > Facet.MAX_LIMIT) {
					errors.add(FACET_LIMIT_INVALID.toMessage(
						Location.create(path + "/limit"),
						"max", Facet.MAX_LIMIT
					));
					valid = false;
				} else {
					limit = facet.limit();
				}
			}

			if(facet.path() != null && facet.path().isBlank()) {
				errors.add(FACET_PATH_INVALID.toMessage(Location.create(path + "/path")));
				valid = false;
			}

			var depth = Facet.DEFAULT_DEPTH;
			if(facet.depth() != null) {
				if(facet.depth() < 1 || facet.depth() > Facet.MAX_DEPTH) {
					errors.add(FACET_DEPTH_INVALID.toMessage(
						Location.create(path + "/depth"),
						"max", Facet.MAX_DEPTH
					));
					valid = false;
				} else {
					depth = facet.depth();
				}
			}

			var ranges = Lists.mutable.<Facet.Range>empty();
			if(facet.ranges() != null) {
				if(facet.path() != null || facet.depth() != null) {
					errors.add(FACET_RANGES_ON_A_TREE.toMessage(Location.create(path)));
					valid = false;
				}

				if(facet.limit() != null || facet.order() != null) {
					errors.add(FACET_RANGES_CONFLICTING.toMessage(Location.create(path)));
					valid = false;
				}

				if(facet.ranges().isEmpty()) {
					errors.add(FACET_RANGES_REQUIRED.toMessage(Location.create(path + "/ranges")));
					valid = false;
				} else if(facet.ranges().size() > Facet.MAX_LIMIT) {
					errors.add(FACET_RANGES_TOO_MANY.toMessage(
						Location.create(path + "/ranges"),
						"max", Facet.MAX_LIMIT
					));
					valid = false;
				} else {
					for(var j = 0; j < facet.ranges().size(); j++) {
						var range = facet.ranges().get(j);
						if(range == null || range.from() == null && range.to() == null) {
							errors.add(FACET_RANGE_EMPTY.toMessage(
								Location.create(path + "/ranges/" + j)
							));
							valid = false;
							continue;
						}

						ranges.add(new Facet.Range(range.from(), range.to()));
					}
				}
			}

			if(!valid) {
				continue;
			}

			var name = facet.name() == null ? facet.field() : facet.name();
			if(!names.add(name)) {
				errors.add(FACET_NAME_DUPLICATE.toMessage(Location.create(path + "/name")));
				continue;
			}

			result.add(new Facet(
				name,
				facet.field(),
				limit,
				facet.order() == SearchRequest.Facet.Order.VALUE
					? Facet.Order.VALUE
					: Facet.Order.COUNT,
				ranges.toImmutable(),
				facet.path(),
				depth
			));
		}

		return result.toImmutable();
	}

	/**
	 * Map what to highlight, or collect what is wrong with it and return
	 * {@code null}.
	 */
	private static MapIterable<String, se.l4.exofind.engine.query.SearchRequest.Highlight> toHighlight(
		SearchRequest.Highlight highlight,
		MutableList<ErrorMessage> errors
	) {
		if(highlight == null) {
			return null;
		}

		if(highlight.fields() == null || highlight.fields().isEmpty()) {
			// Asking to highlight nothing is a mistake, not a quieter way of
			// not asking
			errors.add(HIGHLIGHT_FIELDS_REQUIRED.toMessage(Location.create("/highlight/fields")));
			return null;
		}

		var result = Maps.mutable.<String, se.l4.exofind.engine.query.SearchRequest.Highlight>empty();
		for(var entry : highlight.fields().entrySet()) {
			var name = entry.getKey();
			if(name == null || name.isBlank()) {
				errors.add(HIGHLIGHT_FIELD_REQUIRED.toMessage(Location.create("/highlight/fields")));
				continue;
			}

			var path = "/highlight/fields/" + name;
			var options = entry.getValue();
			if(options == null) {
				options = new SearchRequest.HighlightField(null, null, null, null);
			}

			var valid = true;
			if(options.fragments() != null && options.fragments() < 1) {
				errors.add(
					HIGHLIGHT_FRAGMENTS_INVALID.toMessage(Location.create(path + "/fragments"))
				);
				valid = false;
			}

			if(options.length() != null
				&& (options.length() < 1 || options.length() > HIGHLIGHT_MAX_LENGTH)) {
				errors.add(HIGHLIGHT_LENGTH_INVALID.toMessage(Location.create(path + "/length")));
				valid = false;
			}

			if(valid) {
				result.put(name, new se.l4.exofind.engine.query.SearchRequest.Highlight(
					options.fragments() == null
						? se.l4.exofind.engine.query.SearchRequest.Highlight.DEFAULT_FRAGMENTS
						: options.fragments(),
					options.length() == null
						? se.l4.exofind.engine.query.SearchRequest.Highlight.DEFAULT_LENGTH
						: options.length(),
					options.pre() == null
						? se.l4.exofind.engine.query.SearchRequest.Highlight.DEFAULT_PRE
						: options.pre(),
					options.post() == null
						? se.l4.exofind.engine.query.SearchRequest.Highlight.DEFAULT_POST
						: options.post()
				));
			}
		}

		return result;
	}

	/**
	 * Map what to answer matched values for, or collect what is wrong with it
	 * and return {@code null}.
	 */
	private static MapIterable<String, se.l4.exofind.engine.query.SearchRequest.Matched> toMatched(
		SearchRequest.Matched matched,
		MutableList<ErrorMessage> errors
	) {
		if(matched == null) {
			return null;
		}

		if(matched.fields() == null || matched.fields().isEmpty()) {
			// Asking about no field is a mistake, not a quieter way of not asking
			errors.add(MATCHED_FIELD_REQUIRED.toMessage(Location.create("/matched/fields")));
			return null;
		}

		var result = Maps.mutable.<String, se.l4.exofind.engine.query.SearchRequest.Matched>empty();
		for(var entry : matched.fields().entrySet()) {
			var name = entry.getKey();
			if(name == null || name.isBlank()) {
				errors.add(MATCHED_FIELD_REQUIRED.toMessage(Location.create("/matched/fields")));
				continue;
			}

			var path = "/matched/fields/" + name;
			var options = entry.getValue();
			if(options == null) {
				options = new SearchRequest.MatchedField(null, null);
			}

			var valid = true;
			if(options.limit() != null
				&& (options.limit() < 1
					|| options.limit() > se.l4.exofind.engine.query.SearchRequest.Matched.MAX_LIMIT)) {
				errors.add(MATCHED_LIMIT_INVALID.toMessage(
					Location.create(path + "/limit"),
					"max", se.l4.exofind.engine.query.SearchRequest.Matched.MAX_LIMIT
				));
				valid = false;
			}

			var inside = Sets.mutable.<String>empty();
			if(options.fields() != null) {
				if(options.fields().isEmpty()) {
					// Asking for values with nothing in them can not be meant
					errors.add(MATCHED_FIELDS_EMPTY.toMessage(Location.create(path + "/fields")));
					valid = false;
				}

				for(var i = 0; i < options.fields().size(); i++) {
					var field = options.fields().get(i);
					/*
					 * A field inside an object is named by its dotted path
					 * everywhere, so a name not led by the path - a bare inner
					 * name, a field of the document, a blank - is a mistake
					 * that needs no index to judge.
					 */
					if(field == null || !field.startsWith(name + ".")
						|| field.length() == name.length() + 1) {
						errors.add(MATCHED_FIELD_NOT_INSIDE.toMessage(
							Location.create(path + "/fields/" + i),
							"field", field == null ? "" : field,
							"path", name
						));
						valid = false;
						continue;
					}

					inside.add(field);
				}
			}

			if(!valid) {
				continue;
			}

			result.put(name, new se.l4.exofind.engine.query.SearchRequest.Matched(
				options.limit() == null
					? se.l4.exofind.engine.query.SearchRequest.Matched.DEFAULT_LIMIT
					: options.limit(),
				inside.toImmutable()
			));
		}

		return result;
	}

	/**
	 * Where the results start, as one of the three ways a request can say it.
	 * An offset is a number of results skipped; a keyset key is the hit a
	 * cursor was taken at, continued past or stopped in front of.
	 *
	 * @param offset
	 *   how many results the position skips, or {@code null} when the
	 *   position is a key and skips nothing
	 * @param after
	 *   the key to continue past, when the position came from a keyset
	 *   {@code after} cursor
	 * @param before
	 *   the key to stop in front of, when it came from a keyset
	 *   {@code before} cursor
	 */
	private record Position(Integer offset, SortKey after, SortKey before) {
		static final Position START = new Position(0, null, null);
	}

	/**
	 * Work out where the results start from whichever of {@code offset},
	 * {@code after} and {@code before} the request carries - at most one of
	 * them, as they are three ways of saying the same thing.
	 */
	private static Position resolvePosition(
		SearchRequest body,
		int fingerprint,
		MutableList<ErrorMessage> errors
	) {
		var given = 0;
		if(body.offset() != null) given++;
		if(body.after() != null) given++;
		if(body.before() != null) given++;

		if(given > 1) {
			errors.add(PAGE_CONFLICTING.toMessage(Location.create("")));
			return Position.START;
		}

		if(body.offset() != null) {
			if(body.offset() < 0) {
				errors.add(OFFSET_NEGATIVE.toMessage(Location.create("/offset")));
				return Position.START;
			}

			return new Position(body.offset(), null, null);
		}

		if(body.after() != null) {
			var cursor = decodeCursor(body.after(), "/after", fingerprint, errors);

			return switch(cursor) {
				case null -> Position.START;
				case SearchCursor.Offset offset -> new Position(offset.offset(), null, null);
				case SearchCursor.Keyset keyset -> new Position(null, keyset.key(), null);
			};
		}

		if(body.before() != null) {
			var cursor = decodeCursor(body.before(), "/before", fingerprint, errors);

			return switch(cursor) {
				case null -> Position.START;
				/*
				 * An offset cursor already points at where the preceding
				 * window starts, so as `before` it still just sets the
				 * offset.
				 */
				case SearchCursor.Offset offset -> new Position(offset.offset(), null, null);
				case SearchCursor.Keyset keyset -> new Position(null, null, keyset.key());
			};
		}

		return Position.START;
	}

	/**
	 * Decode a token, or collect what is wrong with it and return
	 * {@code null}.
	 */
	private static SearchCursor decodeCursor(
		String token,
		String path,
		int fingerprint,
		MutableList<ErrorMessage> errors
	) {
		SearchCursor cursor;
		try {
			cursor = SearchCursor.decode(token);
		} catch(IllegalArgumentException e) {
			errors.add(CURSOR_INVALID.toMessage(Location.create(path)));
			return null;
		}

		/*
		 * A keyset position only means something in the order it was taken
		 * from, so under another sort it is refused. An offset counts the
		 * same whatever the order, and deliberately keeps working.
		 */
		if(cursor instanceof SearchCursor.Keyset && cursor.fingerprint() != fingerprint) {
			errors.add(CURSOR_SORT_MISMATCH.toMessage(Location.create(path)));
			return null;
		}

		return cursor;
	}

	private static ImmutableList<SortBy> toSort(
		List<Sort> sort,
		MutableList<ErrorMessage> errors
	) {
		if(sort == null) {
			return Lists.immutable.empty();
		}

		var result = Lists.mutable.<SortBy>empty();
		for(var i = 0; i < sort.size(); i++) {
			var path = "/sort/" + i;

			switch(sort.get(i)) {
				case null -> errors.add(REQUIRED.toMessage(Location.create(path)));
				case Sort.Score score -> result.add(new ScoreSort(toOrder(score.order())));
				case Sort.Field field -> {
					if(field.field() == null || field.field().isBlank()) {
						errors.add(
							SORT_FIELD_REQUIRED.toMessage(Location.create(path + "/field"))
						);
					} else {
						result.add(new FieldSort(field.field(), toOrder(field.order())));
					}
				}
				case Sort.Distance distance -> {
					var valid = true;

					if(distance.field() == null || distance.field().isBlank()) {
						errors.add(
							SORT_FIELD_REQUIRED.toMessage(Location.create(path + "/field"))
						);
						valid = false;
					}

					if(distance.lat() == null || distance.lon() == null) {
						errors.add(SORT_ORIGIN_REQUIRED.toMessage(Location.create(path)));
						valid = false;
					}

					if(valid) {
						result.add(
							new GeoDistanceSort(distance.field(), distance.lat(), distance.lon())
						);
					}
				}
			}
		}

		return result.toImmutable();
	}

	/**
	 * Convert the signals of a request into the ones the engine ranks by.
	 *
	 * Absent and empty are different answers here: no signals at all leaves the
	 * search to the ranking of the index, while an empty list is a search
	 * saying to rank by how well documents match and nothing else.
	 *
	 * @param signals
	 * @param errors
	 * @return
	 *   the signals, or {@code null} to rank by the ones the index declares
	 */
	private static ImmutableList<RankingSignal> toSignals(
		List<Signal> signals,
		MutableList<ErrorMessage> errors
	) {
		if(signals == null) {
			return null;
		}

		var result = Lists.mutable.<RankingSignal>empty();
		for(var i = 0; i < signals.size(); i++) {
			var path = "/signals/" + i;
			var signal = signals.get(i);

			if(signal == null) {
				errors.add(REQUIRED.toMessage(Location.create(path)));
				continue;
			}

			var valid = true;
			if(signal.field() == null || signal.field().isBlank()) {
				errors.add(SIGNAL_FIELD_REQUIRED.toMessage(Location.create(path + "/field")));
				valid = false;
			}

			if(signal.weight() != null
				&& (!(signal.weight() >= 0) || !Float.isFinite(signal.weight()))) {
				errors.add(SIGNAL_WEIGHT_INVALID.toMessage(Location.create(path + "/weight")));
				valid = false;
			}

			var weight = signal.weight() == null ? 1f : signal.weight();

			if(signal.saturation() != null && signal.decay() != null
				|| signal.saturation() == null && signal.decay() == null) {
				errors.add(SIGNAL_SHAPE_INVALID.toMessage(Location.create(path)));
				continue;
			}

			if(signal.saturation() != null) {
				var pivot = signal.saturation().pivot();
				if(pivot == null || !(pivot > 0) || !Double.isFinite(pivot)) {
					errors.add(
						SIGNAL_PIVOT_INVALID.toMessage(
							Location.create(path + "/saturation/pivot")
						)
					);
				} else if(valid) {
					result.add(new SaturationSignal(signal.field(), pivot, weight));
				}
			} else {
				var halfLife = signal.decay().halfLife();
				if(halfLife == null || halfLife <= 0) {
					errors.add(
						SIGNAL_HALF_LIFE_INVALID.toMessage(
							Location.create(path + "/decay/halfLife")
						)
					);
				} else if(valid) {
					result.add(
						new DecaySignal(signal.field(), Duration.ofSeconds(halfLife), weight)
					);
				}
			}
		}

		return result.toImmutable();
	}

	private static SortBy.Order toOrder(Sort.Order order) {
		return switch(order) {
			case null -> null;
			case ASC -> SortBy.Order.ASCENDING;
			case DESC -> SortBy.Order.DESCENDING;
		};
	}

	private static ImmutableList<Query> toClauses(
		List<Clause> clauses,
		String path,
		MutableList<ErrorMessage> errors
	) {
		if(clauses == null) {
			return Lists.immutable.empty();
		}

		var result = Lists.mutable.<Query>empty();
		for(var i = 0; i < clauses.size(); i++) {
			var query = toClause(clauses.get(i), path + "/" + i, errors);
			if(query != null) {
				result.add(query);
			}
		}

		return result.toImmutable();
	}

	/**
	 * Map a single clause, or collect what is wrong with it and return
	 * {@code null} so the rest of the tree is still looked at.
	 */
	private static Query toClause(
		Clause clause,
		String path,
		MutableList<ErrorMessage> errors
	) {
		switch(clause) {
			case null -> {
				errors.add(REQUIRED.toMessage(Location.create(path)));
				return null;
			}

			case Clause.Field field -> {
				var valid = true;

				if(field.field() == null || field.field().isBlank()) {
					errors.add(CLAUSE_FIELD_REQUIRED.toMessage(Location.create(path + "/field")));
					valid = false;
				}

				if(field.match() == null) {
					errors.add(CLAUSE_MATCH_REQUIRED.toMessage(Location.create(path + "/match")));
					return null;
				}

				var matcher = toMatcher(field.match(), path + "/match", errors);
				if(matcher == null || !valid) {
					return null;
				}

				return Query.field(field.field(), matcher);
			}

			case Clause.Text text -> {
				if(text.text() == null) {
					errors.add(CLAUSE_TEXT_REQUIRED.toMessage(Location.create(path + "/text")));
					return null;
				}

				var query = TextQuery.of(
					new TextMatcher(
						text.text(),
						toMatch(text.match()),
						toPrefix(text.prefix()),
						toTypos(text.typos()),
						toSlop(text.slop(), text.match(), path, errors),
						toRelax(text.relax())
					)
				).withCombine(toCombine(text.combine()));

				if(text.fields() != null) {
					var fields = Maps.mutable.<String, Float>empty();
					fields.putAll(text.fields());
					query = query.withFields(fields.toImmutable());
				}

				return query;
			}

			case Clause.Knn knn -> {
				var valid = true;

				if(knn.field() == null || knn.field().isBlank()) {
					errors.add(CLAUSE_FIELD_REQUIRED.toMessage(Location.create(path + "/field")));
					valid = false;
				}

				if(knn.vector() == null || knn.vector().length == 0) {
					errors.add(
						CLAUSE_VECTOR_REQUIRED.toMessage(Location.create(path + "/vector"))
					);
					valid = false;
				}

				if(knn.k() == null || knn.k() <= 0) {
					errors.add(CLAUSE_K_INVALID.toMessage(Location.create(path + "/k")));
					valid = false;
				}

				var filter = toClauses(knn.filter(), path + "/filter", errors);
				if(!valid) {
					return null;
				}

				return new KnnQuery(knn.field(), knn.vector(), knn.k(), filter);
			}

			case Clause.Nested nested -> {
				var clauses = toClauses(nested.clauses(), path + "/clauses", errors);

				if(nested.path() == null || nested.path().isBlank()) {
					errors.add(
						CLAUSE_PATH_REQUIRED.toMessage(Location.create(path + "/path"))
					);
					return null;
				}

				return NestedQuery.of(nested.path(), clauses)
					.withScore(toNestedScore(nested.score()));
			}

			case Clause.And and -> {
				return AndQuery.of(toClauses(and.clauses(), path + "/clauses", errors));
			}

			case Clause.Or or -> {
				return OrQuery.of(toClauses(or.clauses(), path + "/clauses", errors));
			}

			case Clause.Not not -> {
				return NotQuery.of(toClauses(not.clauses(), path + "/clauses", errors));
			}

			case Clause.Boost boost -> {
				var clauses = toClauses(boost.clauses(), path + "/clauses", errors);

				if(boost.weight() == null || boost.weight() < 0) {
					errors.add(
						CLAUSE_WEIGHT_INVALID.toMessage(Location.create(path + "/weight"))
					);
					return null;
				}

				return BoostQuery.of(boost.weight(), clauses);
			}
		}
	}

	/**
	 * Map a matcher, or collect what is wrong with it and return
	 * {@code null}.
	 */
	private static se.l4.exofind.engine.query.matchers.Matcher toMatcher(
		Matcher matcher,
		String path,
		MutableList<ErrorMessage> errors
	) {
		switch(matcher) {
			case Matcher.Equals equals -> {
				if(equals.value() == null) {
					errors.add(
						MATCHER_VALUE_REQUIRED.toMessage(Location.create(path + "/value"))
					);
					return null;
				}

				return new EqualsMatcher(equals.value());
			}

			case Matcher.In in -> {
				if(in.values() == null) {
					errors.add(
						MATCHER_VALUE_REQUIRED.toMessage(Location.create(path + "/values"))
					);
					return null;
				}

				return InMatcher.of(in.values());
			}

			case Matcher.Any any -> {
				return AnyMatcher.INSTANCE;
			}

			case Matcher.Prefix prefix -> {
				if(prefix.value() == null) {
					errors.add(
						MATCHER_VALUE_REQUIRED.toMessage(Location.create(path + "/value"))
					);
					return null;
				}

				return new PrefixMatcher(prefix.value());
			}

			case Matcher.Under under -> {
				if(under.path() == null) {
					errors.add(
						MATCHER_VALUE_REQUIRED.toMessage(Location.create(path + "/path"))
					);
					return null;
				}

				return new UnderMatcher(under.path());
			}

			case Matcher.Range range -> {
				if(range.gte() != null && range.gt() != null
					|| range.lte() != null && range.lt() != null) {
					errors.add(
						MATCHER_RANGE_CONFLICTING.toMessage(Location.create(path))
					);
					return null;
				}

				var lower = range.gte() != null ? range.gte() : range.gt();
				var upper = range.lte() != null ? range.lte() : range.lt();

				if(lower == null && upper == null) {
					errors.add(MATCHER_RANGE_EMPTY.toMessage(Location.create(path)));
					return null;
				}

				return new RangeMatcher(
					lower,
					range.gte() != null,
					upper,
					range.lte() != null
				);
			}

			case Matcher.Text text -> {
				if(text.text() == null) {
					errors.add(CLAUSE_TEXT_REQUIRED.toMessage(Location.create(path + "/text")));
					return null;
				}

				return new TextMatcher(
					text.text(),
					toMatch(text.match()),
					toPrefix(text.prefix()),
					toTypos(text.typos()),
					toSlop(text.slop(), text.match(), path, errors),
					toRelax(text.relax())
				);
			}

			case Matcher.Distance distance -> {
				var valid = true;

				if(distance.lat() == null || distance.lon() == null) {
					errors.add(MATCHER_ORIGIN_REQUIRED.toMessage(Location.create(path)));
					valid = false;
				}

				if(distance.radius() == null) {
					errors.add(
						MATCHER_RADIUS_REQUIRED.toMessage(Location.create(path + "/radius"))
					);
					valid = false;
				}

				if(!valid) {
					return null;
				}

				return new DistanceMatcher(distance.lat(), distance.lon(), distance.radius());
			}
		}
	}

	private static TextMatcher.Match toMatch(Matcher.Text.Match match) {
		return switch(match) {
			case null -> null;
			case ALL -> TextMatcher.Match.ALL;
			case ANY -> TextMatcher.Match.ANY;
			case PHRASE -> TextMatcher.Match.PHRASE;
			case USER -> TextMatcher.Match.USER;
		};
	}

	/**
	 * Map how far apart the words of a phrase may sit, refusing it where there
	 * is no phrase for it to loosen - a search that asked for something the
	 * engine quietly does nothing with looks the same to its caller as one
	 * that worked.
	 */
	private static int toSlop(
		Integer slop,
		Matcher.Text.Match match,
		String path,
		MutableList<ErrorMessage> errors
	) {
		if(slop == null) {
			return 0;
		}

		if(slop < 0) {
			errors.add(CLAUSE_SLOP_INVALID.toMessage(Location.create(path + "/slop")));
			return 0;
		}

		if(slop > 0
			&& match != Matcher.Text.Match.PHRASE
			&& match != Matcher.Text.Match.USER) {
			errors.add(CLAUSE_SLOP_NOT_APPLICABLE.toMessage(Location.create(path + "/slop")));
			return 0;
		}

		return slop;
	}

	private static TextMatcher.Prefix toPrefix(Matcher.Text.Prefix prefix) {
		return switch(prefix) {
			case null -> null;
			case LAST_TOKEN -> TextMatcher.Prefix.LAST_TOKEN;
			case OFF -> TextMatcher.Prefix.OFF;
		};
	}

	private static TextMatcher.Typos toTypos(Matcher.Text.Typos typos) {
		return switch(typos) {
			case null -> null;
			case AUTO -> TextMatcher.Typos.AUTO;
			case OFF -> TextMatcher.Typos.OFF;
		};
	}

	private static TextMatcher.Relax toRelax(Matcher.Text.Relax relax) {
		return switch(relax) {
			case null -> null;
			case OFF -> TextMatcher.Relax.OFF;
			case UNMATCHED -> TextMatcher.Relax.UNMATCHED;
			case WORDS -> TextMatcher.Relax.WORDS;
		};
	}

	private static TextQuery.Combine toCombine(Clause.Text.Combine combine) {
		return switch(combine) {
			case null -> null;
			case TERM -> TextQuery.Combine.TERM;
			case FIELD -> TextQuery.Combine.FIELD;
		};
	}

	private static NestedQuery.Score toNestedScore(Clause.Nested.Score score) {
		return switch(score) {
			case null -> null;
			case MAX -> NestedQuery.Score.MAX;
			case MIN -> NestedQuery.Score.MIN;
			case AVG -> NestedQuery.Score.AVG;
			case TOTAL -> NestedQuery.Score.TOTAL;
		};
	}
}
