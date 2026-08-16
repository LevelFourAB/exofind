package se.l4.exofind.engine.api.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import se.l4.exofind.engine.auth.Permission;

/**
 * What a caller has to be granted to reach an endpoint.
 *
 * <p>Every resource method carries one. A method without it is refused rather
 * than served, so forgetting the annotation closes an endpoint instead of
 * opening it, and {@code AuthCoverageTest} fails the build before it ships.
 *
 * <p>A permission of {@link Permission.Scope#INDEX} is checked against the
 * index the request names, which is read from the {@code name} path parameter.
 * An endpoint that is about the indexes without naming one says so with
 * {@link #anyIndex()}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresPermission {
	/**
	 * The permission the caller has to hold.
	 */
	Permission value();

	/**
	 * Whether an index-scoped permission is checked against any index rather
	 * than one the request names.
	 *
	 * <p>Passing means the caller holds the permission on at least one index.
	 * What the response may then contain is the endpoint's own to narrow, which
	 * is what listing the indexes does.
	 */
	boolean anyIndex() default false;
}
