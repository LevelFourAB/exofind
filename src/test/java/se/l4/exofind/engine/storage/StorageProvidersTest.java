package se.l4.exofind.engine.storage;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.utils.SdkAutoCloseable;

/**
 * How {@code EXOFIND_STORAGE_REMOTE_CREDENTIALS} turns into the provider the
 * storage signs with, and what each source demands of the key settings.
 *
 * <p>The {@code default} source is only constructed here, never resolved:
 * what the chain finds depends on the machine running the tests, and a
 * resolution that succeeds on one developer's laptop and fails on another's
 * is not a test. Resolution through the chain is covered by
 * {@link ObjectStorageDefaultCredentialsTest}, which controls what the chain
 * sees.
 */
public class StorageProvidersTest {
	@Test
	public void staticCredentialsSignWithTheConfiguredKeys() {
		var provider = StorageProviders.remoteCredentialsProvider(
			"static",
			Optional.of("configured-access"),
			Optional.of("configured-secret")
		);

		var credentials = provider.resolveCredentials();
		assertThat(credentials.accessKeyId(), is("configured-access"));
		assertThat(credentials.secretAccessKey(), is("configured-secret"));
	}

	@Test
	public void staticWithoutAnAccessKeyNamesTheVariable() {
		var e = assertThrows(IllegalStateException.class, () ->
			StorageProviders.remoteCredentialsProvider(
				"static",
				Optional.empty(),
				Optional.of("configured-secret")
			)
		);

		assertThat(e.getMessage(), containsString("EXOFIND_STORAGE_REMOTE_ACCESS_KEY"));
	}

	@Test
	public void staticWithoutASecretKeyNamesTheVariable() {
		var e = assertThrows(IllegalStateException.class, () ->
			StorageProviders.remoteCredentialsProvider(
				"static",
				Optional.of("configured-access"),
				Optional.empty()
			)
		);

		assertThat(e.getMessage(), containsString("EXOFIND_STORAGE_REMOTE_SECRET_KEY"));
	}

	@Test
	public void aBlankKeyCountsAsMissing() {
		var e = assertThrows(IllegalStateException.class, () ->
			StorageProviders.remoteCredentialsProvider(
				"static",
				Optional.of("  "),
				Optional.of("configured-secret")
			)
		);

		assertThat(e.getMessage(), containsString("EXOFIND_STORAGE_REMOTE_ACCESS_KEY"));
	}

	@Test
	public void defaultBuildsTheSdkChain() {
		var provider = StorageProviders.remoteCredentialsProvider(
			"default",
			Optional.empty(),
			Optional.empty()
		);

		assertThat(provider, instanceOf(DefaultCredentialsProvider.class));

		((SdkAutoCloseable) provider).close();
	}

	@Test
	public void keysConfiguredAlongsideTheChainAreNotRead() {
		var provider = StorageProviders.remoteCredentialsProvider(
			"default",
			Optional.of("configured-access"),
			Optional.of("configured-secret")
		);

		assertThat(provider, instanceOf(DefaultCredentialsProvider.class));

		((SdkAutoCloseable) provider).close();
	}

	@Test
	public void anUnknownSourceNamesTheVariableAndTheChoices() {
		var e = assertThrows(IllegalStateException.class, () ->
			StorageProviders.remoteCredentialsProvider(
				"chain",
				Optional.empty(),
				Optional.empty()
			)
		);

		assertThat(e.getMessage(), containsString("EXOFIND_STORAGE_REMOTE_CREDENTIALS"));
		assertThat(e.getMessage(), containsString("'chain'"));
		assertThat(e.getMessage(), containsString("neither 'static' nor 'default'"));
	}

	@Test
	public void theSourceIsTrimmedAndCaseInsensitive() {
		var chain = StorageProviders.remoteCredentialsProvider(
			" Default ",
			Optional.empty(),
			Optional.empty()
		);

		assertThat(chain, instanceOf(DefaultCredentialsProvider.class));
		((SdkAutoCloseable) chain).close();

		var fixed = StorageProviders.remoteCredentialsProvider(
			"STATIC",
			Optional.of("configured-access"),
			Optional.of("configured-secret")
		);

		assertThat(fixed.resolveCredentials().accessKeyId(), is("configured-access"));
	}
}
