package se.l4.exofind.engine.api.v1alpha1.admin.model;

/**
 * A key that has just been created, and the only time its credential is ever
 * answered with.
 *
 * @param credential
 *   what a caller presents as {@code Authorization: Bearer}. Only a hash of it
 *   is stored, so this response is the one chance to keep it
 * @param key
 *   the key as listing it will show it from now on
 */
public record CreatedKey(
	String credential,
	KeyInfo key
) {
}
