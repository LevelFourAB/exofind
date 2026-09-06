package se.l4.exofind.engine.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.profiles.ProfileFileSupplier;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;

/**
 * Where a node gets the credentials it authorizes object storage requests
 * with.
 *
 * <p>The engine does not renew credentials itself. Each source hands the
 * client something that caches what it resolved and fetches fresh credentials
 * ahead of their expiry: a provider from the AWS SDK, or for {@link Gcp} the
 * library that mints Google Cloud access tokens. A credential that is replaced
 * underneath a running node is picked up without a restart. What differs
 * between sources is only where it looks.
 *
 * <p>Which source a deployment uses is named by
 * {@code EXOFIND_STORAGE_REMOTE_AUTH}, or inferred from which of the source
 * settings are present when the name is left out. {@link #fromConfig} states
 * the rules, and refuses settings that name one source and carry the settings
 * of another, because a node signing with the wrong credentials against a
 * shared bucket is worse off than a node that did not start.
 */
public sealed interface StorageAuth {
	/**
	 * Name the source is chosen by in the configuration.
	 */
	String name();

	/**
	 * The provider the client signs requests with. Called once when the
	 * storage is opened. The provider refreshes the credentials after that.
	 *
	 * @return
	 */
	AwsCredentialsProvider credentialsProvider();

	/**
	 * An interceptor that puts the identity of this node on every request, for
	 * a source the client cannot sign with. Called once when the storage is
	 * opened.
	 *
	 * <p>Empty for every source that signs, where the SDK authorizes a request
	 * with the credentials of {@link #credentialsProvider()}. Only Google
	 * Cloud Storage accepts a token in place of a signature.
	 *
	 * @return
	 * @throws IOException
	 *   if the identity could not be taken up, leaving the node unable to
	 *   reach the storage
	 */
	default Optional<ExecutionInterceptor> authorization() throws IOException {
		return Optional.empty();
	}

	/**
	 * The region to sign requests for when the configuration names none.
	 *
	 * <p>The SDK demands a region even when an endpoint decides where requests
	 * go, which is all an S3 compatible storage needs; the signature has to
	 * name one, and every storage accepts the default. Only a deployment that
	 * gets its credentials from the AWS environment has somewhere better to
	 * ask.
	 *
	 * @return
	 */
	default Region defaultRegion() {
		return Region.US_EAST_1;
	}

	/**
	 * A fixed access key and secret key, with a session token when the pair
	 * was issued for a session. The keys never change while the node runs.
	 *
	 * <p>What every S3 compatible storage supports, and what a credential
	 * issued for a session looks like when it is pasted in: the temporary
	 * credentials AWS STS issues, or the ones the Cloudflare R2 API issues
	 * against a parent token, come as a key pair with a session token.
	 *
	 * @param accessKey
	 * @param secretKey
	 * @param sessionToken
	 *   token issued with the pair, or empty for a long-lived pair
	 */
	record Static(
		String accessKey,
		String secretKey,
		Optional<String> sessionToken
	) implements StorageAuth {
		public static final String NAME = "static";

		@Override
		public String name() {
			return NAME;
		}

		@Override
		public AwsCredentialsProvider credentialsProvider() {
			var credentials = sessionToken
				.<AwsCredentials>map(
					token -> AwsSessionCredentials.create(accessKey, secretKey, token)
				)
				.orElseGet(() -> AwsBasicCredentials.create(accessKey, secretKey));

			return StaticCredentialsProvider.create(credentials);
		}
	}

	/**
	 * The credentials the AWS environment hands a process: the environment
	 * variables, a web identity token file, the container credentials
	 * endpoint, and the instance metadata service, tried in that order.
	 *
	 * <p>This is how a node on EC2, ECS or EKS gets credentials that are
	 * scoped to its role and expire on their own, without any secret in its
	 * configuration. The provider renews them before they expire.
	 */
	record Aws() implements StorageAuth {
		public static final String NAME = "aws";

		@Override
		public String name() {
			return NAME;
		}

		@Override
		public AwsCredentialsProvider credentialsProvider() {
			return DefaultCredentialsProvider.builder().build();
		}

		/**
		 * The region the AWS environment names, from the environment
		 * variables, the shared configuration or the instance metadata
		 * service. Falls back to the default of every other source when the
		 * environment names none, for example when the credentials come from
		 * the AWS environment but the bucket is somewhere else.
		 */
		@Override
		public Region defaultRegion() {
			try {
				return new DefaultAwsRegionProviderChain().getRegion();
			} catch(RuntimeException e) {
				return StorageAuth.super.defaultRegion();
			}
		}
	}

