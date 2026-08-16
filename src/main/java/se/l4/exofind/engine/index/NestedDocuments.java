package se.l4.exofind.engine.index;

import java.io.IOException;

import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.ConstantScoreScorerSupplier;
import org.apache.lucene.search.ConstantScoreWeight;
import org.apache.lucene.search.FieldExistsQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.ScorerSupplier;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.Weight;
import org.apache.lucene.search.join.BitSetProducer;
import org.apache.lucene.util.BitSetIterator;
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
	 * Get the clause that narrows a search to the documents of the index, to
	 * be added beside what the search is looking for.
	 *
	 * Says the same thing {@link #parentsQuery()} says, and is answered in the
	 * opposite way. That one names the values of object fields and leaves them
	 * out, and a clause that leaves documents out is answered by scoring the
	 * stretches between them - which, in an index where every document is
	 * followed by the values of its object fields, are one document long. A
	 * search that ranks recomputes what its clauses could still score for
	 * every stretch, so it pays that for every value of every object field in
	 * the index, however few documents it matches.
	 *
	 * This hands over the documents themselves instead, as a set built once
	 * per segment, and a search walks it alongside its other clauses the way
	 * it walks any other condition.
	 *
	 * @param parents
	 *   the documents of the index per segment, which is what builds the set
	 *   and what keeps it
	 * @return
	 */
	static Query parentsFilter(BitSetProducer parents) {
		return new ParentsFilter(parents);
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

	/**
	 * The documents of the index, read off a {@link BitSetProducer} rather
	 * than looked for again. See {@link #parentsFilter}.
	 */
	private static final class ParentsFilter extends Query {
		private final BitSetProducer parents;

		ParentsFilter(BitSetProducer parents) {
			this.parents = parents;
		}

		@Override
		public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) {
			return new ConstantScoreWeight(this, boost) {
				@Override
				public ScorerSupplier scorerSupplier(LeafReaderContext context)
					throws IOException
				{
					var documents = parents.getBitSet(context);
					if(documents == null) {
						// A segment holding nothing but values of object fields
						return null;
					}

					return ConstantScoreScorerSupplier.fromIterator(
						new BitSetIterator(documents, documents.approximateCardinality()),
						score(),
						scoreMode,
						context.reader().maxDoc()
					);
				}

				@Override
				public boolean isCacheable(LeafReaderContext ctx) {
					/*
					 * The set is already kept per segment by the producer, so
					 * the query cache would only be holding a second copy of
					 * what is there.
					 */
					return false;
				}
			};
		}

		@Override
		public void visit(QueryVisitor visitor) {
			visitor.visitLeaf(this);
		}

		@Override
		public boolean equals(Object obj) {
			return sameClassAs(obj) && parents.equals(((ParentsFilter) obj).parents);
		}

		@Override
		public int hashCode() {
			return classHash() * 31 + parents.hashCode();
		}

		@Override
		public String toString(String field) {
			return "ParentsFilter(" + parents + ")";
		}
	}
}
