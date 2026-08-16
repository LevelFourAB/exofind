package se.l4.exofind.engine.index.schema;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.ImmutableMap;
import org.eclipse.collections.api.set.ImmutableSet;

import se.l4.exofind.engine.errors.ErrorMessage;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.analysis.AnalyzerChains;
import se.l4.exofind.engine.index.locales.Locales;
import se.l4.exofind.engine.index.types.FieldTypes;
import se.l4.exofind.engine.query.DecaySignal;
import se.l4.exofind.engine.query.RankingSignal;
import se.l4.exofind.engine.query.SaturationSignal;

/**
 * IndexSchema contains the schema for an index, such as the fields and facets.
 */
public class IndexSchema {
	private final ReadWriteLock lock;

	private ImmutableMap<String, Field> fields;
	private ImmutableList<Field> wildcardFields;
	private ImmutableMap<String, NestedField> nestedFields;

	/**
	 * The fields inside each object field, keyed by the name of the object -
	 * what a search naming no fields covers while it is inside a {@code nested}
	 * clause.
	 */
	private ImmutableMap<String, ImmutableList<Field>> nestedFieldsByPath;

	private Field primaryKey;
	private ImmutableSet<String> requiredFields;

	private ImmutableList<Field> fieldList;

	private boolean sourceStored;

	private ImmutableList<RankingConfig.TieBreaker> tieBreakers;

	private ImmutableList<RankingSignal> signals;

	/**
	 * The locales a field takes a value from for a locale it holds none in,
	 * or {@code null} when the index leaves those locales empty. Empty when
	 * the index falls back but named no chain, which means each field's own
	 * default locale.
	 */
	private ImmutableList<String> localeFallback;

	private ResourcesDef resources;

	private static ErrorType MULTIPLE_PRIMARY_KEYS =
		ErrorType.withCode("index:schema:multiple_primary_keys")
			.withMessage("Only a single primary key field is allowed");

	private static ErrorType TIE_BREAKER_UNKNOWN_FIELD =
		ErrorType.withCode("index:ranking:unknown_field")
			.withArguments("name")
			.withMessage("Ties can not be broken by field `{{name}}`, which is not defined");

	private static ErrorType TIE_BREAKER_WILDCARD_FIELD =
		ErrorType.withCode("index:ranking:wildcard_field")
			.withArguments("name")
			.withMessage(
				"Ties can not be broken by `{{name}}`, names with wildcards stand for several fields"
			);

	private static ErrorType TIE_BREAKER_NOT_SORTABLE =
		ErrorType.withCode("index:ranking:field_not_sortable")
			.withArguments("name")
			.withMessage(
				"Ties can not be broken by field `{{name}}`, which is not defined for sorting"
			);

	private static ErrorType TIE_BREAKER_DUPLICATE_FIELD =
		ErrorType.withCode("index:ranking:duplicate_field")
			.withArguments("name")
			.withMessage("Field `{{name}}` breaks ties more than once");

	private static ErrorType SIGNAL_UNKNOWN_FIELD =
		ErrorType.withCode("index:ranking:signal:unknown_field")
			.withArguments("name")
			.withMessage("Ranking can not read field `{{name}}`, which is not defined");

	private static ErrorType SIGNAL_WILDCARD_FIELD =
		ErrorType.withCode("index:ranking:signal:wildcard_field")
			.withArguments("name")
			.withMessage(
				"Ranking can not read `{{name}}`, names with wildcards stand for several fields"
			);

	private static ErrorType SIGNAL_NOT_SORTABLE =
		ErrorType.withCode("index:ranking:signal:field_not_sortable")
			.withArguments("name")
			.withMessage(
				"Ranking can not read field `{{name}}`, which is not defined for sorting"
			);

	private static ErrorType SIGNAL_SHAPE_NOT_SET =
		ErrorType.withCode("index:ranking:signal:shape_not_set")
			.withMessage(
				"A ranking signal has to say how the value it reads counts, such as `saturation`"
			);

