package se.l4.exofind.engine.api.v1alpha1.search.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A field that a sort or a facet reads in place of the one before it, where a
 * document holds no value there.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
	name = "FallbackTarget",
	description = """
		A field read in place of the one before it, for a document that holds \
		no value there. A field inside a `nested` \
		[object field](https://exofind.dev/reference/field-types/#object) is \
		named by its dotted path and read one value at a time, with `when` \
		saying which values count.""",
	examples = FallbackTarget.EXAMPLE
)
public record FallbackTarget(
	/**
	 * The field, as named in the index definition.
	 */
	@Schema(
		description = "The field, as named in the index definition.",
		required = true,
		examples = "prices.amount"
	)
	String field,

	/**
	 * Clauses that must hold where the value is read.
	 */
	@Schema(description = """
		Clauses that must hold where the value is read: in the same value as \
		the field for a field inside a `nested` list, and for the document \
		otherwise. Inside a list it takes what a `nested` clause takes: \
		`field`, `text`, `and`, `or`, `not` and `boost`. A clause naming a \
		field outside the list returns `search:nested:field_not_inside`.""")
	List<Clause> when,

	/**
	 * Targets read instead where the document holds no value on this one, in
	 * order.
	 */
	@Schema(description = """
		Targets read instead, in order, where the document holds no value on \
		this one. They are read after this target and before the next target \
		beside it.""")
	List<FallbackTarget> fallback
) {
	/** The example target, as the JSON a caller writes. */
	public static final String EXAMPLE = """
		{
		  "field": "prices.amount",
		  "when": [ { "field": "prices.list", "match": { "value": "store" } } ]
		}""";
}
