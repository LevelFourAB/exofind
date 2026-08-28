package se.l4.exofind.engine.api.v1alpha1.admin.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

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
@Schema(description = """
	A newly created key. This is the only response its credential ever appears \
	in.""")
public record CreatedKey(
	@Schema(
		description = """
			The generated credential, presented as `Authorization: Bearer`. \
			Only a hash of it is stored, so this response is the one chance to \
			keep it - a lost credential cannot be recovered and must be \
			replaced.""",
		examples = "exok_4ff6b760264c1918_ePQcdT1O9HSATZoXfDbT8hhHGsP9VpZH"
	)
	String credential,

	@Schema(description = "The key as a listing will show it from now on.")
	KeyInfo key
) {
}