	private static ErrorType SIGNAL_SHAPE_NOT_SUPPORTED =
		ErrorType.withCode("index:ranking:signal:shape_not_supported")
			.withArguments("name", "shape")
			.withMessage("Field `{{name}}` holds nothing that `{{shape}}` can be read from");

	private static ErrorType SIGNAL_INVALID_PIVOT =
		ErrorType.withCode("index:ranking:signal:invalid_pivot")
			.withMessage("The `pivot` of a saturation signal has to be a number above zero");

	private static ErrorType SIGNAL_INVALID_HALF_LIFE =
		ErrorType.withCode("index:ranking:signal:invalid_half_life")
			.withMessage("The `halfLife` of a decay signal has to be longer than nothing");

	private static ErrorType SIGNAL_INVALID_WEIGHT =
		ErrorType.withCode("index:ranking:signal:invalid_weight")
			.withMessage("The `weight` of a ranking signal can not be less than nothing");

	private static ErrorType FALLBACK_UNSUPPORTED_LOCALE =
		ErrorType.withCode("index:locale_fallback:unsupported_locale")
			.withArguments("locale")
			.withMessage(
				"Locale `{{locale}}` is fallen back to, which this version of the engine does not support"
			);

	private static ErrorType FALLBACK_DUPLICATE_LOCALE =
		ErrorType.withCode("index:locale_fallback:duplicate_locale")
			.withArguments("locale")
			.withMessage("Locale `{{locale}}` is fallen back to more than once");

	private static ErrorType FALLBACK_LOCALE_NOT_HELD =
		ErrorType.withCode("index:locale_fallback:locale_not_held")
			.withArguments("locale")
			.withMessage(
				"Locale `{{locale}}` is fallen back to, but no field of the index holds values in it"
			);

	private static ErrorType FALLBACK_WITHOUT_LOCALE_FIELDS =
		ErrorType.withCode("index:locale_fallback:no_locale_fields")
			.withMessage(
				"The index falls back between locales, but none of its fields is locale specific"
			);

	private static ErrorType FALLBACK_ENABLED_WITHOUT_INDEX =
		ErrorType.withCode("index:field:locales:fallback_without_index")
			.withArguments("name")
			.withMessage(
				"Field `{{name}}` takes part in locale fallback, but the index does not declare one"
			);

	private static ErrorType OBJECT_PATH_TAKEN =
		ErrorType.withCode("index:schema:object_path_taken")
			.withArguments("name")
			.withMessage(
				"Field `{{name}}` is defined both at the root and inside an object, and a path can only mean one of them"
			);

	private static ErrorType UNSUPPORTED_FEATURES =
		ErrorType.withCode("index:schema:unsupported_features")
			.withArguments("features")
			.withMessage(
				"The definition needs features this version of the engine does not have: {{features}}"
			);

	private static ErrorType SYNONYM_RULE_NOT_ONE_KIND =
		ErrorType.withCode("index:resources:synonyms:invalid_rule")
			.withMessage(
				"A synonym rule has to be exactly one kind - equivalent words, or a one way mapping"
			);

	private static ErrorType SYNONYM_RULE_TOO_FEW_WORDS =
		ErrorType.withCode("index:resources:synonyms:too_few_words")
			.withMessage("Equivalent synonyms need at least two words");

	private static ErrorType SYNONYM_RULE_ONE_SIDED =
		ErrorType.withCode("index:resources:synonyms:one_sided")
			.withMessage("A synonym mapping needs at least one word on each side");

	private static ErrorType SYNONYM_RULE_BLANK_WORD =
		ErrorType.withCode("index:resources:synonyms:blank_word")
			.withMessage("A synonym can not be blank");

	/**
	 * A field inside an object, addressed by the dotted path through it.
	 *
	 * @param path
	 *   name of the object field the field sits in
	 * @param field
	 *   the field itself, named by its full path
	 */
	public record NestedField(String path, Field field) {
	}

