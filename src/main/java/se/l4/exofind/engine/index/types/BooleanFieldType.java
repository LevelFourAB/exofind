package se.l4.exofind.engine.index.types;

import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.BytesRef;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ListIterable;

import se.l4.exofind.engine.errors.ErrorMessage;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.index.FacetCounter;
import se.l4.exofind.engine.index.FieldNames;
import se.l4.exofind.engine.index.IndexEncounter;
import se.l4.exofind.engine.index.IndexFieldUsageException;
import se.l4.exofind.engine.index.IndexInvalidQueryTypeException;
import se.l4.exofind.engine.index.IndexInvalidQueryValueException;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.ResourcesDef;
import se.l4.exofind.engine.index.schema.SortConfig;
import se.l4.exofind.engine.query.matchers.AnyMatcher;
import se.l4.exofind.engine.query.matchers.EqualsMatcher;
import se.l4.exofind.engine.query.matchers.InMatcher;
import se.l4.exofind.engine.query.matchers.Matcher;

public class BooleanFieldType implements FieldType {
	private static final org.apache.lucene.document.FieldType INDEX_TYPE = createFieldType();
	private static final BytesRef TRUE = new BytesRef("T");
	private static final BytesRef FALSE = new BytesRef("F");

	private static final ErrorType COLLATION_NOT_SUPPORTED = ErrorType
		.withCode("index:field:sort:collation_not_supported")
		.withMessage("Collation means nothing when sorting a boolean field");

	protected static org.apache.lucene.document.FieldType createFieldType() {
		var ft = new org.apache.lucene.document.FieldType();
		ft.setIndexOptions(IndexOptions.DOCS);
		ft.setTokenized(false);
		ft.setOmitNorms(true);
		ft.freeze();
		return ft;
	}

	@Override
	public boolean isSortingSupported() {
		return true;
	}

	@Override
	public boolean isDocValuesSupported() {
		return true;
	}

	@Override
	public ListIterable<ErrorMessage> validate(
		ObjectLocation location,
		FieldDef def,
		ResourcesDef resources
	) {
		var errors = Lists.mutable.<ErrorMessage>empty();

		if(def.getSort().hasCollation()) {
			errors.add(COLLATION_NOT_SUPPORTED.toMessage(location));
		}

		return errors;
	}

	@Override
	public Iterable<? extends IndexableField> createFields(
		IndexEncounter encounter,
		Object value0
	) {
		var results = Lists.mutable.<IndexableField>empty();

		var value = ((Boolean) value0) ? TRUE : FALSE;

		if(encounter.isFiltered()) {
			var field = new Field(
				encounter.name(FieldNames.FILTER),
				value,
				INDEX_TYPE
			);
			results.add(field);
		}

		if(encounter.isStored()) {
			var field = new StoredField(
				encounter.name(FieldNames.STORED),
				value
			);
			results.add(field);
		}

		if(encounter.isSorted()) {
			var field = new SortedDocValuesField(
				encounter.name(FieldNames.SORT),
				value
			);
			results.add(field);
		}

		if(encounter.isStoreDocValues()) {
			var field = new SortedSetDocValuesField(
				encounter.name(FieldNames.VALUES),
				value
			);
			results.add(field);
		}

		return results;
	}

	@Override
	public Object readStored(IndexEncounter encounter, IndexableField field) {
		return TRUE.equals(field.binaryValue());
	}

	@Override
	public Query createQuery(IndexEncounter encounter, Matcher matcher) {
		if(matcher instanceof EqualsMatcher m) {
			return new TermQuery(
				new Term(filterName(encounter), booleanValue(encounter, m.value()))
			);
		}

		if(matcher instanceof InMatcher m) {
			/*
			 * A list of both values says nothing beyond having a value, but a
			 * filter someone built out of checkboxes can end up here and is
			 * cheaper to answer than to argue with.
			 */
			var builder = new BooleanQuery.Builder();
			for(var value : m.values()) {
				builder.add(
					new TermQuery(new Term(filterName(encounter), booleanValue(encounter, value))),
					BooleanClause.Occur.SHOULD
				);
			}

			return builder.build();
		}

		if(matcher instanceof AnyMatcher) {
			// Either term is a value, and there are only the two
			var name = filterName(encounter);
			return new BooleanQuery.Builder()
				.add(new TermQuery(new Term(name, TRUE)), BooleanClause.Occur.SHOULD)
				.add(new TermQuery(new Term(name, FALSE)), BooleanClause.Occur.SHOULD)
				.build();
		}

		throw new IndexInvalidQueryTypeException("boolean", matcher.id());
	}

	@Override
	public FacetCounter createFacetCounter(IndexEncounter encounter) {
		return FacetCounter.overStrings(
			encounter.name(FieldNames.VALUES),
			value -> TRUE.utf8ToString().equals(value),
			null
		);
	}

	/**
	 * Get the name of the field values are looked up in, refusing fields that
	 * were never written for it.
	 *
	 * @param encounter
	 * @return
	 */
	private static String filterName(IndexEncounter encounter) {
		if(!encounter.isFiltered()) {
			throw new IndexFieldUsageException(encounter.getFieldName(), "filter");
		}

		return encounter.name(FieldNames.FILTER);
	}

	/**
	 * Get a value as the term this type writes it as.
	 *
	 * @param encounter
	 * @param value
	 * @return
	 */
	private static BytesRef booleanValue(IndexEncounter encounter, Object value) {
		if(value instanceof Boolean b) {
			return b ? TRUE : FALSE;
		}

		throw new IndexInvalidQueryValueException(encounter.getFieldName(), "boolean");
	}

	@Override
	public SortField createSortField(IndexEncounter encounter, boolean ascending) {
		// Lucene takes whether to reverse, which is the opposite of ascending
		var field = new SortField(
			encounter.name(FieldNames.SORT),
			SortField.Type.STRING,
			!ascending
		);

		field.setMissingValue(
			encounter.getSortConfig().getMissing() == SortConfig.Missing.MISSING_FIRST
				? SortField.STRING_FIRST
				: SortField.STRING_LAST
		);

		return field;
	}
}
