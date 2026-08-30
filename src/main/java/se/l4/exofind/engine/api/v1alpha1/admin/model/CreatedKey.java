package se.l4.exofind.engine.api.v1alpha1.admin.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Represents a newly created API key and its credential.
 *
 * <p>This response is the only time the plaintext credential is returned.
 *
 * @param credential
 *   what a caller presents as {@code Authorization: Bearer}; stored only as a
 *   hash and cannot be recovered if lost
 * @param key
 *   the key metadata as returned in listings
 */
@Schema(description = """
	A newly created key. This is the only response its credential ever appears \
	in.""")
public record CreatedKey(
	@Schema(
		description = """
			The generated credential, presented as `Authorization: Bearer`. \
			Only a hash of it is stored, so this response is the only chance \
			to keep it. A lost credential cannot be recovered and must be \
			replaced.""",
		examples = "exok_4ff6b760264c1918_ePQcdT1O9HSATZoXfDbT8hhHGsP9VpZH"
	)
	String credential,

	@Schema(description = "The key as a listing will show it from now on.")
	KeyInfo key
) {
}
