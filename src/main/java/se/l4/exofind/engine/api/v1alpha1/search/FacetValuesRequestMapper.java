package se.l4.exofind.engine.api.v1alpha1.search;

import org.eclipse.collections.api.factory.Lists;

import se.l4.exofind.engine.api.v1alpha1.search.model.FacetValuesRequest;
import se.l4.exofind.engine.errors.ErrorMessage;
import se.l4.exofind.engine.errors.Location;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.locales.Locales;
import se.l4.exofind.engine.query.Facet;

/**
 * Maps a request for the values of one facet into the search the engine
 * counts them with.
 *
 * <p>The engine answers the values of a facet as part of a search, so the
 * request becomes a search that brings back no hits and counts one facet
 * carrying the prefix. The clauses are mapped the way
 * {@link SearchRequestMapper} maps them, and every problem found is collected
 * and reported together, each with a JSON Pointer into the request body.
 */
final class FacetValuesRequestMapper {
	private FacetValuesRequestMapper() {
	}

	/**
	 * Map a request into the search that counts the values.
	 *
	 * @param field
	 *   the facet field, as the URL named it
	 * @param body
	 *   the request as received, or {@code null} for an empty one
	 * @param limits
	 *   what the node allows a search to ask for
	 * @return
	 * @throws ValidationException
	 *   when the request is not one that can be run, carrying every problem
	 *   found
	 */
	static se.l4.exofind.engine.query.SearchRequest toEngine(
		String field,
		FacetValuesRequest body,
		SearchLimits limits
	) {
		if(body == null) {
			body = new FacetValuesRequest(null, null, null, null, null, null);
		}

		var errors = Lists.mutable.<ErrorMessage>empty();

		if(!QueryBudget.check(body.query(), body.filters(), limits, errors)) {
			throw new ValidationException(errors);
		}

		var limit = Facet.DEFAULT_LIMIT;
		if(body.limit() != null) {
			if(body.limit() < 1 || body.limit() > Facet.MAX_LIMIT) {
				errors.add(SearchRequestMapper.FACET_LIMIT_INVALID.toMessage(
					Location.create("/limit"),
					"max", Facet.MAX_LIMIT
				));
			} else {
				limit = body.limit();
			}
		}

		var query = SearchRequestMapper.toClauses(body.query(), "/query", errors);
		var filters = SearchRequestMapper.toFilters(body.filters(), errors);

		if(body.locale() != null && !Locales.isSupported(body.locale())) {
			errors.add(SearchRequestMapper.LOCALE_UNSUPPORTED.toMessage(
				Location.create("/locale"),
				"locale", body.locale()
			));
		}

		if(errors.notEmpty()) {
			throw new ValidationException(errors);
		}

		var facet = Facet.of(field)
			.withLimit(limit)
			.withOrder(SearchRequestMapper.toOrder(body.order()))
			.withPrefix(body.prefix());

		return se.l4.exofind.engine.query.SearchRequest.create()
			.withQuery(query)
			.withFilters(filters)
			.withFacets(facet)
			.withLocale(body.locale())
			.withLimit(0)
			.build();
	}
}
