package se.l4.exofind.engine.storage;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.core.exception.SdkClientException;

/**
 * How {@link StorageAuth#fromConfig} reads the settings into a source, and
 * what the sources hand the client.
 */
public class StorageAuthTest {
	/**
	 * A key pair with no source named is the static source, which is what
	 * every deployment configured before there was a choice.
	 */
	@Test
	void testKeyPairIsStatic() {
		var auth = fromConfig(null, "key", "secret", null, null, null);

		assertThat(auth, is(new StorageAuth.Static("key", "secret", Optional.empty())));
	}

	/**
	 * A session token beside the pair is carried into the credentials, which
	 * is what a pair issued for a session needs to sign with.
	 */
	@Test
	void testSessionTokenIsSigned() {
		var auth = fromConfig(null, "key", "secret", "token", null, null);

		var credentials = auth.credentialsProvider().resolveCredentials();
		assertThat(credentials, instanceOf(AwsSessionCredentials.class));
		assertThat(((AwsSessionCredentials) credentials).sessionToken(), is("token"));
	}

	/**
	 * A credentials file with no source named is the file source, read from
	 * the profile the AWS tools write a lone profile under.
	 */
	@Test
	void testFileIsFile() {
		var auth = fromConfig(null, null, null, null, "/run/secrets/credentials", null);

		assertThat(auth, is(new StorageAuth.File(
			Path.of("/run/secrets/credentials"),
			StorageAuth.File.DEFAULT_PROFILE
		)));
	}

	@Test
	void testFileReadsNamedProfile() {
		var auth = fromConfig("file", null, null, null, "/run/secrets/credentials", "exofind");

		assertThat(auth, is(new StorageAuth.File(
			Path.of("/run/secrets/credentials"),
			"exofind"
		)));
	}

	/**
	 * Nothing at all is the AWS environment, so a node on AWS with a role
	 * needs no credential settings.
	 */
	@Test
	void testNothingIsAws() {
		var auth = fromConfig(null, null, null, null, null, null);

		assertThat(auth, is(new StorageAuth.Aws()));
	}

	/**
	 * A blank value is an unset one. A manifest that passes an empty
	 * variable through must not pick a source by it.
	 */
	@Test
	void testBlankIsUnset() {
		var auth = fromConfig("", "", " ", null, "", null);

		assertThat(auth, is(new StorageAuth.Aws()));
	}

	/**
	 * The name is read the way the storage mode is: trimmed and in any case.
	 */
	@Test
	void testNameIsCaseInsensitive() {
		var auth = fromConfig(" AWS ", null, null, null, null, null);

		assertThat(auth, is(new StorageAuth.Aws()));
	}

	/**
	 * A key pair and a file with no source named is refused rather than
	 * picked between, because either pick is wrong for half the deployments
	 * that end up here.
	 */
	@Test
	void testKeyPairAndFileAreRefusedWithoutName() {
		var e = assertThrows(IllegalStateException.class,
			() -> fromConfig(null, "key", "secret", null, "/run/secrets/credentials", null)
		);

		assertThat(e.getMessage(), containsString("EXOFIND_STORAGE_REMOTE_AUTH"));
	}

	/**
	 * With the source named, the settings of another source are refused,
	 * because they say the deployment meant something the node would not
	 * do.
	 */
	@Test
	void testNamedSourceRefusesSettingsOfAnother() {
		var e = assertThrows(IllegalStateException.class,
			() -> fromConfig("aws", "key", null, null, null, null)
		);

		assertThat(e.getMessage(), containsString("EXOFIND_STORAGE_REMOTE_ACCESS_KEY"));
		assertThat(e.getMessage(), containsString("'aws'"));
	}

	@Test
	void testFileRefusesKeyPair() {
		var e = assertThrows(IllegalStateException.class,
			() -> fromConfig("file", null, "secret", null, "/run/secrets/credentials", null)
		);

		assertThat(e.getMessage(), containsString("EXOFIND_STORAGE_REMOTE_SECRET_KEY"));
	}

	/**
	 * The static source demands both halves of the pair, and names the one
	 * that is missing.
	 */
	@Test
	void testStaticDemandsBothKeys() {
		var e = assertThrows(IllegalStateException.class,
			() -> fromConfig(null, "key", null, null, null, null)
		);

		assertThat(e.getMessage(), containsString("EXOFIND_STORAGE_REMOTE_SECRET_KEY"));
	}

