package se.l4.exofind.engine.benchmark.grouping;

import java.util.List;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.DoubleDocValuesField;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FloatDocValuesField;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.NumericUtils;

/**
 * The Lucene fields every shape writes, so that a condition compiles to the
 * same terms and points whichever shape it runs against.
 *
 * <p>Values that only one shape can hold several of - a colour, a price - are
 * written as sorted set and sorted numeric doc values everywhere, so that
 * counting and ordering read the same doc values in every shape and a
 * difference in timing is a difference in the layout rather than in the
 * encoding.
 */
final class Fields {
	static final String TITLE = "title";
	static final String DESCRIPTION = "description";
	static final String BRAND = "brand";
	static final String CATEGORY = "category";
	static final String RATING = "rating";

	static final String COLOR = "color";
	static final String SIZE = "size";
	static final String PRICE = "price";
	static final String STOCK = "stock";

	/**
	 * Doc values holding the price, which a shape keeping one price per
	 * document writes as a plain number and one keeping several writes as a set
	 * of them - the two encodings {@link org.apache.lucene.search.SortField} and
	 * {@link org.apache.lucene.search.SortedNumericSortField} respectively read.
	 */
	static final String PRICE_SORT = "priceSort";

	/** Doc values holding which product a document belongs to, or is. */
	static final String PRODUCT = "product";

	/** Points over the same identifier, for selecting products by a set of them. */
	static final String PRODUCT_POINT = "productPoint";

	/** Doc values holding which variant a document is. */
	static final String VARIANT = "variant";

	/**
	 * Names the product a document belongs to, as the term a rewrite of that
	 * product replaces it by. Carried by every document of a block, so that
	 * replacing a product by it replaces the whole block rather than leaving
	 * its variants behind.
	 */
	static final String PRODUCT_KEY = "productKey";

	/** Names the variant a document is, as the term a rewrite replaces it by. */
	static final String VARIANT_KEY = "variantKey";

	/** Marks a Lucene document as a variant living inside its product's block. */
	static final String NESTED = "_nested";

	private Fields() {
	}

	/**
	 * Write the text, refinements and identifier of a product.
	 */
	static void product(Document document, Catalog.Product product) {
		document.add(new TextField(TITLE, product.title(), Field.Store.NO));
		document.add(new TextField(DESCRIPTION, product.description(), Field.Store.NO));
		document.add(new StringField(BRAND, product.brand(), Field.Store.NO));
		document.add(new SortedDocValuesField(BRAND, new BytesRef(product.brand())));
		document.add(new StringField(CATEGORY, product.category(), Field.Store.NO));
		document.add(new FloatDocValuesField(RATING, product.rating()));

		identify(document, product);
	}

	/**
	 * Write only the identifier of a product, for a document that carries a
	 * variant rather than the product itself.
	 */
	static void identify(Document document, Catalog.Product product) {
		document.add(new NumericDocValuesField(PRODUCT, product.id()));
		document.add(new LongPoint(PRODUCT_POINT, product.id()));
		name(document, product);
	}

	/**
	 * Write only the term a rewrite of a product replaces its documents by.
	 */
	static void name(Document document, Catalog.Product product) {
		document.add(
			new StringField(PRODUCT_KEY, Long.toString(product.id()), Field.Store.NO)
		);
	}

	/**
	 * Write the values of one variant onto a document holding that variant
	 * alone, so its price is one number the document can be ordered by.
	 */
	static void variant(Document document, Catalog.Variant variant) {
		matchable(document, variant);
		document.add(new DoubleDocValuesField(PRICE_SORT, variant.price()));
		document.add(new NumericDocValuesField(VARIANT, variant.id()));
		document.add(
			new StringField(VARIANT_KEY, Long.toString(variant.id()), Field.Store.NO)
		);
	}

	/**
	 * Write the values of every variant of a product onto one document, which
	 * is what leaves nothing saying which value belonged with which.
	 */
	static void variants(Document document, List<Catalog.Variant> variants) {
		for(var variant : variants) {
			matchable(document, variant);
			document.add(new SortedNumericDocValuesField(
				PRICE_SORT,
				NumericUtils.doubleToSortableLong(variant.price())
			));
		}
	}

	private static void matchable(Document document, Catalog.Variant variant) {
		document.add(new StringField(COLOR, variant.color(), Field.Store.NO));
		document.add(new SortedSetDocValuesField(COLOR, new BytesRef(variant.color())));

		for(var size : variant.sizes()) {
			document.add(new StringField(SIZE, size, Field.Store.NO));
		}

		document.add(new DoublePoint(PRICE, variant.price()));
		document.add(new IntPoint(STOCK, variant.stock()));
	}
}
