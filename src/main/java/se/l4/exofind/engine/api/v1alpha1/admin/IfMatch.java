package se.l4.exofind.engine.api.v1alpha1.admin;

import java.util.ArrayList;
import java.util.List;

/**
 * The precondition an {@code If-Match} header states, as RFC 9110 defines it.
 *
 * <p>A header holding {@code *} asks only that the resource exists. A header
 * holding entity tags asks that the stored version is one of them, compared
 * strongly: a weak tag ({@code W/"..."}) matches nothing, as a weak tag says
 * two versions answer the same rather than that they are the same. A header may
 * hold more than one tag, separated by commas, and is satisfied while one of
 * them matches.
 *
 * <p>The quotes around a tag are dropped while it is read, so a tag is compared
 * against a version as the engine holds it. A tag without quotes is read as one
 * all the same, which is what a client that sends back the value of an
 * {@code ETag} header writes.
 *
 * <p>What a resource answers is in {@code docs/reference/api-conventions.md}: a
 * precondition on a resource that does not exist is {@code 404}, and a stored
 * version that no tag matches is {@code 412}.
 */
final class IfMatch {
	/**
	 * What no header states: nothing is asked of the resource, so a resource
	 * that does not exist is created rather than reported.
	 */
	private static final IfMatch NONE = new IfMatch(null, null);

	/**
	 * The header as it was sent, for an error to name what was expected.
	 * {@code null} while no header was sent.
	 */
	private final String header;

	/**
	 * The tags to compare the stored version against, without their quotes.
	 * {@code null} while the header holds {@code *}, and empty while every tag
	 * it holds is weak - a precondition nothing satisfies.
	 */
	private final List<String> versions;

	private IfMatch(String header, List<String> versions) {
		this.header = header;
		this.versions = versions;
	}

	/**
	 * Read the precondition an {@code If-Match} header states.
	 *
	 * @param header
	 *   the header as it was sent, or {@code null} while the request carries
	 *   none
	 * @return
	 *   the precondition, which asks nothing while the header is absent or
	 *   holds no text
	 */
	static IfMatch of(String header) {
		if(header == null) {
			return NONE;
		}

		var value = header.trim();
		if(value.isEmpty()) {
			return NONE;
		}

		if(value.equals("*")) {
			return new IfMatch(value, null);
		}

		/*
		 * A version is written as hexadecimal digits and holds no comma, so the
		 * tags are taken apart on one without reading the quotes first.
		 */
		var versions = new ArrayList<String>();
		for(var tag : value.split(",")) {
			var one = tag.trim();

			/*
			 * A weak tag is dropped rather than kept: strong comparison matches
			 * none of them, so a header holding only weak tags states a
			 * precondition no stored version satisfies.
			 */
			if(one.isEmpty() || one.startsWith("W/")) {
				continue;
			}

			versions.add(unquote(one));
		}

		return new IfMatch(value, List.copyOf(versions));
	}

	/**
	 * Get whether the request states a precondition. A request that states one
	 * is answered with {@code 404} while the resource does not exist, rather
	 * than creating it.
	 */
	boolean isConditional() {
		return header != null;
	}

	/**
	 * Get whether the header holds versions rather than {@code *}. A request
	 * that holds versions has its write made conditional on the one that
	 * matched, so a change landing between the read and the write is reported
	 * rather than overwritten; {@code *} asks only that the resource exists.
	 */
	boolean namesVersions() {
		return versions != null;
	}

	/**
	 * Get whether a stored version satisfies the precondition. True while no
	 * header was sent, and while the header holds {@code *} - what {@code *}
	 * asks is that the resource exists, which the caller has already read.
	 *
	 * @param version
	 *   the stored version, without the quotes of an entity tag
	 */
	boolean matches(String version) {
		return versions == null || versions.contains(version);
	}

	/**
	 * The header as it was sent, for an error to name what the request
	 * expected. {@code null} while no header was sent.
	 */
	String describe() {
		return header;
	}

	private static String unquote(String tag) {
		if(tag.length() >= 2 && tag.startsWith("\"") && tag.endsWith("\"")) {
			return tag.substring(1, tag.length() - 1);
		}

		return tag;
	}
}
