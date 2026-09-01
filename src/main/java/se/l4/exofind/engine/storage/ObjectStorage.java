package se.l4.exofind.engine.storage;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.Consumer;

import se.l4.exofind.engine.index.IndexName;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.utils.SdkAutoCloseable;

/**
 * The bucket a deployment in {@link StorageMode#OBJECT} keeps everything in,
 * and the client it is reached through.
 *
 * <p>Everything that coordinates through the storage - the index files, the
 * registry, the indexer lease and the keys - shares this one client and one
 * bucket, so a deployment is configured once and there is a single place
 * deciding where an object lands.
 */
public class ObjectStorage implements AutoCloseable {
	/**
	 * Part of the path every index is stored under, so that an index can be
	 * told apart from anything else sharing the prefix.
	 */
	private static final String INDEXES_PATH = "indexes";

	/**
	 * Scratch object used to check that the storage enforces conditional
	 * writes. Lives directly under the prefix, where nothing reads it as an
	 * index.
	 */
	private static final String PROBE_PATH = ".conditional-write-probe";

	private final S3Client client;
	private final AwsCredentialsProvider credentialsProvider;
	private final String bucket;
	private final Optional<String> prefix;

	/**
	 * Open the storage, checking that it enforces conditional writes when this
	 * node may index.
	 *
	 * @param url
	 *   endpoint of the S3 compatible storage
	 * @param credentialsProvider
	 *   source of the credentials requests are signed with. The storage takes
	 *   ownership: {@link #close()} closes it along with the client
	 * @param region
	 *   region to sign requests for, where the storage cares
	 * @param bucket
	 * @param prefix
	 *   key prefix everything is written under, for sharing a bucket with
	 *   something else
	 * @param indexer
	 *   whether this node may act as the indexer, which is what makes the
	 *   conditional write check worth making
	 * @throws IOException
	 *   if the check could not be made, or the storage does not enforce
	 *   conditional writes
	 */
	public ObjectStorage(
		String url,
		AwsCredentialsProvider credentialsProvider,
		Optional<String> region,
		String bucket,
		Optional<String> prefix,
		boolean indexer
	) throws IOException {
		this(url, credentialsProvider, region, bucket, prefix, indexer, null);
	}

	/**
	 * Open the storage with something watching the requests made through it.
	 *
	 * @param interceptor
	 *   told about every request the client makes, or {@code null} to watch
	 *   none
	 */
	public ObjectStorage(
		String url,
		AwsCredentialsProvider credentialsProvider,
		Optional<String> region,
		String bucket,
		Optional<String> prefix,
		boolean indexer,
		ExecutionInterceptor interceptor
	) throws IOException {
		this.bucket = bucket;
		this.prefix = prefix;
		this.credentialsProvider = credentialsProvider;
		this.client = S3Client.builder()
			.endpointOverride(URI.create(url))
			.credentialsProvider(credentialsProvider)
			/*
			 * The SDK demands a region even when the endpoint decides where
			 * requests actually go, which is all an S3 compatible storage
			 * needs. The signature has to name one, so default to the one
			 * every storage accepts.
			 */
			.region(Region.of(region.orElse("us-east-1")))
			/*
			 * Address the bucket in the path rather than the host name, which
			 * works without wildcard DNS in front of the storage.
			 */
			.forcePathStyle(true)
			/*
			 * Only add checksums where an operation requires them. The default
			 * of checksumming everything sends them as a trailer, which
			 * S3 compatible storages do not universally accept.
			 */
			.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
			.responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
			/*
			 * Retries are decided by the code making the request - it knows
			 * whether a failure is an outcome, like a refused conditional
			 * write, or worth another attempt. The SDK retrying underneath
			 * would multiply those attempts.
			 */
			.overrideConfiguration(o -> {
				o.retryStrategy(AwsRetryStrategy.doNotRetry());

				if(interceptor != null) {
					o.addExecutionInterceptor(interceptor);
				}
			})
			.httpClientBuilder(UrlConnectionHttpClient.builder())
			.build();

		if(indexer) {
			/*
			 * A node that indexes relies on conditional writes to notice
			 * another writer, so it refuses to start against a storage that
			 * does not enforce them rather than run unfenced.
			 */
			verifyConditionalWrites(client, bucket, rootObject(PROBE_PATH));
		}
	}

	/**
	 * The client the storage is talked to through, shared by everything that
	 * coordinates through the same bucket.
	 */
	public S3Client client() {
		return client;
	}

	/**
	 * Close the client and the credentials provider this storage was opened
	 * with. Requests made through {@link #client()} after this throw
	 * {@link IllegalStateException}.
	 */
	@Override
	public void close() {
		client.close();

		/*
		 * The client does not close a provider handed to it, and the default
		 * chain caches sessions and may hold a refresh thread worth releasing.
		 */
		if(credentialsProvider instanceof SdkAutoCloseable closeable) {
			closeable.close();
		}
	}

	/**
	 * The bucket everything lives in.
	 */
	public String bucket() {
		return bucket;
	}

	/**
	 * The key of an object stored directly under the configured prefix, next
	 * to the indexes rather than inside one - which is where nothing that
	 * sweeps unreferenced index files can reach it.
	 *
	 * @param name
	 * @return
	 */
	public String rootObject(String name) {
		return resolvePath(prefix.orElse(""), name);
	}

	/**
	 * The path every index lives under, without a trailing separator. Listing
	 * it with {@code /} as the delimiter reports one entry per index, and each
	 * of those one entry per generation.
	 */
	public String indexesPath() {
		return resolvePath(prefix.orElse(""), INDEXES_PATH);
	}

