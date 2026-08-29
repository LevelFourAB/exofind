package se.l4.exofind.engine.api.v1alpha1.admin;

import java.util.ArrayList;
import java.util.List;

import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexDefinition;
import se.l4.exofind.engine.errors.EngineException;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.index.schema.ResourcesDef;

/**
 * Translates the rules of a synonym set between the form the API uses and the
 * one the engine stores.
 *
 * <p>Split out of {@link IndexDefinitionMapper} because the same rules appear
 * in two places: in the resources of a definition, where they widen a value as
 * it is indexed, and in the search settings of an index, where they widen what
 * a search asks for. A set is the same set on either side, so both go through
 * here and the two can never read one differently.
 *
 * <p>Which error a rule of no clear kind is refused with belongs to the caller,
 * as the two sides answer for different parts of a request.
 */
final class SynonymsMapper {
	private SynonymsMapper() {
	}

	/**
	 * Convert the rules of a set received over the API into the ones to store.
	 *
	 * @param name
	 *   name of the set, for saying which one a bad rule is in
	 * @param rules
	 *   the rules, {@code null} for a set that holds none
	 * @param invalidRule
	 *   what to refuse a rule that is not exactly one kind with, taking the
	 *   name of the set as its {@code name} argument
	 * @return
	 */
	static ResourcesDef.SynonymsResource toStored(
		String name,
		List<IndexDefinition.Resources.Synonyms.Rule> rules,
		ErrorType invalidRule
	) {
		var builder = ResourcesDef.SynonymsResource.newBuilder();

		if(rules != null) {
			for(var rule : rules) {
				if((rule.equivalent() == null) == (rule.mapping() == null)) {
					throw new EngineException(invalidRule, "name", name);
				}

				var stored = ResourcesDef.SynonymsResource.Rule.newBuilder();
				if(rule.equivalent() != null) {
					stored.setEquivalent(
						ResourcesDef.SynonymsResource.Rule.Equivalent.newBuilder()
							.addAllTerms(rule.equivalent())
					);
				}
				if(rule.mapping() != null) {
					var mapping = ResourcesDef.SynonymsResource.Rule.Mapping.newBuilder();
					if(rule.mapping().from() != null) {
						mapping.addAllFrom(rule.mapping().from());
					}
					if(rule.mapping().to() != null) {
						mapping.addAllTo(rule.mapping().to());
					}
					stored.setMapping(mapping);
				}

				builder.addRules(stored);
			}
		}

		return builder.build();
	}

	/**
	 * Convert the rules of a stored set into what the API describes them as.
	 *
	 * @param resource
	 * @return
	 */
	static List<IndexDefinition.Resources.Synonyms.Rule> toApi(
		ResourcesDef.SynonymsResource resource
	) {
		var rules = new ArrayList<IndexDefinition.Resources.Synonyms.Rule>();

		for(var rule : resource.getRulesList()) {
			List<String> equivalent = null;
			if(rule.hasEquivalent()) {
				equivalent = List.copyOf(rule.getEquivalent().getTermsList());
			}

			IndexDefinition.Resources.Synonyms.Rule.Mapping mapping = null;
			if(rule.hasMapping()) {
				mapping = new IndexDefinition.Resources.Synonyms.Rule.Mapping(
					List.copyOf(rule.getMapping().getFromList()),
					List.copyOf(rule.getMapping().getToList())
				);
			}

			rules.add(new IndexDefinition.Resources.Synonyms.Rule(equivalent, mapping));
		}

		return rules;
	}
}
