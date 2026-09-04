package se.l4.exofind.engine.index;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.util.BytesRef;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.factory.primitive.ObjectIntMaps;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.ImmutableMap;
import org.eclipse.collections.api.map.primitive.ImmutableObjectIntMap;
import org.eclipse.collections.api.set.MutableSet;

import se.l4.exofind.engine.errors.ErrorMessage;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.index.locales.Locales;
import se.l4.exofind.engine.index.schema.IndexSchema;
import se.l4.exofind.engine.index.settings.DeclaredValue;
import se.l4.exofind.engine.index.settings.FieldSettings;

/**
 * The values the search settings declare for fields of an index - an order
 * and labels per locale - compiled against one generation.
 *
 * <p>A facet answers the values a field holds, and the values alone say
 * neither how to order sizes nor what a code such as {@code BLK} reads as. A
 * declaration gives a value a place in {@link se.l4.exofind.engine.query.Facet.Order#DECLARED
 * declared order} and a label per locale. A facet answers the label of the
 * search locale next to each value, a facet searched by prefix finds a value
 * by its label as well, and a search in user mode reads a typed label as the
 * value it stands for - see {@link ValueReader}.
 *
 * <p>A label is resolved the way a locale specific field resolves a variant:
 * the search locale, matched as closely as the declared tags tell apart, and
 * the field's default locale where the search locale has no label. A value
 * with no label in either is answered without one.
 *
 * <p>Only a string field with {@code facet} and without {@code hierarchy}
 * holds values to declare. The settings outlive generations, so a field the
 * generation lacks or cannot answer for is skipped and carried in
 * {@link #skippedFields()} instead of failing the search.
 *
 * <p>Immutable once compiled and safe to share between threads; what a
 * locale resolves to is worked out once per locale and kept.
 */
public final class DeclaredValues {
	/**
	 * The most values one field may declare. Declaring is for the fields
	 * whose values need an order or a label, and a declaration is read on
	 * every search of the index, so a field is not declared value by value
	 * at the size of a catalogue.
	 */
	public static final int MAX_VALUES = 10_000;

	private static final ErrorType VALUES_UNSUPPORTED =
		ErrorType.withCode("index:settings:fields:values_unsupported")
			.withArguments("field")
			.withMessage(
				"Values can not be declared for `{{field}}`; declaring needs a string field"
					+ " with `facet` and without `hierarchy`"
			);

	private static final ErrorType VALUES_INVALID =
		ErrorType.withCode("index:settings:fields:values_invalid")
			.withArguments("field", "reason")
			.withMessage("The values declared for `{{field}}` can not be used: {{reason}}");

	private static final DeclaredValues NONE = new DeclaredValues(
		Maps.immutable.empty(),
		Lists.immutable.empty()
	);

	private final ImmutableMap<String, Field> fields;
	private final ListIterable<String> skippedFields;

	private DeclaredValues(ImmutableMap<String, Field> fields, ListIterable<String> skippedFields) {
		this.fields = fields;
		this.skippedFields = skippedFields;
	}

	/**
	 * Get the declarations of an index whose settings declare nothing.
	 *
	 * @return
	 */
	public static DeclaredValues none() {
		return NONE;
	}

	/**
	 * Compile the field settings of an index against one generation.
	 *
	 * @param settings
	 *   the settings by field name, as stored
	 * @param schema
	 *   the generation the values are answered from
	 * @return
	 */
	public static DeclaredValues compile(Map<String, FieldSettings> settings, IndexSchema schema) {
		var fields = Maps.mutable.<String, Field>empty();
		var skipped = Lists.mutable.<String>empty();

		for(var entry : settings.entrySet()) {
			if(entry.getValue().getValuesCount() == 0) {
				continue;
			}

			var name = entry.getKey();
			var field = fieldOf(schema, name);
			if(field == null || !isDeclarable(field)) {
				skipped.add(name);
				continue;
			}

			var values = Lists.mutable.<Value>empty();
			for(var declared : entry.getValue().getValuesList()) {
				values.add(new Value(
					declared.getValue(),
					declared.hasOrder() ? declared.getOrder() : null,
					Maps.immutable.ofMap(declared.getLabelsMap())
				));
			}

			var defaultLocale = field.getDefaultLocale() != null
				? field.getDefaultLocale()
				: Locales.getDefault().getLocale();

			fields.put(name, new Field(values.toImmutable(), defaultLocale));
		}

		if(fields.isEmpty() && skipped.isEmpty()) {
			return NONE;
		}

		return new DeclaredValues(fields.toImmutable(), skipped.toSortedList());
	}

