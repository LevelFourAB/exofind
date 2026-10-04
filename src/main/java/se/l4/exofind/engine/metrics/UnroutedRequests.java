package se.l4.exofind.engine.metrics;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.vertx.core.http.impl.HttpServerRequestInternal;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Marks a request that no route template matched, so that
 * {@link HttpUriCardinality} reports it as {@code UNKNOWN} rather than under
 * the path it arrived with.
 *
 * <p>The HTTP server meter names a request by the template of the resource
 * method that answered it. A request refused before a resource method is
 * chosen has no template: a method the path does not have ({@code 405}), a
 * media type the endpoint does not read ({@code 415}) or write
 * ({@code 406}), and a body past the limit ({@code 413}). The meter then
 * names it by its path, which holds whatever the client put in it, such as a
 * document key. Each such path is a series of its own, and those series use
 * up the bound of {@code exofind.metrics.http.max-uri-tags} that the routes
 * share, so a route used for the first time after that is counted as
 * {@code UNKNOWN}.
 *
 * <p>The mark is the template {@link HttpUriCardinality#UNROUTED}, put where
 * the framework puts the template of a matched resource method. It is put once
 * the status is known, because the framework adds to a template already there
 * for a sub-resource. Requests the framework names without a path are left
 * alone: {@code 404} is {@code NOT_FOUND} and a redirect is
 * {@code REDIRECTION}. Requests under the non-application root, such as
 * {@code /q/metrics}, are left alone too, because the meter leaves them out by
 * their path.
 */
@ApplicationScoped
public class UnroutedRequests {
	/**
	 * The request context key the framework keeps the matched template under.
	 * Quarkus REST writes it and the HTTP server meter reads it.
	 */
	static final String TEMPLATE_KEY = "UrlPathTemplate";

	/**
	 * Where this runs among the handlers of the router. Ahead of every handler
	 * that can answer a request, so that every answer is seen.
	 */
	private static final int ORDER = Integer.MIN_VALUE;

	/**
	 * The path every non-application endpoint is under, ending in a slash.
	 */
	private final String nonApplicationRoot;

	public UnroutedRequests(
		@ConfigProperty(name = "quarkus.http.root-path", defaultValue = "/")
		String rootPath,
		@ConfigProperty(name = "quarkus.http.non-application-root-path", defaultValue = "q")
		String nonApplicationRootPath
	) {
		this.nonApplicationRoot = nonApplicationRoot(rootPath, nonApplicationRootPath);
	}

	/**
	 * Add the handler to the router of the node while it is being built.
	 */
	public void register(@Observes Router router) {
		router.route().order(ORDER).handler(this::watch);
	}

	private void watch(RoutingContext context) {
		context.addHeadersEndHandler(ignored -> mark(context));
		context.next();
	}

	private void mark(RoutingContext context) {
		var status = context.response().getStatusCode();
		if(status == 404 || status / 100 == 3) {
			return;
		}

		var path = context.request().path();
		if(path == null || isNonApplication(path)) {
			return;
		}

		var requestContext = ((HttpServerRequestInternal) context.request()).context();
		if(requestContext.getLocal(TEMPLATE_KEY) == null) {
			requestContext.putLocal(TEMPLATE_KEY, HttpUriCardinality.UNROUTED);
		}
	}

	private boolean isNonApplication(String path) {
		return path.startsWith(nonApplicationRoot)
			|| path.equals(nonApplicationRoot.substring(0, nonApplicationRoot.length() - 1));
	}

	/**
	 * Resolve the non-application root the way the framework does: an
	 * absolute path stands on its own, and a relative one is under the root
	 * path.
	 */
	static String nonApplicationRoot(String rootPath, String nonApplicationRootPath) {
		String resolved;
		if(nonApplicationRootPath.startsWith("/")) {
			resolved = nonApplicationRootPath;
		} else {
			var root = rootPath.startsWith("/") ? rootPath : "/" + rootPath;
			resolved = (root.endsWith("/") ? root : root + "/") + nonApplicationRootPath;
		}

		return resolved.endsWith("/") ? resolved : resolved + "/";
	}
}