	public IndexSchema() {
		this.lock = new ReentrantReadWriteLock();
		this.fields = Maps.immutable.empty();
		this.wildcardFields = Lists.immutable.empty();
		this.nestedFields = Maps.immutable.empty();
		this.nestedFieldsByPath = Maps.immutable.empty();
		this.primaryKey = null;
		this.requiredFields = Sets.immutable.empty();
		this.fieldList = Lists.immutable.empty();
		this.sourceStored = true;
		this.tieBreakers = Lists.immutable.empty();
		this.signals = Lists.immutable.empty();
		this.localeFallback = null;
		this.resources = ResourcesDef.getDefaultInstance();
	}

	/**
	 * Get whether a definition asks for the document to be kept as it was
	 * given.
	 *
	 * Unset means it does. An index created by this version says so outright,
	 * so a definition that leaves it unset was written before there was
	 * anything to say - and keeping the document is what the engine does unless
	 * told otherwise.
	 *
	 * A mode this version does not know is also treated as keeping it, which is
	 * safe because it can only have been written by a version that named a
	 * feature for it, and a feature this one does not have keeps the index from
	 * being opened at all.
	 *
	 * @param definition
	 * @return
	 */
	public static boolean storesSource(IndexDef definition) {
		return definition.getSource() != IndexDef.SourceMode.SOURCE_MODE_NONE;
	}

	/**
	 * Validates the given index definition, checks field names, types, required
	 * fields, etc.
	 *
	 * @param definition
	 */
	private void validate(IndexDef definition) {
		var errors = Lists.mutable.<ErrorMessage>empty();

		/*
		 * Checked before the fields are, because a definition that needs a
		 * feature this build does not have would produce errors about fields
		 * that are perfectly valid where they were written.
		 */
		var unsupported = IndexFeatures.unsupportedIn(definition);
		if(!unsupported.isEmpty()) {
			throw new ValidationException(
				Lists.immutable.of(
					UNSUPPORTED_FEATURES.toMessage(
						ObjectLocation.root(),
						"features", unsupported.toSortedList().makeString(", ")
					)
				).toList()
			);
		}

		// Validate individual fields
		for(var entry : definition.getFieldsMap().entrySet()) {
			errors.addAllIterable(
				Field.validate(entry.getKey(), entry.getValue(), definition.getResources())
			);
		}

		/*
		 * The fields inside an object are addressed by the dotted path through
		 * it, so a root field on the same path would make the path ambiguous.
		 */
		for(var entry : definition.getFieldsMap().entrySet()) {
			if(entry.getValue().getType().getTypeCase() != FieldTypeDef.TypeCase.OBJECT) {
				continue;
			}

			for(var inner : entry.getValue().getType().getObject().getFieldsMap().keySet()) {
				var path = entry.getKey() + '.' + inner;
				if(definition.getFieldsMap().containsKey(path)) {
					errors.add(
						OBJECT_PATH_TAKEN.toMessage(
							ObjectLocation.root().forField(path),
							"name", path
						)
					);
				}
			}
		}

		// Check for multiple primary keys
		var primaryKeyCount = definition.getFieldsMap().values().stream()
			.filter(FieldDef::getPrimaryKey)
			.count();

		if(primaryKeyCount > 1) {
			errors.add(MULTIPLE_PRIMARY_KEYS.toMessage(ObjectLocation.root()));
		}

		validateRanking(definition, errors);
		validateResources(definition, errors);
		validateLocaleFallback(definition, errors);

		if(!errors.isEmpty()) {
			throw new ValidationException(errors);
		}
	}

	/**
	 * Validate what the index shares between fields. The chains here are
	 * checked the same way one on a field is, so a broken chain is refused
	 * wherever it sits; synonym rules are checked for saying something - a
	 * rule with too few words to relate can only be a mistake.
	 *
	 * @param definition
	 * @param errors
	 */
	private void validateResources(IndexDef definition, MutableList<ErrorMessage> errors) {
		var resources = definition.getResources();
		var location = ObjectLocation.root().forField("resources");

		for(var entry : resources.getAnalyzersMap().entrySet()) {
			AnalyzerChains.validate(
				location.forField("analyzers").forField(entry.getKey()),
				errors,
				entry.getValue(),
				resources
			);
		}

		for(var entry : resources.getSynonymsMap().entrySet()) {
			var rules = entry.getValue().getRulesList();
			for(var i = 0; i < rules.size(); i++) {
				validateSynonymRule(
					location.forField("synonyms").forField(entry.getKey())
						.forField("rules").forIndex(i),
					rules.get(i),
					errors
				);
			}
		}
	}