	/**
	 * A credentials file in the format of the AWS shared credentials file,
	 * read again whenever its modification time changes.
	 *
	 * <p>For credentials something other than the node renews: an agent that
	 * fetches short-lived credentials from a secrets manager and writes them
	 * to disk, a mounted Kubernetes secret that a controller rotates, or a
	 * script that mints temporary credentials from a storage's API. The file
	 * holds a profile with {@code aws_access_key_id},
	 * {@code aws_secret_access_key} and, for a session, {@code aws_session_token}.
	 * A profile may instead name a {@code credential_process}, which the
	 * provider runs whenever it needs credentials.
	 *
	 * @param path
	 *   the file to read
	 * @param profile
	 *   name of the profile in the file to read the credentials from
	 */
	record File(
		Path path,
		String profile
	) implements StorageAuth {
		public static final String NAME = "file";

		/**
		 * Profile read when the configuration names none, which is the name
		 * the AWS tools write a lone profile under.
		 */
		public static final String DEFAULT_PROFILE = "default";

		@Override
		public String name() {
			return NAME;
		}

		@Override
		public AwsCredentialsProvider credentialsProvider() {
			return ProfileCredentialsProvider.builder()
				.profileFile(
					ProfileFileSupplier.reloadWhenModified(path, ProfileFile.Type.CREDENTIALS)
				)
				.profileName(profile)
				.build();
		}
	}

	/**
	 * The credentials Google Cloud hands a workload: the identity a node on
	 * GKE, Cloud Run or Compute Engine runs as, a service account key file
	 * named by {@code GOOGLE_APPLICATION_CREDENTIALS}, a federated identity,
	 * or the credentials of a developer signed in with {@code gcloud}.
	 *
	 * <p>A node on Google Cloud then reaches a bucket with no secret in its
	 * configuration. Google Cloud Storage accepts an access token in place of
	 * a signature, so the client signs nothing and
	 * {@link GoogleCredentialsInterceptor} puts the token on every request.
	 *
	 * <p>Only Google Cloud Storage accepts these credentials. A node that
	 * names this source and reaches another storage refuses to start, see
	 * {@link ObjectStorage}.
	 */
	record Gcp() implements StorageAuth {
		public static final String NAME = "gcp";

		@Override
		public String name() {
			return NAME;
		}

		/**
		 * Nothing to sign with. The SDK leaves a request unsigned for these
		 * credentials, and {@link GoogleCredentialsInterceptor} then sets the
		 * {@code Authorization} header.
		 */
		@Override
		public AwsCredentialsProvider credentialsProvider() {
			return AnonymousCredentialsProvider.create();
		}

		@Override
		public Optional<ExecutionInterceptor> authorization() throws IOException {
			return Optional.of(new GoogleCredentialsInterceptor());
		}
	}

