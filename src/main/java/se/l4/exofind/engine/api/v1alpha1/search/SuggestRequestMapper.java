package se.l4.exofind.engine.api.v1alpha1.search;

import org.eclipse.collections.api.factory.Lists;

import se.l4.exofind.engine.api.v1alpha1.search.model.SuggestRequest;
import se.l4.exofind.engine.errors.ErrorMessage;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.Location;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.locales.Locales;

/**
 * Maps a suggest request into what the engine suggests from.
 *
 * <p>The filters are mapped the way {@link SearchRequestMapper} maps them,
 * and every problem found is collected and reported together, each with a
 * JSON Pointer into the request body.
 */
final class SuggestRequestMapper {
	static final ErrorType LIMIT_INVALID =
		ErrorType.withCode("search:suggest:limit_invalid")
			.withArguments("max")
			.withMessage("A suggest request brings back between 1 and {{max}} suggestions");

	private SuggestRequestMapper() {
	}

	/**
	 * Map a request into what the engine suggests from.
	 *
	 * @param body
	 *   the request as received, or {@code null} for an empty one
	 * @param limits
	 *   what the node allows a request to ask for
	 * @return
	 * @throws ValidationException
	 *   when the request is not one that can be run, carrying every problem
	 *   found
	 */
	static se.l4.exofind.engine.query.SuggestRequest toEngine(
		SuggestRequest body,
		SearchLimits limits
	) {
		if(body == null) {
			body = new SuggestRequest(null, null, null, null, null);
		}

		var errors = Lists.mutable.<ErrorMessage>empty();

		if(!QueryBudget.check(null, body.filters(), limits, errors)) {
			throw new ValidationException(errors);
		}

		var limit = se.l4.exofind.engine.query.SuggestRequest.DEFAULT_LIMIT;
		if(body.limit() != null) {
			var max = se.l4.exofind.engine.query.SuggestRequest.MAX_LIMIT;
			if(body.limit() < 1 || body.limit() > max) {
				errors.add(LIMIT_INVALID.toMessage(Location.create("/limit"), "max", max));
			} else {
				limit = body.limit();
			}
		}

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

		return new se.l4.exofind.engine.query.SuggestRequest(
			body.text(),
			body.locale(),
			filters,
			limit,
			body.typos() != SuggestRequest.Typos.OFF
		);
	}
}
