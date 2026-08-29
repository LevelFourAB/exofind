package se.l4.exofind.engine.api.v1alpha1.search;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.eclipse.collections.api.factory.Lists;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest.Hits;
import se.l4.exofind.engine.query.SortBy;
import se.l4.exofind.engine.query.SortKey;
import se.l4.exofind.engine.query.matchers.Matchers;

/**
 * Tests for the tokens paging hands out - that both kinds survive the round
 * trip, that tampering is noticed and that the sort fingerprint tells sorts
 * apart.
 */
public class SearchCursorTest {
	@Test
	public void testOffsetRoundTrip() {
		var cursor = new SearchCursor.Offset(1234, 40);

		var decoded = SearchCursor.decode(cursor.encode());

		assertThat(decoded, is(cursor));
	}

	@Test
	public void testKeysetRoundTrip() {
		var cursor = new SearchCursor.Keyset(
			1234,
			new SortKey(
				Lists.immutable.of(3.5f, 42, 7L, 2.25d, "name".getBytes(), null),
				17
			)
		);

		var decoded = SearchCursor.decode(cursor.encode());

		/*
		 * Bytes do not compare by content in a record, so the round trip is
		 * checked value by value.
		 */
		assertThat(decoded, is(instanceOf(SearchCursor.Keyset.class)));
		var keyset = (SearchCursor.Keyset) decoded;

		assertThat(keyset.fingerprint(), is(1234));
		assertThat(keyset.key().doc(), is(17));

		var values = keyset.key().values();
		assertThat(values.size(), is(6));
		assertThat(values.get(0), is(3.5f));
		assertThat(values.get(1), is(42));
		assertThat(values.get(2), is(7L));
		assertThat(values.get(3), is(2.25d));
		assertThat(new String((byte[]) values.get(4)), is("name"));
		assertThat(values.get(5), is(nullValue()));
	}

	@Test
	public void testGarbageIsRefused() {
		assertThrows(IllegalArgumentException.class, () -> SearchCursor.decode("not a cursor"));
	}

	@Test
	public void testTruncatedTokenIsRefused() {
		var token = new SearchCursor.Offset(1234, 40).encode();

		assertThrows(
			IllegalArgumentException.class,
			() -> SearchCursor.decode(token.substring(0, token.length() - 2))
		);
	}

	@Test
	public void testTruncatedKeysetTokenIsRefused() {
		var token = new SearchCursor.Keyset(
			1234,
			new SortKey(Lists.immutable.of((Object) "name".getBytes()), 17)
		).encode();

		assertThrows(
			IllegalArgumentException.class,
			() -> SearchCursor.decode(token.substring(0, token.length() - 2))
		);
	}

	@Test
	public void testEmptySortMeansScoreDescending() {
		/*
		 * An empty sort and writing the default out mean the same order, so
		 * a cursor issued under one has to be usable under the other.
		 */
		var empty = SearchCursor.fingerprintOf(Lists.immutable.empty());
		var explicit = SearchCursor.fingerprintOf(
			Lists.immutable.of(SortBy.score())
		);

		assertThat(empty, is(explicit));
	}

	@Test
	public void testDifferentSortsHaveDifferentFingerprints() {
		var score = SearchCursor.fingerprintOf(Lists.immutable.empty());
		var byName = SearchCursor.fingerprintOf(
			Lists.immutable.of(SortBy.field("name"))
		);
		var byNameDescending = SearchCursor.fingerprintOf(
			Lists.immutable.of(SortBy.field("name", SortBy.Order.DESCENDING))
		);

		assertThat(score, is(not(byName)));
		assertThat(byName, is(not(byNameDescending)));
	}

	@Test
	public void testWhatAHitStandsForIsPartOfTheFingerprint() {
		var sort = Lists.immutable.of(SortBy.score());

		var documents = SearchCursor.fingerprintOf(sort);
		var values = SearchCursor.fingerprintOf(sort, new Hits("variants"));
		var otherValues = SearchCursor.fingerprintOf(sort, new Hits("chunks"));

		// A position among values names nothing among documents
		assertThat(documents, is(not(values)));
		assertThat(values, is(not(otherValues)));

		// Hits that are documents fingerprint exactly as they always have
		assertThat(SearchCursor.fingerprintOf(sort, null), is(documents));
	}

	@Test
	public void testExpandingOnlySomeDocumentsIsItsOwnFingerprint() {
		var sort = Lists.immutable.of(SortBy.score());
		var when = Lists.immutable.<Query>of(
			Query.field("split", Matchers.equalTo(true))
		);

		var values = SearchCursor.fingerprintOf(sort, new Hits("variants"));
		var mixed = SearchCursor.fingerprintOf(
			sort,
			new Hits("variants", null, when)
		);

		/*
		 * The same path expanded for every document numbers its hits
		 * differently, so a position taken under one names nothing under the
		 * other.
		 */
		assertThat(mixed, is(not(values)));
		assertThat(mixed, is(not(SearchCursor.fingerprintOf(sort))));
	}

	@Test
	public void testWhichDocumentsExpandIsNotPartOfTheFingerprint() {
		var sort = Lists.immutable.of(SortBy.score());

		/*
		 * Which documents expand decides which hits there are, not the shape
		 * of the key naming one - the same as the filters of a search, which
		 * are not fingerprinted either.
		 */
		var one = SearchCursor.fingerprintOf(sort, new Hits(
			"variants",
			null,
			Lists.immutable.of(Query.field("split", Matchers.equalTo(true)))
		));
		var other = SearchCursor.fingerprintOf(sort, new Hits(
			"variants",
			null,
			Lists.immutable.of(Query.field("inStock", Matchers.equalTo(true)))
		));

		assertThat(one, is(other));
	}
}
