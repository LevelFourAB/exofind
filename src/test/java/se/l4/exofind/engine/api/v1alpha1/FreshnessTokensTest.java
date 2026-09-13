package se.l4.exofind.engine.api.v1alpha1;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.freshness.Freshness;
import se.l4.exofind.engine.index.IndexName;

/**
 * The token as clients see it: what goes in comes back out unchanged, and a
 * token the engine did not issue is refused with a code that says why.
 */
public class FreshnessTokensTest {
	@Test
	public void testEveryPartSurvivesTheRoundTrip() {
		var state = new Freshness("products", "2", 7, "\"ab12cd34\"");

		var decoded = FreshnessTokens.decode(FreshnessTokens.encode(state), null, "products");

		assertThat(decoded, is(state));
	}

	@Test
	public void testAbsentPartsStayAbsent() {
		var state = Freshness.ofGeneration(IndexName.of("products", "2"));

		var decoded = FreshnessTokens.decode(FreshnessTokens.encode(state), null, "products@2");

		assertThat(decoded.generation(), is("2"));
		assertThat(decoded.hasCommit(), is(false));
		assertThat(decoded.hasSettingsVersion(), is(false));
	}

	/**
	 * Removed settings are a state of their own, told apart from settings the
	 * token says nothing about.
	 */
	@Test
	public void testRemovedSettingsAreAnEmptyVersion() {
		var state = Freshness.ofSettings("products", null);

		var decoded = FreshnessTokens.decode(FreshnessTokens.encode(state), null, "products");

		assertThat(decoded.hasSettingsVersion(), is(true));
		assertThat(decoded.settingsVersion(), is(""));
	}

	@Test
	public void testNoTokenIsNoDemand() {
		assertThat(FreshnessTokens.decode(null, null, "products"), is(nullValue()));
		assertThat(FreshnessTokens.decode("", " ", "products"), is(nullValue()));
	}

	@Test
	public void testTheBodyWinsOverTheHeader() {
		var inBody = FreshnessTokens.encode(Freshness.ofCommit(IndexName.of("products", "2"), 3));
		var inHeader = FreshnessTokens.encode(Freshness.ofCommit(IndexName.of("products", "2"), 9));

		var decoded = FreshnessTokens.decode(inBody, inHeader, "products");

		assertThat(decoded.commit(), is(3L));
	}

	@Test
	public void testTheHeaderIsReadWhenTheBodyCarriesNone() {
		var inHeader = FreshnessTokens.encode(Freshness.ofCommit(IndexName.of("products", "2"), 9));

		var decoded = FreshnessTokens.decode(null, " " + inHeader + " ", "products");

		assertThat(decoded.commit(), is(9L));
	}

	@Test
	public void testGarbageIsInvalid() {
		var e = assertThrows(
			ValidationException.class,
			() -> FreshnessTokens.decode("not a token", null, "products")
		);

		var error = e.getErrors().getOnly();
		assertThat(error.getCode(), is("search:freshness:invalid"));
		assertThat(error.getLocation().describe(), is("freshness.atLeast"));
	}

	@Test
	public void testBytesThatAreNotAMessageAreInvalid() {
		var token = Base64.getUrlEncoder().withoutPadding().encodeToString(
			new byte[] { 1, (byte) 0xff, (byte) 0xff, (byte) 0xff }
		);

		var e = assertThrows(
			ValidationException.class,
			() -> FreshnessTokens.decode(token, null, "products")
		);

		assertThat(e.getErrors().getOnly().getCode(), is("search:freshness:invalid"));
	}

	/**
	 * A token from a later release names a format version this build does
	 * not read. Refused rather than misread, and the version is reported so
	 * the caller can tell which release it came from.
	 */
	@Test
	public void testALaterFormatVersionIsUnsupported() {
		var issued = Base64.getUrlDecoder().decode(
			FreshnessTokens.encode(Freshness.ofCommit(IndexName.of("products", "2"), 3))
		);
		issued[0] = 2;
		var token = Base64.getUrlEncoder().withoutPadding().encodeToString(issued);

		var e = assertThrows(
			ValidationException.class,
			() -> FreshnessTokens.decode(null, token, "products")
		);

		var error = e.getErrors().getOnly();
		assertThat(error.getCode(), is("search:freshness:version_unsupported"));
		assertThat(error.getArguments().get("version"), is(2));
		assertThat(error.getLocation().describe(), is(FreshnessTokens.HEADER));
	}

	@Test
	public void testATokenOfAnotherIndexIsAMismatch() {
		var token = FreshnessTokens.encode(Freshness.ofCommit(IndexName.of("products", "2"), 3));

		var e = assertThrows(
			ValidationException.class,
			() -> FreshnessTokens.decode(token, null, "orders@1")
		);

		var error = e.getErrors().getOnly();
		assertThat(error.getCode(), is("search:freshness:index_mismatch"));
		assertThat(error.getArguments().get("index"), is("products"));
		assertThat(error.getArguments().get("expected"), is("orders"));
	}

	/**
	 * A token of the index is accepted on any generation of it. Which
	 * generation the token names is for the wait to weigh, not the codec.
	 */
	@Test
	public void testATokenOfTheIndexIsAcceptedOnAGeneration() {
		var token = FreshnessTokens.encode(Freshness.ofCommit(IndexName.of("products", "2"), 3));

		var decoded = FreshnessTokens.decode(token, null, "products@1");

		assertThat(decoded.generation(), is("2"));
	}
}