	private void validateSynonymRule(
		ObjectLocation location,
		ResourcesDef.SynonymsResource.Rule rule,
		MutableList<ErrorMessage> errors
	) {
		switch(rule.getRuleCase()) {
			case EQUIVALENT -> {
				var terms = rule.getEquivalent().getTermsList();
				if(terms.size() < 2) {
					errors.add(SYNONYM_RULE_TOO_FEW_WORDS.toMessage(location));
				}
				if(terms.stream().anyMatch(String::isBlank)) {
					errors.add(SYNONYM_RULE_BLANK_WORD.toMessage(location));
				}
			}
			case MAPPING -> {
				var mapping = rule.getMapping();
				if(mapping.getFromCount() == 0 || mapping.getToCount() == 0) {
					errors.add(SYNONYM_RULE_ONE_SIDED.toMessage(location));
				}
				if(mapping.getFromList().stream().anyMatch(String::isBlank)
					|| mapping.getToList().stream().anyMatch(String::isBlank)) {
					errors.add(SYNONYM_RULE_BLANK_WORD.toMessage(location));
				}
			}
			case RULE_NOT_SET -> errors.add(SYNONYM_RULE_NOT_ONE_KIND.toMessage(location));
		}
	}

	/**
	 * Validate how the index fills the locales a document holds no value in.
	 * Like the tie breakers this is a rule across fields - which locales are
	 * worth falling back to is decided by what the fields between them hold -
	 * so it is checked here rather than in {@link Field#validate}.
	 *
	 * A chain entry no field holds values in is refused rather than skipped,
	 * because it can only be a mistake: the locale it names would never be
	 * taken from, and the definition would read as covering a locale it does
	 * not.
	 *
	 * @param definition
	 * @param errors
	 */
	private void validateLocaleFallback(IndexDef definition, MutableList<ErrorMessage> errors) {
		var held = Sets.mutable.<String>empty();
		var localeSpecific = false;

		for(var field : definition.getFieldsMap().values()) {
			if(!field.hasLocales()) {
				continue;
			}

			localeSpecific = true;

			var config = field.getLocales();
			held.add(
				config.hasDefaultLocale()
					? config.getDefaultLocale()
					: Locales.getDefault().getLocale()
			);
			held.addAll(config.getLocalesList());
		}

		if(!definition.hasLocaleFallback()) {
			/*
			 * A field asking to take part where there is nothing to take part
			 * in would quietly get no fallback at all. Turning it off is left
			 * alone - it asks for nothing beyond what already happens, and
			 * goes on saying what it means once the index does declare a
			 * chain.
			 */
			for(var entry : definition.getFieldsMap().entrySet()) {
				if(entry.getValue().getLocales().getFallback()
					== FieldDef.LocaleConfig.Fallback.FALLBACK_ENABLED) {
					errors.add(
						FALLBACK_ENABLED_WITHOUT_INDEX.toMessage(
							ObjectLocation.root().forField(entry.getKey()),
							"name", entry.getKey()
						)
					);
				}
			}

			return;
		}

		var location = ObjectLocation.root().forField("localeFallback");

		if(!localeSpecific) {
			errors.add(FALLBACK_WITHOUT_LOCALE_FIELDS.toMessage(location));
			return;
		}

		var seen = Sets.mutable.<String>empty();
		var chain = definition.getLocaleFallback().getChainList();
		for(var i = 0; i < chain.size(); i++) {
			var locale = chain.get(i);
			var entry = location.forField("chain").forIndex(i);

			if(!Locales.isSupported(locale)) {
				errors.add(FALLBACK_UNSUPPORTED_LOCALE.toMessage(entry, "locale", locale));
				continue;
			}

			if(!seen.add(locale)) {
				errors.add(FALLBACK_DUPLICATE_LOCALE.toMessage(entry, "locale", locale));
				continue;
			}

			if(!held.contains(locale)) {
				errors.add(FALLBACK_LOCALE_NOT_HELD.toMessage(entry, "locale", locale));
			}
		}
	}

