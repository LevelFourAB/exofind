package se.l4.exofind.engine.auth;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

import java.util.Optional;

import org.junit.jupiter.api.Test;

public class KeySecretTest {
	@Test
	void generatedCredentialParsesBackIntoItsParts() {
		var generated = KeySecret.generate();

		assertThat(generated.credential(), startsWith(KeySecret.PREFIX));

		var parsed = KeySecret.parse(generated.credential()).orElseThrow();
		assertThat(parsed.id(), is(generated.id()));
		assertThat(KeySecret.matches(parsed.secret(), generated.secretHash()), is(true));
	}

	@Test
	void everyGeneratedCredentialIsItsOwn() {
		var first = KeySecret.generate();
		var second = KeySecret.generate();

		assertThat(second.id(), is(not(first.id())));
		assertThat(second.credential(), is(not(first.credential())));
		assertThat(second.secretHash(), is(not(first.secretHash())));
	}

	@Test
	void aSecretIsNotStoredAlongsideItsHash() {
		var generated = KeySecret.generate();
		var secret = KeySecret.parse(generated.credential()).orElseThrow().secret();

		assertThat(generated.secretHash().contains(secret), is(false));
	}

	@Test
	void aWrongSecretDoesNotMatch() {
		var generated = KeySecret.generate();

		assertThat(KeySecret.matches("not the secret", generated.secretHash()), is(false));
	}

	@Test
	void whatIsNotShapedLikeACredentialIsNotOne() {
		assertThat(KeySecret.parse(null), is(Optional.empty()));
		assertThat(KeySecret.parse(""), is(Optional.empty()));
		assertThat(KeySecret.parse("hunter2"), is(Optional.empty()));

		// The prefix alone, without an id and a secret behind it
		assertThat(KeySecret.parse("exok_"), is(Optional.empty()));

		// An id that is not hex, so the separator would be ambiguous
		assertThat(KeySecret.parse("exok_zzzzzzzzzzzzzzzz_secret"), is(Optional.empty()));

		// The right length of id but no secret behind it
		assertThat(KeySecret.parse("exok_0123456789abcdef_"), is(Optional.empty()));
	}

	@Test
	void aSecretHoldingTheSeparatorIsReadWhole() {
		var parsed = KeySecret.parse("exok_0123456789abcdef_aa_bb_cc").orElseThrow();

		assertThat(parsed.id(), is("0123456789abcdef"));
		assertThat(parsed.secret(), is("aa_bb_cc"));
	}

	@Test
	void hashingIsStableAcrossCalls() {
		assertThat(KeySecret.hash("value"), is(KeySecret.hash("value")));
		assertThat(KeySecret.hash("value"), is(not(KeySecret.hash("Value"))));
	}
}
