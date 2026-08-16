package se.l4.exofind.engine.index;

import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.FieldExistsQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.BytesRef;

/**
 * How the values of an object field are told apart from the documents of the
 * index inside Lucene.
 *
 * Every value of an object field is written as a Lucene document of its own,
 * right before the document it belongs to, and marked with
 * {@link FieldNames#NESTED} carrying the name of the object field. The mark is
 * what everything else is built on: a search of the index only answers with
 * documents that lack it, and a {@code nested} clause runs against the
 * documents of one path and joins what it finds to their parents. Indexing and
 * querying have to agree on all of that, which is why it is written down here
 * rather than where it is used.
 *
 * Documents indexed before an index had object fields carry no mark, so they
 * count as documents of the index without being touched.
 */
final class NestedDocuments {
	private NestedDocuments() {
	}

	/**
	 * Mark a Lucene document as holding one value of an object field.
	 *
	 * The mark is written both as a term, which is what scopes a {@code nested}
	 * clause to its path, and as doc values, which is what
	 * {@link #parentsQuery()} finds documents by the absence of.
	 *
	 * @param document
	 * @param path
	 *   name of the object field the value belongs to
	 */
	static void mark(org.apache.lucene.document.Document document, String path) {
		document.add(new StringField(FieldNames.NESTED, path, org.apache.lucene.document.Field.Store.NO));
		document.add(new SortedDocValuesField(FieldNames.NESTED, new BytesRef(path)));
	}

	/**
	 * Get the query matching the documents of the index - everything that is
	 * not a value of some object field.
	 *
	 * @return
	 */
	static Query parentsQuery() {
		return new BooleanQuery.Builder()
			.add(new MatchAllDocsQuery(), BooleanClause.Occur.FILTER)
			.add(new FieldExistsQuery(FieldNames.NESTED), BooleanClause.Occur.MUST_NOT)
			.build();
	}

	/**
	 * Get the query matching the values of one object field, whichever
	 * document they belong to.
	 *
	 * @param path
	 *   name of the object field
	 * @return
	 */
	static Query childrenQuery(String path) {
		return new TermQuery(new Term(FieldNames.NESTED, path));
	}
}