	/**
	 * Validate the tie breakers of a definition. What they name is a rule
	 * across fields rather than of any one field, which is why the check lives
	 * here and not in {@link Field#validate}.
	 *
	 * @param definition
	 * @param errors
	 */
	private void validateRanking(IndexDef definition, MutableList<ErrorMessage> errors) {
		if(!definition.hasRanking()) {
			return;
		}

		var seen = Sets.mutable.<String>empty();
		var breakers = definition.getRanking().getTieBreakersList();
		for(var i = 0; i < breakers.size(); i++) {
			var breaker = breakers.get(i);
			var location = ObjectLocation.root()
				.forField("ranking")
				.forField("tieBreakers")
				.forIndex(i);

			var name = breaker.getField();
			if(name.contains("*")) {
				errors.add(TIE_BREAKER_WILDCARD_FIELD.toMessage(location, "name", name));
				continue;
			}

			var field = definition.getFieldsMap().get(name);
			if(field == null) {
				errors.add(TIE_BREAKER_UNKNOWN_FIELD.toMessage(location, "name", name));
				continue;
			}

			if(!field.hasSort()) {
				errors.add(TIE_BREAKER_NOT_SORTABLE.toMessage(location, "name", name));
			}

			if(!seen.add(name)) {
				errors.add(TIE_BREAKER_DUPLICATE_FIELD.toMessage(location, "name", name));
			}
		}

		var signals = definition.getRanking().getSignalsList();
		for(var i = 0; i < signals.size(); i++) {
			validateSignal(
				definition,
				signals.get(i),
				ObjectLocation.root().forField("ranking").forField("signals").forIndex(i),
				errors
			);
		}
	}

	/**
	 * Check that a signal names a field that holds a value it can read, and
	 * that the shape it reads it with is one the field can answer for.
	 *
	 * A signal on a field that never wrote doc values, or shaped in a way its
	 * type has no meaning for, would rank by nothing at all - which looks like
	 * a ranking that simply does not work rather than like a definition to fix.
	 *
	 * @param definition
	 * @param signal
	 * @param location
	 * @param errors
	 */
	private void validateSignal(
		IndexDef definition,
		RankingConfig.Signal signal,
		ObjectLocation location,
		MutableList<ErrorMessage> errors
	) {
		var usable = true;
		if(signal.hasWeight() && (!(signal.getWeight() >= 0) || !Float.isFinite(signal.getWeight()))) {
			errors.add(SIGNAL_INVALID_WEIGHT.toMessage(location));
			usable = false;
		}

		/*
		 * The shape is checked before the field, as what a field can answer for
		 * is a question about the two of them together.
		 */
		usable &= switch(signal.getShapeCase()) {
			case SATURATION -> {
				var pivot = signal.getSaturation().getPivot();
				if(!signal.getSaturation().hasPivot() || !(pivot > 0) || !Double.isFinite(pivot)) {
					errors.add(SIGNAL_INVALID_PIVOT.toMessage(location));
					yield false;
				}

				yield true;
			}
			case DECAY -> {
				if(!signal.getDecay().hasHalfLifeSeconds()
					|| signal.getDecay().getHalfLifeSeconds() <= 0) {
					errors.add(SIGNAL_INVALID_HALF_LIFE.toMessage(location));
					yield false;
				}

				yield true;
			}
			default -> {
				errors.add(SIGNAL_SHAPE_NOT_SET.toMessage(location));
				yield false;
			}
		};

		var name = signal.getField();
		if(name.contains("*")) {
			errors.add(SIGNAL_WILDCARD_FIELD.toMessage(location, "name", name));
			return;
		}

		var field = definition.getFieldsMap().get(name);
		if(field == null) {
			errors.add(SIGNAL_UNKNOWN_FIELD.toMessage(location, "name", name));
			return;
		}

		if(!field.hasSort()) {
			errors.add(SIGNAL_NOT_SORTABLE.toMessage(location, "name", name));
			return;
		}

		if(!usable) {
			// Nothing to ask the field about until the signal itself is usable
			return;
		}

		var shape = toSignal(signal);
		var supported = FieldTypes.forDef(field.getType())
			.map(type -> type.isRankingSupported(shape))
			.orElse(false);

		if(!supported) {
			errors.add(
				SIGNAL_SHAPE_NOT_SUPPORTED.toMessage(
					location,
					"name", name,
					"shape", shape.type()
				)
			);
		}
	}

