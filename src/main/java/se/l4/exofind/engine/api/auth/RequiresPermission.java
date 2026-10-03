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
 *
 * <p>This is also what the OpenAPI document says an endpoint requires:
 * {@code RequiredPermissionFilter} writes it there, along with the {@code 401}
 * and {@code 403} answers, so an endpoint states none of that for itself.
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

	/**
	 * Whether an index-scoped permission is checked against the index itself
	 * when the path names one of its generations.
	 *
	 * <p>For an endpoint that changes or reads what belongs to the whole index,
	 * such as its search settings. A grant of {@code products@*} covers the
	 * generations of {@code products} but not the index, so it must not reach
	 * the settings of {@code products} through the name {@code products@2}.
	 *
	 * <p>Whether the caller sees the named generation at all is still checked
	 * against the name in the path, so a caller who can see the generation is
	 * refused rather than told it does not exist.
	 */
	boolean wholeIndex() default false;
}