	@Test
	void testFileDemandsPath() {
		var e = assertThrows(IllegalStateException.class,
			() -> fromConfig("file", null, null, null, null, null)
		);

		assertThat(e.getMessage(), containsString("EXOFIND_STORAGE_REMOTE_CREDENTIALS_FILE"));
	}

	/**
	 * The Google Cloud source carries no settings of its own and has to be
	 * named. The AWS source also looks for the credentials of the
	 * environment, so nothing present tells the two apart.
	 */
	@Test
	void testGcpIsNamed() {
		var auth = fromConfig("gcp", null, null, null, null, null);

		assertThat(auth, is(new StorageAuth.Gcp()));
	}

	/**
	 * The Google Cloud source signs nothing. The client leaves the request
	 * unsigned so that an access token carries the identity.
	 */
	@Test
	void testGcpSignsNothing() {
		var auth = fromConfig("gcp", null, null, null, null, null);

		assertThat(auth.credentialsProvider(), instanceOf(AnonymousCredentialsProvider.class));
	}

	/**
	 * A key pair beside the Google Cloud source says two different things
	 * about how a request is authorized, and is refused.
	 */
	@Test
	void testGcpRefusesKeyPair() {
		var e = assertThrows(IllegalStateException.class,
			() -> fromConfig("gcp", "key", "secret", null, null, null)
		);

		assertThat(e.getMessage(), containsString("EXOFIND_STORAGE_REMOTE_ACCESS_KEY"));
		assertThat(e.getMessage(), containsString("'gcp'"));
	}

	@Test
	void testUnknownNameIsRefused() {
		var e = assertThrows(IllegalStateException.class,
			() -> fromConfig("vault", null, null, null, null, null)
		);

		assertThat(e.getMessage(), containsString("'vault'"));
	}

	/**
	 * The file source reads the file again when it has been written since,
	 * which is what lets something else renew the credentials of a running
	 * node. The SDK checks the modification time at most every few seconds,
	 * so the new pair is waited for rather than expected at once.
	 */
	@Test
	void testFileSourceReloadsWhenModified(@TempDir Path dir) throws IOException {
		var file = dir.resolve("credentials");
		writeProfile(file, "first", "secret-1", Instant.now().minus(Duration.ofMinutes(1)));

		var provider = new StorageAuth.File(file, StorageAuth.File.DEFAULT_PROFILE)
			.credentialsProvider();

		assertThat(provider.resolveCredentials().accessKeyId(), is("first"));

		writeProfile(file, "second", "secret-2", Instant.now().plus(Duration.ofSeconds(1)));

		var deadline = Instant.now().plus(Duration.ofSeconds(30));
		String seen;
		while(true) {
			seen = provider.resolveCredentials().accessKeyId();
			if(seen.equals("second") || Instant.now().isAfter(deadline)) {
				break;
			}

			try {
				Thread.sleep(200);
			} catch(InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}

		assertThat(seen, is("second"));
	}

	/**
	 * A file without the profile has no credentials to hand over, and says
	 * which profile it looked for.
	 */
	@Test
	void testFileSourceWithoutProfileFails(@TempDir Path dir) throws IOException {
		var file = dir.resolve("credentials");
		Files.writeString(file, "[other]\naws_access_key_id = key\naws_secret_access_key = secret\n");

		var provider = new StorageAuth.File(file, "exofind").credentialsProvider();

		var e = assertThrows(SdkClientException.class, provider::resolveCredentials);
		assertThat(e.getMessage(), containsString("exofind"));
	}

	private static void writeProfile(Path file, String key, String secret, Instant modified)
		throws IOException {
		Files.writeString(file, """
			[default]
			aws_access_key_id = %s
			aws_secret_access_key = %s
			""".formatted(key, secret));
		Files.setLastModifiedTime(file, FileTime.from(modified));
	}

	private static StorageAuth fromConfig(
		String auth,
		String accessKey,
		String secretKey,
		String sessionToken,
		String credentialsFile,
		String credentialsProfile
	) {
		return StorageAuth.fromConfig(
			Optional.ofNullable(auth),
			Optional.ofNullable(accessKey),
			Optional.ofNullable(secretKey),
			Optional.ofNullable(sessionToken),
			Optional.ofNullable(credentialsFile),
			Optional.ofNullable(credentialsProfile)
		);
	}
}