	/**
	 * Get a stored signal as the one a search runs with. Only ever called for a
	 * signal that has been validated, so the shape is set and its numbers are
	 * usable.
	 *
	 * @param signal
	 * @return
	 */
	private static RankingSignal toSignal(RankingConfig.Signal signal) {
		var weight = signal.hasWeight() ? signal.getWeight() : 1f;

		return switch(signal.getShapeCase()) {
			case SATURATION -> new SaturationSignal(
				signal.getField(),
				signal.getSaturation().getPivot(),
				weight
			);
			case DECAY -> new DecaySignal(
				signal.getField(),
				Duration.ofSeconds(signal.getDecay().getHalfLifeSeconds()),
				weight
			);
			default -> throw new IllegalArgumentException(
				"A ranking signal has to name a shape, which validation is what makes sure of"
			);
		};
	}

	/**
	 * Set the definition for the index schema.
	 *
	 * @param definition
	 */
	public void setDefinition(IndexDef definition) {
		this.validate(definition);

		this.lock.writeLock().lock();
		try {
			var fields = Maps.mutable.<String, Field>empty();
			var wildcardFields = Lists.mutable.<Field>empty();
			var nestedFields = Maps.mutable.<String, NestedField>empty();
			var nestedFieldsByPath = Maps.mutable.<String, MutableList<Field>>empty();
			var requiredFields = Sets.mutable.<String>empty();
			Field primaryKey = null;

			var fieldDefs = definition.getFieldsMap();
			for(var entry : fieldDefs.entrySet()) {
				var fieldDef = entry.getValue();
				var field = new Field(entry.getKey(), fieldDef);

				fields.put(field.getName(), field);
				if(field.nameHasWildcard()) {
					wildcardFields.add(field);
				}

				if(field.isObject()) {
					var inside = Lists.mutable.<Field>empty();

					for(var inner : fieldDef.getType().getObject().getFieldsMap().entrySet()) {
						var path = field.getName() + '.' + inner.getKey();
						var innerField = new Field(path, inner.getValue());

						nestedFields.put(path, new NestedField(field.getName(), innerField));
						inside.add(innerField);
					}

					inside.sort((a, b) -> compareFieldNames(a.getName(), b.getName()));
					nestedFieldsByPath.put(field.getName(), inside);
				}

				if(field.getDef().getPrimaryKey()) {
					primaryKey = field;
					requiredFields.add(field.getName());
				} else if(field.getDef().getRequired()) {
					requiredFields.add(field.getName());
				}
			}

			// Sort wildcard fields by name to maintain consistent ordering
			wildcardFields.sort((a, b) -> compareFieldNames(a.getName(), b.getName()));

			this.fields = fields.toImmutable();
			this.nestedFields = nestedFields.toImmutable();
			this.nestedFieldsByPath = nestedFieldsByPath
				.collectValues((path, inside) -> inside.toImmutable())
				.toImmutable();
			this.fieldList = fields.valuesView()
				.toSortedList((a, b) -> compareFieldNames(a.getName(), b.getName()))
				.toImmutable();
			this.wildcardFields = wildcardFields.toImmutable();
			this.primaryKey = primaryKey;
			this.requiredFields = requiredFields.toImmutable();
			this.sourceStored = storesSource(definition);
			this.tieBreakers = Lists.immutable.ofAll(
				definition.getRanking().getTieBreakersList()
			);
			this.signals = Lists.immutable.ofAll(
				definition.getRanking().getSignalsList()
			).collect(IndexSchema::toSignal);
			this.localeFallback = definition.hasLocaleFallback()
				? Lists.immutable.ofAll(definition.getLocaleFallback().getChainList())
				: null;
			this.resources = definition.getResources();
		} finally {
			this.lock.writeLock().unlock();
		}
	}