	/**
	 * Validate the declared values of field settings against one generation.
	 * What passes here can still be skipped by a later generation - see
	 * {@link #compile} - so this is the check for storing settings, not for
	 * searching with them. A field the generation does not have is reported
	 * by {@link ValueDictionaries#validate} and left alone here.
	 *
	 * @param settings
	 *   the settings by field name, as they would be stored
	 * @param schema
	 *   the generation the index name answers from
	 * @param location
	 *   where the settings sit in what the caller is validating, for the
	 *   errors to point into it
	 * @return
	 *   what stops the settings, empty when this generation answers for all of
	 *   them
	 */
	public static ListIterable<ErrorMessage> validate(
		Map<String, FieldSettings> settings,
		IndexSchema schema,
		ObjectLocation location
	) {
		var errors = Lists.mutable.<ErrorMessage>empty();

		for(var entry : settings.entrySet()) {
			var declared = entry.getValue().getValuesList();
			if(declared.isEmpty()) {
				continue;
			}

			var name = entry.getKey();
			var at = location.forField(name).forField("values");

			var field = fieldOf(schema, name);
			if(field == null) {
				continue;
			}

			if(!isDeclarable(field)) {
				errors.add(VALUES_UNSUPPORTED.toMessage(at, "field", name));
				continue;
			}

			if(declared.size() > MAX_VALUES) {
				errors.add(VALUES_INVALID.toMessage(
					at,
					"field", name,
					"reason", "at most " + MAX_VALUES + " values can be declared"
				));
				continue;
			}

			var seen = Sets.mutable.<String>empty();
			for(var i = 0; i < declared.size(); i++) {
				validate(name, declared.get(i), at.forIndex(i), seen, errors);
			}
		}

		return errors;
	}

	private static void validate(
		String field,
		DeclaredValue declared,
		ObjectLocation at,
		MutableSet<String> seen,
		MutableList<ErrorMessage> errors
	) {
		if(!declared.hasValue() || declared.getValue().isBlank()) {
			errors.add(VALUES_INVALID.toMessage(
				at.forField("value"),
				"field", field,
				"reason", "a value is required"
			));
			return;
		}

		if(!seen.add(declared.getValue())) {
			errors.add(VALUES_INVALID.toMessage(
				at.forField("value"),
				"field", field,
				"reason", "`" + declared.getValue() + "` is declared twice"
			));
		}

		for(var label : declared.getLabelsMap().entrySet()) {
			var tag = label.getKey();
			if(!isLocaleTag(tag)) {
				errors.add(VALUES_INVALID.toMessage(
					at.forField("labels"),
					"field", field,
					"reason", "`" + tag + "` is not a BCP-47 tag in its canonical form"
				));
			} else if(label.getValue().isBlank()) {
				errors.add(VALUES_INVALID.toMessage(
					at.forField("labels").forField(tag),
					"field", field,
					"reason", "the label for `" + tag + "` is blank"
				));
			}
		}
	}

	/**
	 * Whether a tag is one a search locale resolves against - well-formed,
	 * and spelled the way {@link Locale#toLanguageTag()} spells it, so that
	 * {@code sv-SE} typed as {@code SV-se} is refused rather than never found.
	 */
	private static boolean isLocaleTag(String tag) {
		if(tag == null || tag.isEmpty()) {
			return false;
		}

		var canonical = Locale.forLanguageTag(tag).toLanguageTag();
		return !"und".equals(canonical) && canonical.equals(tag);
	}

	private static se.l4.exofind.engine.index.schema.Field fieldOf(IndexSchema schema, String name) {
		var nested = schema.getNestedField(name);
		if(nested.isPresent()) {
			return nested.get().field();
		}

		return schema.getField(name).orElse(null);
	}

