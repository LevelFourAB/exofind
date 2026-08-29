package se.l4.exofind.engine.index;

import java.util.Locale;
import java.util.Optional;

import se.l4.exofind.engine.index.analysis.SynonymOverlay;
import se.l4.exofind.engine.index.locales.LocaleSupport;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.ResourcesDef;
import se.l4.exofind.engine.index.schema.SortConfig;

public class IndexEncounterImpl implements IndexEncounter {
	private final ResourcesDef resources;
	private final boolean highlightsInPostings;
	private final SynonymOverlay querySynonyms;

	private Optional<Locale> locale;
	private LocaleSupport localeSupport;

	private String fieldName;
	private FieldDef fieldDef;

	private boolean forHighlighting;

	public IndexEncounterImpl(ResourcesDef resources, boolean highlightsInPostings) {
		this(resources, highlightsInPostings, SynonymOverlay.none());
	}

	public IndexEncounterImpl(
		ResourcesDef resources,
		boolean highlightsInPostings,
		SynonymOverlay querySynonyms
	) {
		this.resources = resources;
		this.highlightsInPostings = highlightsInPostings;
		this.querySynonyms = querySynonyms;
		this.locale = Optional.empty();
	}

	@Override
	public SynonymOverlay getQuerySynonyms() {
		return querySynonyms;
	}

	@Override
	public boolean isHighlightingInPostings() {
		return highlightsInPostings;
	}

	public void updateLocale(LocaleSupport localeSupport) {
		this.locale = Optional.of(localeSupport.getJavaLocale());
		this.localeSupport = localeSupport;
	}

	public void updateForHighlighting(boolean forHighlighting) {
		this.forHighlighting = forHighlighting;
	}

	@Override
	public boolean isForHighlighting() {
		return forHighlighting;
	}

	public void updateValue(String fieldName, FieldDef field) {
		this.fieldName = fieldName;
		this.fieldDef = field;
	}

	@Override
	public String getFieldName() {
		return fieldName;
	}

	@Override
	public FieldTypeDef getFieldType() {
		return fieldDef.getType();
	}

	@Override
	public Optional<Locale> getLocale() {
		return locale;
	}

	@Override
	public LocaleSupport getLocaleSupport() {
		return localeSupport;
	}

	@Override
	public ResourcesDef getResources() {
		return resources;
	}

	@Override
	public boolean isPrimaryKey() {
		return fieldDef.getPrimaryKey();
	}

	@Override
	public boolean isFiltered() {
		return fieldDef.hasFilter();
	}

	@Override
	public boolean isSorted() {
		return fieldDef.hasSort();
	}

	@Override
	public SortConfig getSortConfig() {
		return fieldDef.getSort();
	}

	@Override
	public boolean isStored() {
		return fieldDef.getStored() || fieldDef.getPrimaryKey();
	}

	@Override
	public boolean isStoreDocValues() {
		return fieldDef.hasFacet();
	}

	@Override
	public String name(String suffix) {
		/*
		 * A locale specific field writes each value under the locale it is
		 * in, so the same field holds a variant per locale; every other field
		 * writes under the no-locale slot whatever locale is current.
		 */
		return FieldNames.name(
			fieldName,
			fieldDef.hasLocales() ? localeSupport.getLocale() : null,
			suffix
		);
	}
}