	/**
	 * Get all of the fields that are available.
	 *
	 * @return
	 */
	public ImmutableList<Field> getFields() {
		this.lock.readLock().lock();
		try {
			return fieldList;
		} finally {
			this.lock.readLock().unlock();
		}
	}

	/**
	 * Get a field based on its name. An exact match wins over any wildcard
	 * pattern; otherwise the patterns are tried in the order
	 * {@link #compareFieldNames} defines and the first that matches is the
	 * field. Which pattern a name resolves to is contract - see the
	 * comparator for the rule.
	 *
	 * @param name
	 * @return
	 */
	public Optional<Field> getField(String name) {
		this.lock.readLock().lock();
		try {
			// First try exact match for better performance
			Field exactMatch = fields.get(name);
			if(exactMatch != null) {
				return Optional.of(exactMatch);
			}

			// If no exact match, check wildcard patterns
			for(var field : wildcardFields) {
				if(field.nameMatches(name)) {
					return Optional.of(field);
				}
			}

			return Optional.empty();
		} finally {
			this.lock.readLock().unlock();
		}
	}

	/**
	 * Get a field inside an object, by the dotted path through it - the field
	 * {@code price} inside the object {@code variants} is {@code
	 * variants.price}. Fields at the root are found through
	 * {@link #getField(String)}, never here.
	 *
	 * @param name
	 * @return
	 */
	public Optional<NestedField> getNestedField(String name) {
		this.lock.readLock().lock();
		try {
			return Optional.ofNullable(nestedFields.get(name));
		} finally {
			this.lock.readLock().unlock();
		}
	}

	/**
	 * Get the fields inside an object field, named by their dotted path. Empty
	 * for a name that is not an object field of the index.
	 *
	 * @param path
	 *   name of the object field
	 * @return
	 */
	public ImmutableList<Field> getNestedFields(String path) {
		this.lock.readLock().lock();
		try {
			var inside = nestedFieldsByPath.get(path);
			return inside == null ? Lists.immutable.empty() : inside;
		} finally {
			this.lock.readLock().unlock();
		}
	}

	/**
	 * Get whether any field of the index is an object, meaning documents write
	 * Lucene documents beyond their own and searching has to keep those out of
	 * the results.
	 *
	 * @return
	 */
	public boolean hasNestedFields() {
		this.lock.readLock().lock();
		try {
			return nestedFields.notEmpty();
		} finally {
			this.lock.readLock().unlock();
		}
	}

	/**
	 * Get the primary key for the index.
	 *
	 * @return
	 */
	public Optional<Field> getPrimaryKey() {
		this.lock.readLock().lock();
		try {
			return Optional.ofNullable(primaryKey);
		} finally {
			this.lock.readLock().unlock();
		}
	}

	/**
	 * Get whether documents are kept as they were given, so that they come back
	 * whole rather than only as far as their fields were stored.
	 *
	 * Only decides what is written from here on. Turning it off leaves the
	 * documents already indexed with their copy, and turning it on does not
	 * give one to documents indexed before, so reading has to cope with both
	 * either way.
	 *
	 * @return
	 */
	public boolean isSourceStored() {
		this.lock.readLock().lock();
		try {
			return sourceStored;
		} finally {
			this.lock.readLock().unlock();
		}
	}