	/**
	 * Whether a field holds values a declaration can order and label: a
	 * facet over strings, whose values stand on their own rather than being
	 * levels of a tree.
	 */
	private static boolean isDeclarable(se.l4.exofind.engine.index.schema.Field field) {
		var def = field.getDef();
		return def.getType().hasString()
			&& !def.getType().getString().hasHierarchy()
			&& def.hasFacet();
	}

	/**
	 * Get whether no field declares values.
	 *
	 * @return
	 */
	public boolean isEmpty() {
		return fields.isEmpty();
	}

	/**
	 * Get the fields the settings declare values for that this generation
	 * cannot answer for, sorted. Empty when every declared field is answered.
	 *
	 * @return
	 */
	public ListIterable<String> skippedFields() {
		return skippedFields;
	}

	/**
	 * Get the declared values of a field as a search in the given locale
	 * reads them.
	 *
	 * @param field
	 *   the field, named as the definition names it
	 * @param locale
	 *   the locale of the search, or {@code null} where it named none
	 * @return
	 *   the values, or {@code null} when the field declares none
	 */
	public Localized localized(String field, String locale) {
		var declared = fields.get(field);
		return declared == null ? null : declared.inLocale(locale);
	}

	/**
	 * One declared value.
	 *
	 * @param value
	 *   the value as the field stores it
	 * @param order
	 *   where it sits in declared order, or {@code null} for after every
	 *   value that has one
	 * @param labels
	 *   what a person reads instead of the value, by locale tag
	 */
	public record Value(String value, Integer order, ImmutableMap<String, String> labels) {
	}

	/**
	 * The declared values of one field, before a locale picks the labels.
	 */
	private static final class Field {
		private final ImmutableList<Value> values;
		private final String defaultLocale;

		/**
		 * What each search locale resolves to, keyed by the tag the search
		 * gave - the empty string for none. A deployment searches in a
		 * handful of locales, so this stays small.
		 */
		private final ConcurrentHashMap<String, Localized> localized = new ConcurrentHashMap<>();

		Field(ImmutableList<Value> values, String defaultLocale) {
			this.values = values;
			this.defaultLocale = defaultLocale;
		}

		Localized inLocale(String locale) {
			return localized.computeIfAbsent(locale == null ? "" : locale, this::resolve);
		}

		private Localized resolve(String locale) {
			var labels = Maps.mutable.<String, String>empty();
			var orders = ObjectIntMaps.mutable.<String>empty();

			for(var value : values) {
				var label = labelIn(value, locale.isEmpty() ? null : locale);
				if(label == null) {
					label = labelIn(value, defaultLocale);
				}

				if(label != null) {
					labels.put(value.value(), label);
				}

				if(value.order() != null) {
					orders.put(value.value(), value.order());
				}
			}

			return new Localized(labels.toImmutable(), orders.toImmutable());
		}

		private static String labelIn(Value value, String locale) {
			if(locale == null || value.labels().isEmpty()) {
				return null;
			}

			var tags = value.labels().keysView().toSet();
			return Locales.resolve(locale, tags)
				.map(tag -> value.labels().get(tag))
				.orElse(null);
		}
	}

	/**
	 * The declared values of one field as a search in one locale reads them:
	 * the label of each value that has one, and the place of each value that
	 * has an order.
	 *
	 * <p>Safe to share between searches.
	 */
	public static final class Localized {
		private final ImmutableMap<String, String> labels;
		private final ImmutableObjectIntMap<String> orders;

		/**
		 * The labels folded the way one Lucene field folds its values, keyed
		 * by the name of that field. The analyzer of a field is decided by
		 * the definition, so the same name always folds the same way for as
		 * long as this is kept.
		 */
		private final ConcurrentHashMap<String, FoldedLabels> folded = new ConcurrentHashMap<>();

		private Localized(ImmutableMap<String, String> labels, ImmutableObjectIntMap<String> orders) {
			this.labels = labels;
			this.orders = orders;
		}

		/**
		 * Get the label of a value.
		 *
		 * @param value
		 *   the value as the field stores it
		 * @return
		 *   the label, or {@code null} when the value has none in this locale
		 *   or the field's default one
		 */
		public String labelOf(String value) {
			return labels.get(value);
		}

