package se.l4.exofind.engine.index;

import java.util.HashSet;

import org.apache.lucene.document.FieldType;
import org.eclipse.collections.api.RichIterable;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;

import se.l4.exofind.engine.index.locales.Locales;
import se.l4.exofind.engine.index.schema.Field;
import se.l4.exofind.engine.index.schema.ResourcesDef;

/**
 * The analyzed Lucene fields an index can write, and the empty entries that
 * keep them in every Lucene document.
 *
 * <p>Lucene scores with the norm of a field, a byte per document saying how
 * long the value was. It stores those norms as an array indexed by document
 * only when every document of the segment has the field. A segment where some
 * documents lack the field gets a sparse encoding instead, which scoring reads
 * through an iterator that has to seek to each document.
 *
 * <p>Most Lucene documents of this engine hold few of the analyzed fields: a
 * document with object fields is written as a block of one Lucene document per
 * object value plus the document itself, and an optional field is missing
 * wherever a document left it out. To keep the dense encoding, every Lucene
 * document written to an index carries an entry for every analyzed field of
 * the index, empty where the document has no text. An empty entry produces no
 * terms, so it does not match a query and does not count towards the document
 * frequency or the average field length the scoring reads.
 *
 * <p>Because of this a {@code FieldExistsQuery} on an analyzed field matches
 * every Lucene document of the index and says nothing about which documents
 * hold text. Ask a term or phrase query instead.
 *
 * <p>Fields whose name holds a wildcard are left out. Their Lucene names only
 * exist once a document gives them, so the norms of those fields stay sparse.
 *
 * <p>Instances are immutable and safe to share. Build one per definition
 * through {@link #of} and keep it, since it walks every field of the schema.
 */
public final class AnalyzedFields {
	private static final AnalyzedFields NONE =
		new AnalyzedFields(new String[0], new FieldType[0]);

	/**
	 * Where an analyzed Lucene field is reported while a definition is walked.
	 */
	@FunctionalInterface
	public interface Collector {
		/**
		 * Report one analyzed Lucene field.
		 *
		 * @param name
		 *   the full Lucene name, as {@link IndexEncounter#name(String)} builds
		 *   it
		 * @param shape
		 *   what the field is written with beyond its terms
		 */
		void add(String name, AnalyzingTextField.Shape shape);
	}

	private final String[] names;
	private final FieldType[] types;

	private AnalyzedFields(String[] names, FieldType[] types) {
		this.names = names;
		this.types = types;
	}

	/**
	 * Get the empty set, which pads nothing.
	 *
	 * @return
	 */
	public static AnalyzedFields none() {
		return NONE;
	}

	/**
	 * Collect the analyzed Lucene fields of a definition, asking every field
	 * type what it writes.
	 *
	 * <p>A locale specific field is asked once per locale it declares, because
	 * each locale is a Lucene field of its own.
	 *
	 * @param resources
	 *   what the index shares between fields
	 * @param highlightsInPostings
	 *   whether the offsets highlighting reads sit in the postings, which
	 *   decides how a highlightable field is written
	 * @param fields
	 *   every field of the definition, including the ones inside objects
	 * @return
	 */
	public static AnalyzedFields of(
		ResourcesDef resources,
		boolean highlightsInPostings,
		RichIterable<Field> fields
	) {
		var names = Lists.mutable.<String>empty();
		var types = Lists.mutable.<FieldType>empty();
		var seen = Sets.mutable.<String>empty();

		Collector collector = (name, shape) -> {
			if(seen.add(name)) {
				names.add(name);
				types.add(AnalyzingTextField.typeOf(shape));
			}
		};

		var encounter = new IndexEncounterImpl(resources, highlightsInPostings);
		for(var field : fields) {
			if(field.nameHasWildcard()) {
				continue;
			}

			encounter.updateValue(field.getName(), field.getDef());

			if(field.isLocaleSpecific()) {
				for(var locale : field.getLocales()) {
					// Declared locales are validated with the definition
					encounter.updateLocale(Locales.get(locale).orElseThrow());
					field.getType().collectAnalyzedFields(encounter, collector);
				}
			} else {
				encounter.updateLocale(Locales.getDefault());
				field.getType().collectAnalyzedFields(encounter, collector);
			}
		}

		if(names.isEmpty()) {
			return NONE;
		}

		return new AnalyzedFields(
			names.toArray(new String[names.size()]),
			types.toArray(new FieldType[types.size()])
		);
	}

	/**
	 * Add an empty entry to a Lucene document for every analyzed field it does
	 * not already hold. Call it once per Lucene document, after every value has
	 * been added and before the document is written.
	 *
	 * @param document
	 */
	public void padTo(org.apache.lucene.document.Document document) {
		if(names.length == 0) {
			return;
		}

		var present = new HashSet<String>();
		for(var field : document) {
			present.add(field.name());
		}

		for(var i = 0; i < names.length; i++) {
			if(!present.contains(names[i])) {
				document.add(AnalyzingTextField.empty(names[i], types[i]));
			}
		}
	}
}