	/**
	 * Get how this index breaks ties in the order of results. Appended after
	 * whatever primary ordering a search asks for, so they only ever decide
	 * the order within ties.
	 *
	 * @return
	 *   the tie breakers in the order they apply, empty when the index has no
	 *   opinion
	 */
	public ImmutableList<RankingConfig.TieBreaker> getTieBreakers() {
		this.lock.readLock().lock();
		try {
			return tieBreakers;
		} finally {
			this.lock.readLock().unlock();
		}
	}

	/**
	 * Get the values of the documents themselves that this index takes into
	 * their relevance. Multiplied into the score of every search that ranks by
	 * relevance, unless the search brings signals of its own.
	 *
	 * @return
	 *   the signals, empty when the index ranks by how well documents match
	 *   alone
	 */
	public ImmutableList<RankingSignal> getSignals() {
		this.lock.readLock().lock();
		try {
			return signals;
		} finally {
			this.lock.readLock().unlock();
		}
	}

	/**
	 * Get whether the index fills the locales a document holds no value in
	 * from another locale, so that a search in one still finds, orders and
	 * counts documents that were never translated into it.
	 *
	 * @return
	 */
	public boolean hasLocaleFallback() {
		this.lock.readLock().lock();
		try {
			return localeFallback != null;
		} finally {
			this.lock.readLock().unlock();
		}
	}

	/**
	 * Get the locales a field takes a value from for a locale it holds none
	 * in, in the order they are tried.
	 *
	 * The chain is the index's; a field skips the entries it holds no values
	 * in, so the same chain serves fields declaring different locales. An
	 * index that falls back without naming a chain sends every field to its
	 * own default locale.
	 *
	 * @param field
	 * @return
	 *   the locales to try, empty when this field is left with its gaps
	 */
	public ListIterable<String> getLocaleFallbackChain(Field field) {
		this.lock.readLock().lock();
		try {
			if(localeFallback == null
				|| !field.isLocaleSpecific()
				|| !field.isLocaleFallbackEnabled()) {
				return Lists.immutable.empty();
			}

			return localeFallback.isEmpty()
				? Lists.immutable.of(field.getDefaultLocale())
				: localeFallback;
		} finally {
			this.lock.readLock().unlock();
		}
	}

	/**
	 * Get what the index shares between fields - named analysis chains,
	 * stopword lists and synonym sets.
	 *
	 * @return
	 *   never {@code null}, empty when the index shares nothing
	 */
	public ResourcesDef getResources() {
		this.lock.readLock().lock();
		try {
			return resources;
		} finally {
			this.lock.readLock().unlock();
		}
	}

	/**
	 * Get the required fields for the index.
	 *
	 * @return
	 */
	public ImmutableSet<String> getRequiredFields() {
		this.lock.readLock().lock();
		try {
			return requiredFields;
		} finally {
			this.lock.readLock().unlock();
		}
	}

	/**
	 * Compare the names of two fields, taking into account wildcards.
	 *
	 * Wildcards are represented by {@code *}, treated as greater than every
	 * other character. This ordering is contract rather than cosmetics: it is
	 * the order {@link #getField} tries wildcard patterns in, so it decides
	 * which field a name matching several patterns resolves to. The pattern
	 * with the longer literal prefix before the first difference comes first
	 * ({@code a.b.*} before {@code a.*}), and on an equal prefix the shorter
	 * pattern does ({@code a.*} before {@code a.*x}). Documented in the
	 * README and pinned by IndexSchemaTest, so a change here fails loudly
	 * instead of quietly moving names to another field.
	 *
	 * @param a
	 * @param b
	 * @return
	 */
	private int compareFieldNames(String a, String b) {
		var aChars = a.toCharArray();
		var bChars = b.toCharArray();

		for(var i = 0; i < aChars.length && i < bChars.length; i++) {
			var aChar = aChars[i];
			var bChar = bChars[i];

			if(aChar == '*' && bChar != '*') {
				return 1;
			} else if(aChar != '*' && bChar == '*') {
				return -1;
			} else if(aChar != bChar) {
				return aChar - bChar;
			}
		}

		return aChars.length - bChars.length;
	}
}