	/**
	 * Work out the source from the settings, in the form the configuration
	 * names them.
	 *
	 * <p>With {@code auth} set, the settings of that source are demanded and
	 * the settings of the other sources refused. With it unset, the settings
	 * present decide: a key pair means {@link Static}, a file means
	 * {@link File}, and nothing at all means {@link Aws}. A key pair and a
	 * file together are refused. {@link Gcp} carries no settings of its own
	 * and is never inferred, because {@link Aws} also looks for the
	 * credentials of the environment.
	 *
	 * @param auth
	 *   value of {@code EXOFIND_STORAGE_REMOTE_AUTH}
	 * @param accessKey
	 *   value of {@code EXOFIND_STORAGE_REMOTE_ACCESS_KEY}
	 * @param secretKey
	 *   value of {@code EXOFIND_STORAGE_REMOTE_SECRET_KEY}
	 * @param sessionToken
	 *   value of {@code EXOFIND_STORAGE_REMOTE_SESSION_TOKEN}
	 * @param credentialsFile
	 *   value of {@code EXOFIND_STORAGE_REMOTE_CREDENTIALS_FILE}
	 * @param credentialsProfile
	 *   value of {@code EXOFIND_STORAGE_REMOTE_CREDENTIALS_PROFILE}
	 * @return
	 * @throws IllegalStateException
	 *   if the settings name no usable source, or name one source and carry
	 *   the settings of another
	 */
	static StorageAuth fromConfig(
		Optional<String> auth,
		Optional<String> accessKey,
		Optional<String> secretKey,
		Optional<String> sessionToken,
		Optional<String> credentialsFile,
		Optional<String> credentialsProfile
	) {
		var key = present(accessKey);
		var secret = present(secretKey);
		var token = present(sessionToken);
		var file = present(credentialsFile);
		var profile = present(credentialsProfile);

		var name = present(auth)
			.map(v -> v.trim().toLowerCase(Locale.ROOT))
			.orElseGet(() -> {
				if(key.isPresent() || secret.isPresent()) {
					if(file.isPresent()) {
						throw new IllegalStateException(
							"EXOFIND_STORAGE_REMOTE_ACCESS_KEY and"
								+ " EXOFIND_STORAGE_REMOTE_CREDENTIALS_FILE are both set;"
								+ " set EXOFIND_STORAGE_REMOTE_AUTH to 'static' or 'file' to"
								+ " say which one to use"
						);
					}

					return Static.NAME;
				}

				if(file.isPresent()) {
					return File.NAME;
				}

				return Aws.NAME;
			});

		return switch(name) {
			case Static.NAME -> {
				refuse(file, "EXOFIND_STORAGE_REMOTE_CREDENTIALS_FILE", name);
				refuse(profile, "EXOFIND_STORAGE_REMOTE_CREDENTIALS_PROFILE", name);

				yield new Static(
					demand(key, "EXOFIND_STORAGE_REMOTE_ACCESS_KEY", name),
					demand(secret, "EXOFIND_STORAGE_REMOTE_SECRET_KEY", name),
					token
				);
			}
			case Aws.NAME -> {
				refuse(key, "EXOFIND_STORAGE_REMOTE_ACCESS_KEY", name);
				refuse(secret, "EXOFIND_STORAGE_REMOTE_SECRET_KEY", name);
				refuse(token, "EXOFIND_STORAGE_REMOTE_SESSION_TOKEN", name);
				refuse(file, "EXOFIND_STORAGE_REMOTE_CREDENTIALS_FILE", name);
				refuse(profile, "EXOFIND_STORAGE_REMOTE_CREDENTIALS_PROFILE", name);

				yield new Aws();
			}
			case File.NAME -> {
				refuse(key, "EXOFIND_STORAGE_REMOTE_ACCESS_KEY", name);
				refuse(secret, "EXOFIND_STORAGE_REMOTE_SECRET_KEY", name);
				refuse(token, "EXOFIND_STORAGE_REMOTE_SESSION_TOKEN", name);

				yield new File(
					Path.of(demand(file, "EXOFIND_STORAGE_REMOTE_CREDENTIALS_FILE", name)),
					profile.orElse(File.DEFAULT_PROFILE)
				);
			}
			case Gcp.NAME -> {
				refuse(key, "EXOFIND_STORAGE_REMOTE_ACCESS_KEY", name);
				refuse(secret, "EXOFIND_STORAGE_REMOTE_SECRET_KEY", name);
				refuse(token, "EXOFIND_STORAGE_REMOTE_SESSION_TOKEN", name);
				refuse(file, "EXOFIND_STORAGE_REMOTE_CREDENTIALS_FILE", name);
				refuse(profile, "EXOFIND_STORAGE_REMOTE_CREDENTIALS_PROFILE", name);

				yield new Gcp();
			}
			default -> throw new IllegalStateException(
				"EXOFIND_STORAGE_REMOTE_AUTH is '" + name + "', which is none of '"
					+ Static.NAME + "', '" + Aws.NAME + "', '" + File.NAME + "' or '"
					+ Gcp.NAME + "'"
			);
		};
	}

	/**
	 * The value when it says something, treating a blank the way an unset
	 * variable is treated so that an empty value in a manifest does not pick
	 * a source.
	 */
	private static Optional<String> present(Optional<String> value) {
		return value.filter(v -> !v.isBlank());
	}

	private static String demand(Optional<String> value, String variable, String auth) {
		return value.orElseThrow(() -> new IllegalStateException(
			variable + " has to be set when EXOFIND_STORAGE_REMOTE_AUTH is '" + auth + "'"
		));
	}

	private static void refuse(Optional<String> value, String variable, String auth) {
		if(value.isPresent()) {
			throw new IllegalStateException(
				variable + " is set but EXOFIND_STORAGE_REMOTE_AUTH is '" + auth
					+ "', which does not read it. Unset it, or name the source that does"
			);
		}
	}
}
