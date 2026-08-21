package se.l4.exofind.engine.api.routing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Which node a request that changes something runs on.
 *
 * <p>Every resource method with a mutating HTTP method carries one, the way
 * every method carries {@link se.l4.exofind.engine.api.auth.RequiresPermission}.
 * {@link Node#INDEXER} means only the indexer serves the request, and
 * {@code IndexerForwardFilter} passes it along when it lands on another node;
 * {@link Node#ANY_NODE} means whichever node receives it serves it, which is
 * what an endpoint whose state is one object replaced conditionally says.
 *
 * <p>A mutating method without the annotation is refused rather than served,
 * so forgetting it cannot quietly serve a write on a node that should have
 * passed it along - and {@code RoutingCoverageTest} fails the build before a
 * request ever finds out. Reads are always served where they land and say
 * nothing.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ServedBy {
	/**
	 * Path parameter every endpoint about one index names it with. Both the
	 * forwarding filter and {@code AuthFilter} read which index a request is
	 * about from it, so it is defined once here rather than in each -
	 * {@code RoutingCoverageTest} and {@code AuthCoverageTest} check that
	 * every endpoint spells it this way.
	 */
	String INDEX_PARAMETER = "name";

	/**
	 * The node the request runs on.
	 */
	Node value();

	/**
	 * Whether the request may create the index it names. A request that may
	 * appoints a writer for a name the deployment does not hold yet - which
	 * is every index while it is being created. One that may not is served
	 * where it lands when the index does not exist, so the answer is the
	 * endpoint's own rather than a writer being appointed for a name that
	 * does not exist.
	 */
	boolean creates() default false;

	enum Node {
		/**
		 * Only the indexer serves this request. A node that is not the
		 * indexer passes it along instead of serving it.
		 */
		INDEXER,

		/**
		 * Whichever node receives this request serves it.
		 */
		ANY_NODE;
	}
}