	/**
	 * Where one generation of an index lives, which is a path of its own under
	 * the path of the index it belongs to.
	 *
	 * <p>Nesting them means the generations of an index sit together: listing
	 * what lies under the shared prefix reports one entry per index however
	 * many generations stand under it, and removing an index is removing one
	 * prefix.
	 *
	 * @param generation
	 * @return
	 */
	public String indexPath(IndexName generation) {
		return resolvePath(
			prefix.orElse(""),
			INDEXES_PATH,
			generation.index(),
			generation.generation()
		);
	}

	/**
	 * Whether a failed conditional write says this writer did not get its
	 * change in, rather than that something went wrong. What follows either
	 * way is the same: read what the storage holds now, build the change on
	 * top of it, and write again.
	 *
	 * <p>A storage that evaluated the condition against what it holds answers
	 * {@code 412}. AWS S3 answers {@code 409} instead when a write to the same
	 * key at the same moment kept it from deciding, and asks for the request to
	 * be made again. Nothing was written in either case, so a caller that
	 * rereads before retrying has the same work to do for both - and a caller
	 * that treated {@code 409} as an error would report a lost race as a
	 * storage failure.
	 *
	 * <p>Not for a caller whose answer to a refusal is to give something up:
	 * {@code 409} does not say another writer won, only that the write did not
	 * happen. See {@link se.l4.exofind.engine.index.state.ObjectStorageSync},
	 * where a refused manifest costs the node its unpushed documents and the
	 * conflict is only concluded once a write has actually been refused.
	 *
	 * @param e
	 * @return
	 */
	public static boolean isConditionalWriteLost(S3Exception e) {
		return e.statusCode() == 412 || e.statusCode() == 409;
	}

	/**
	 * Check that the storage enforces conditional writes. A storage that
	 * predates them ignores the condition instead of refusing the request, so
	 * the only way to know is to write against conditions that do not hold and
	 * see the writes be refused.
	 *
	 * @param client
	 * @param bucket
	 * @param object
	 *   key of the scratch object the check writes
	 * @throws IOException
	 *   if the storage could not be reached, or accepts writes whose condition
	 *   does not hold
	 */
	public static void verifyConditionalWrites(S3Client client, String bucket, String object)
		throws IOException {
		try {
			putProbe(client, bucket, object, null);

			/*
			 * The object now exists, so a write demanding that it does not,
			 * and one demanding a tag it never carried, both have to be
			 * refused.
			 */
			if(
				putProbe(client, bucket, object, b -> b.ifNoneMatch("*"))
					|| putProbe(
						client, bucket, object,
						b -> b.ifMatch("\"00000000000000000000000000000000\"")
					)
			) {
				throw new IOException(
					"Object storage accepted a write whose condition did not hold."
						+ " Running as the indexer relies on conditional writes to"
						+ " fence out a second writer; upgrade the storage to a"
						+ " version that enforces them, or run this node without"
						+ " the indexer property"
				);
			}
		} finally {
			try {
				client.deleteObject(
					DeleteObjectRequest.builder()
						.bucket(bucket)
						.key(object)
						.build()
				);
			} catch(Exception e) {
				// The probe object carries no data, leaving it behind is harmless
			}
		}
	}

	/**
	 * Write the probe object, under a condition when one is given.
	 *
	 * A write the storage refuses early can tear down the connection while the
	 * request is still on its way, which arrives as a connection failure
	 * rather than the refusal itself. That says nothing about what the storage
	 * decided, so the write is made again until it answers.
	 *
	 * @return
	 *   whether the storage accepted the write, {@code false} meaning it was
	 *   refused by its condition
	 * @throws IOException
	 *   if the write failed for any other reason
	 */
	private static boolean putProbe(
		S3Client client,
		String bucket,
		String object,
		Consumer<PutObjectRequest.Builder> condition
	) throws IOException {
		var contents = "Written by exofind to check that conditional writes are enforced"
			.getBytes(StandardCharsets.UTF_8);

		for(int attempt = 1;; attempt++) {
			var builder = PutObjectRequest.builder()
				.bucket(bucket)
				.key(object)
				.contentType("text/plain");

			if(condition != null) {
				condition.accept(builder);
			}

			try {
				client.putObject(builder.build(), RequestBody.fromBytes(contents));
				return true;
			} catch(S3Exception e) {
				if(isConditionalWriteLost(e)) {
					return false;
				}

				throw new IOException(
					"Unable to check conditional writes; " + e.getMessage(), e
				);
			} catch(Exception e) {
				if(attempt >= 3) {
					throw new IOException(
						"Unable to check conditional writes; " + e.getMessage(), e
					);
				}
			}
		}
	}

	/**
	 * Join the parts of a path, leaving out the ones that are empty. The
	 * result has no separator at either end, callers add one along with the
	 * name of the object they are after.
	 *
	 * @param prefix
	 * @param parts
	 * @return
	 */
	private static String resolvePath(String prefix, String... parts) {
		var path = new StringJoiner("/");

		appendTo(path, prefix);
		for(var part : parts) {
			appendTo(path, part);
		}

		return path.toString();
	}

	/**
	 * Add a part to a path, without the separators it may carry of its own so
	 * that joining can not produce an empty segment.
	 */
	private static void appendTo(StringJoiner path, String part) {
		if(part == null) {
			return;
		}

		var trimmed = part.replaceAll("^/+|/+$", "");
		if(!trimmed.isEmpty()) {
			path.add(trimmed);
		}
	}
}