		/**
		 * Get whether any value has a declared order.
		 *
		 * @return
		 */
		public boolean hasOrders() {
			return orders.notEmpty();
		}

		/**
		 * Get whether a value has a declared order.
		 *
		 * @param value
		 *   the value as the field stores it
		 * @return
		 */
		public boolean isOrdered(String value) {
			return orders.containsKey(value);
		}

		/**
		 * Get the declared order of a value, lower first. Only meaningful for
		 * a value {@link #isOrdered} answers {@code true} for.
		 *
		 * @param value
		 *   the value as the field stores it
		 * @return
		 */
		public int orderOf(String value) {
			return orders.getIfAbsent(value, Integer.MAX_VALUE);
		}

		/**
		 * Get the values that have a label, in no particular order.
		 *
		 * @return
		 */
		public ImmutableMap<String, String> labels() {
			return labels;
		}

		/**
		 * Get the declared order of every value that has one.
		 *
		 * @return
		 */
		public ImmutableObjectIntMap<String> orders() {
			return orders;
		}

		/**
		 * Get the labels folded the way the given Lucene field folds its
		 * values, for comparing them with a folded prefix or span.
		 *
		 * @param field
		 *   the Lucene field the values were written under
		 * @param normalizer
		 *   what folds a value of that field
		 * @return
		 */
		public FoldedLabels folded(String field, Analyzer normalizer) {
			return folded.computeIfAbsent(field, key -> FoldedLabels.build(key, normalizer, labels));
		}
	}

	/**
	 * The labels of one field in one locale, folded the way the field folds
	 * its values, each with the value it stands for.
	 *
	 * <p>Read by a scan: a field declares labels for the values that need
	 * them rather than for a catalogue, so the scan is over tens of entries
	 * and at most {@link #MAX_VALUES}.
	 */
	public static final class FoldedLabels {
		/**
		 * One folded label with the value it stands for.
		 */
		private record Entry(BytesRef folded, String value) {
		}

		private final ImmutableList<Entry> entries;

		private FoldedLabels(ImmutableList<Entry> entries) {
			this.entries = entries;
		}

		private static FoldedLabels build(
			String field,
			Analyzer normalizer,
			ImmutableMap<String, String> labels
		) {
			var entries = Lists.mutable.<Entry>empty();
			labels.forEachKeyValue((value, label) ->
				entries.add(new Entry(fold(field, normalizer, label), value))
			);

			return new FoldedLabels(entries.toImmutable());
		}

		/**
		 * Fold one label. A label the analyzer cannot fold into one token is
		 * kept as it was given, the way {@link FoldedTerms} keeps a value.
		 */
		private static BytesRef fold(String field, Analyzer normalizer, String label) {
			try {
				return normalizer.normalize(field, label);
			} catch(IllegalStateException e) {
				return new BytesRef(label);
			}
		}

		/**
		 * Hand the value of every label whose folded form starts with the
		 * given bytes to the consumer.
		 *
		 * @param prefix
		 *   the folded prefix; empty selects every labelled value
		 * @param consumer
		 *   given each value once per label it has
		 */
		public void forEachStartingWith(BytesRef prefix, Consumer<String> consumer) {
			for(var entry : entries) {
				if(startsWith(entry.folded(), prefix)) {
					consumer.accept(entry.value());
				}
			}
		}

		/**
		 * Hand the value of every label whose folded form is exactly the
		 * given bytes to the consumer.
		 *
		 * @param folded
		 *   the folded span to find
		 * @param consumer
		 *   given each value once per label it has
		 */
		public void forEachEqualTo(BytesRef folded, Consumer<String> consumer) {
			for(var entry : entries) {
				if(entry.folded().bytesEquals(folded)) {
					consumer.accept(entry.value());
				}
			}
		}

		private static boolean startsWith(BytesRef bytes, BytesRef prefix) {
			if(bytes.length < prefix.length) {
				return false;
			}

			for(var i = 0; i < prefix.length; i++) {
				if(bytes.bytes[bytes.offset + i] != prefix.bytes[prefix.offset + i]) {
					return false;
				}
			}

			return true;
		}
	}
}
