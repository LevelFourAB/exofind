package se.l4.exofind.engine.index.state;

import java.net.URI;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

/**
 * Object storage for tests to run against, started in a container on first
 * use and shared by every test in the JVM. Testcontainers removes the
 * container when the JVM exits.
 *
 * <p>The storage has to enforce conditional writes ({@code If-Match},
 * {@code If-None-Match}), which the sync and ownership tests depend on;
 * {@link ObjectStorageSyncProvider} refuses storage that does not. SeaweedFS
 * is run with an identity configured, so unsigned or wrongly signed requests
 * are refused the way a real storage would refuse them.
 *
 * <p>{@link #BUCKET} exists by the time {@link #client()} or {@link #url()}
 * returns. Tests share the bucket and keep to a prefix of their own within
 * it.
 */
public final class TestObjectStorage {
	public static final String BUCKET = "automated-tests";
	public static final String ACCESS_KEY = "exofind";
	public static final String SECRET_KEY = "exofind123";

	private static final int S3_PORT = 8333;

	private static final String S3_CONFIG = """
		{
			"identities": [
				{
					"name": "tests",
					"credentials": [
						{"accessKey": "%s", "secretKey": "%s"}
					],
					"actions": ["Admin", "Read", "Write", "List", "Tagging"]
				}
			]
		}
		""".formatted(ACCESS_KEY, SECRET_KEY);

	private static final GenericContainer<?> container = new GenericContainer<>("chrislusf/seaweedfs:4.41")
		.withExposedPorts(S3_PORT)
		.withCopyToContainer(
			Transferable.of(S3_CONFIG),
			"/etc/seaweedfs/s3-config.json"
		)
		.withCommand("server", "-s3", "-s3.config=/etc/seaweedfs/s3-config.json")
		/*
		 * The S3 gateway only starts serving after the filer is up, and with
		 * an identity configured an anonymous listing is refused with a 403.
		 * Seeing one means the storage is ready for signed requests, not
		 * merely listening.
		 */
		.waitingFor(
			Wait.forHttp("/")
				.forPort(S3_PORT)
				.forStatusCode(403)
		);

	/**
	 * Where the URL of the running storage is published, so that every
	 * classloader in the JVM talks to the same container. A Quarkus test
	 * loads this class once more in the classloader the application is built
	 * in, and a plain static would have each copy start a container of its
	 * own - with the node writing into one storage and the test reading
	 * another.
	 */
	private static final String URL_PROPERTY = "exofind.test.object-storage.url";

	private static S3Client client;

	private TestObjectStorage() {
	}

	/**
	 * Client for the storage, starting the container if none is running in
	 * this JVM. The returned client is shared; it is safe for concurrent use
	 * and is never closed. Built the way {@link ObjectStorageSyncProvider}
	 * builds its client, so the tests exercise the storage the way the engine
	 * talks to it.
	 */
	public static synchronized S3Client client() {
		if(client == null) {
			var created = S3Client.builder()
				.endpointOverride(URI.create(url()))
				.credentialsProvider(
					StaticCredentialsProvider.create(
						AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)
					)
				)
				.region(Region.US_EAST_1)
				.forcePathStyle(true)
				.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
				.responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
				.overrideConfiguration(o -> o.retryStrategy(AwsRetryStrategy.doNotRetry()))
				.httpClientBuilder(UrlConnectionHttpClient.builder())
				.build();

			try {
				created.headBucket(HeadBucketRequest.builder().bucket(BUCKET).build());
			} catch(NoSuchBucketException e) {
				try {
					created.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
				} catch(Exception e2) {
					throw new IllegalStateException("Could not create the test bucket", e2);
				}
			}

			client = created;
		}

		return client;
	}

	/**
	 * URL the storage is reachable on, starting the container if none is
	 * running in this JVM. The port is picked by Docker, so the value differs
	 * between runs.
	 */
	public static synchronized String url() {
		var existing = System.getProperty(URL_PROPERTY);
		if(existing != null) {
			return existing;
		}

		container.start();

		var endpoint = "http://" + container.getHost() + ":" + container.getMappedPort(S3_PORT);
		System.setProperty(URL_PROPERTY, endpoint);
		return endpoint;
	}
}
