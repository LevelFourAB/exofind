package se.l4.exofind.engine.api.v1alpha1.admin;

import java.util.ArrayList;
import java.util.List;

/**
 * The precondition an {@code If-None-Match} header states on a read, as RFC
 * 9110 defines it.
 *
 * <p>A client that holds a version of a resource sends it back in this header,
 * and the read answers {@code 304 Not Modified} with no body while the stored
 * version is still one the header names. A header holding {@code *} names
 * every version, so it is satisfied while the resource exists.
 *
 * <p>Versions are compared weakly, as a read asks whether the client holds a
 * representation it can go on using: a weak tag ({@code W/"..."}) is read the
 * same as a strong one. This differs from {@link IfMatch}, where a weak tag
 * matches nothing because a write needs the version it builds on to be the
 * exact one stored.
 *
 * <p>The quotes around a tag are dropped while it is read, so a tag is compared
 * against a version as the engine holds it. A tag without quotes is read as one
 * all the same, which is what a client that sends back the value of an
 * {@code ETag} header writes.
 *
 * <p>The precondition is only read once the resource has been found: a
 * resource that does not exist is {@code 404} whatever the header holds, as
 * {@code docs/reference/api-conventions.md} states.
 */
final class IfNoneMatch {
	/**
	 * What no header states: nothing is asked, so the read answers as it
	 * would without a header.
	 */
	private static final IfNoneMatch NONE = new IfNoneMatch(null);

	/**
	 * The tags the client holds, without their quotes or weakness marker.
	 * {@code null} while no header was sent, and empty while the header holds
	 * {@code *}, which {@link #any} tells from an absent header.
	 */
	private final List<String> versions;

	private final boolean any;

	private IfNoneMatch(List<String> versions) {
		this(versions, false);
	}

	private IfNoneMatch(List<String> versions, boolean any) {
		this.versions = versions;
		this.any = any;
	}

	/**
	 * Read the precondition an {@code If-None-Match} header states.
	 *
	 * @param header
	 *   the header as it was sent, or {@code null} while the request carries
	 *   none
	 * @return
	 *   the precondition, which asks nothing while the header is absent or
	 *   holds no text
	 */
	static IfNoneMatch of(String header) {
		if(header == null) {
			return NONE;
		}

		var value = header.trim();
		if(value.isEmpty()) {
			return NONE;
		}

		if(value.equals("*")) {
			return new IfNoneMatch(List.of(), true);
		}

		/*
		 * A version is written as hexadecimal digits and holds no comma, so the
		 * tags are taken apart on one without reading the quotes first.
		 */
		var versions = new ArrayList<String>();
		for(var tag : value.split(",")) {
			var one = tag.trim();
			if(one.isEmpty()) {
				continue;
			}

			if(one.startsWith("W/")) {
				one = one.substring(2);
			}

			versions.add(IfMatch.unquote(one));
		}

		return new IfNoneMatch(List.copyOf(versions));
	}

	/**
	 * Get whether the client already holds the stored version, so the read
	 * answers {@code 304} rather than the resource again. False while no
	 * header was sent.
	 *
	 * @param version
	 *   the stored version, without the quotes of an entity tag
	 */
	boolean holds(String version) {
		return any || (versions != null && versions.contains(version));
	}
}
