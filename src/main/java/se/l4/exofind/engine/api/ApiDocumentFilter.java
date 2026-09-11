package se.l4.exofind.engine.api;

import java.util.List;

import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.OpenAPI;

import se.l4.exofind.engine.api.auth.RequiredPermissionFilter;
import se.l4.exofind.engine.api.errors.ErrorCodeFilter;

/**
 * Everything the build writes into the OpenAPI document beyond the annotations
 * on the endpoints.
 *
 * <p>One filter is what {@code mp.openapi.filter} names, so this is where a
 * second one is added. The order is the order they are listed in, and it
 * matters once: {@link RequiredPermissionFilter} writes the {@code 401} and
 * {@code 403} answers that no endpoint declares, and a filter that reads the
 * answers of an operation has to run after them.
 */
public class ApiDocumentFilter implements OASFilter {
	private static final List<OASFilter> FILTERS = List.of(
		new RequiredPermissionFilter(),
		new ErrorCodeFilter()
	);

	@Override
	public void filterOpenAPI(OpenAPI api) {
		for(var filter : FILTERS) {
			filter.filterOpenAPI(api);
		}
	}
}
