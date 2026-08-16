package se.l4.exofind.engine.index;

import java.util.Locale;
import java.util.Optional;

import se.l4.exofind.engine.index.locales.LocaleSupport;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.ResourcesDef;
import se.l4.exofind.engine.index.schema.SortConfig;

public interface IndexEncounter {
	/**
	 * Get the name of the field in the schema, as a caller would write it.
	 *
	 * Only used for telling someone which field a problem is with - what a
	 * value is written under is {@link #name(String)}.
	 *
	 * @return
	 */
	String getFieldName();

	/**
	 * Get the type of the field.
	 *
	 * @return
	 */
	FieldTypeDef getFieldType();

	/**
	 * Get the locale of the value.
	 *
	 * @return
	 */
	Optional<Locale> getLocale();

	/**
	 * Get the locale support for the field.
	 *
	 * @return
	 */
	LocaleSupport getLocaleSupport();

	/**
	 * Get what the index shares between fields - named analysis chains,
	 * stopword lists and synonym sets.
	 *
	 * @return
	 *   never {@code null}, empty when the index shares nothing
	 */
	ResourcesDef getResources();

	/**
	 * Get if the value is the primary key.
	 *
	 * @return
	 */
	boolean isPrimaryKey();

	/**
	 * Get if the value should be stored.
	 *
	 * @return
	 */
	boolean isStored();

	/**
	 * Get if the value can be filtered on.
	 *
	 * @return
	 */
	boolean isFiltered();

	/**
	 * Get if the value should be sortable.
	 *
	 * @return
	 */
	boolean isSorted();

	/**
	 * Get how sorting on this field behaves. Only meaningful when
	 * {@link #isSorted()} is {@code true}.
	 *
	 * @return
	 */
	SortConfig getSortConfig();

	/**
	 * Get if the value should be stored as doc values, so documents can be
	 * counted per value of the field.
	 *
	 * @return
	 */
	boolean isStoreDocValues();

	/**
	 * Get under which name this field is stored for searching.
	 *
	 * @return
	 */
	String name(String suffix);
}
