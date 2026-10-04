package se.l4.exofind.engine.index.state;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.RandomStringUtils;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ListIterable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.storage.ObjectStorage;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Tests of a sweep that removes a marked prefix while the same name is
 * created again, and deleted again, on another node.
 */
public class ObjectStorageIndexRemovalsDuringRecreateTest {
	ObjectStorage storage;

	/**
	 * Runs once after the given number of matching requests returned,
	 * standing in for another node acting between two requests of the sweep.
	 */
	final AtomicReference<String> hookMethod = new AtomicReference<>();
	final AtomicInteger hookAfter = new AtomicInteger(1);
	final AtomicReference<Runnable> hook = new AtomicReference<>();

	ObjectStorageIndexRemovals sweeping;
	ObjectStorageIndexRemovals other;

	@BeforeEach
	void setup() throws IOException {
		var prefix = Optional.of("test" + RandomStringUtils.insecure().nextAlphabetic(10));

		storage = new ObjectStorage(
			Optional.of(TestObjectStorage.url()),
			TestObjectStorage.auth(),
			Optional.empty(),
			TestObjectStorage.BUCKET,
			prefix,
			false
		);

		var real = storage.client();
		var hooked = (S3Client) Proxy.newProxyInstance(
			S3Client.class.getClassLoader(),
			new Class<?>[] { S3Client.class },
			(proxy, method, args) -> {
				Object result;
				try {
					result = method.invoke(real, args);
				} catch(InvocationTargetException e) {
					throw e.getCause();
				}

				if(method.getName().equals(hookMethod.get()) && hookAfter.decrementAndGet() == 0) {
					var run = hook.getAndSet(null);
					if(run != null) {
						hookMethod.set(null);
						run.run();
					}
				}

				return result;
			}
		);

		var hookedStorage = new ObjectStorage(
			Optional.of(TestObjectStorage.url()),
			TestObjectStorage.auth(),
			Optional.empty(),
			TestObjectStorage.BUCKET,
			prefix,
			false
		) {
			@Override
			public S3Client client() {
				return hooked;
			}
		};

		sweeping = new ObjectStorageIndexRemovals(hookedStorage);
		other = new ObjectStorageIndexRemovals(storage);
	}

	@AfterEach
	void cleanup() {
		for(var key : keysUnder("")) {
			storage.client().deleteObject(
				DeleteObjectRequest.builder()
					.bucket(storage.bucket())
					.key(storage.indexesPath() + "/" + key)
					.build()
			);
		}
	}

	private void put(String path, String contents) {
		storage.client().putObject(
			PutObjectRequest.builder()
				.bucket(storage.bucket())
				.key(storage.indexesPath() + "/" + path)
				.build(),
			RequestBody.fromString(contents)
		);
	}

	private ListIterable<String> keysUnder(String path) {
		var prefix = storage.indexesPath() + "/" + path;
		var keys = Lists.mutable.<String>empty();

		var objects = storage.client().listObjectsV2Paginator(
			b -> b.bucket(storage.bucket()).prefix(prefix)
		).contents();

		for(var object : objects) {
			keys.add(object.key().substring(storage.indexesPath().length() + 1));
		}

		return keys;
	}

	/**
	 * Another node creates the name again, which clears the old mark, and
	 * then deletes it again, which writes a new mark. Both happen after the
	 * sweep removed its last batch and before it removes the mark. The new
	 * mark is the only thing that says the new objects are to be removed, so
	 * the sweep must not remove it.
	 */
	@Test
	public void sweepDoesNotRemoveTheMarkOfALaterDelete() throws IOException {
		// Given an index deleted long ago
		put("books/1/" + LocalCopy.MANIFEST_FILE, "old manifest");
		put("books/1/e1/_0.cfs", "old segment");
		other.mark(IndexName.of("books"));

		// And another node creates it again and deletes it again after the last batch
		hookMethod.set("deleteObjects");
		hook.set(() -> {
			try {
				other.prepareForIndex("books");
				other.prepareForGeneration(IndexName.of("books", "1"));
				put("books/1/" + LocalCopy.MANIFEST_FILE, "new manifest");
				put("books/1/e1/_0.cfs", "new segment");
				other.mark(IndexName.of("books"));
			} catch(IOException e) {
				throw new RuntimeException(e);
			}
		});

		// When
		var removed = sweeping.remove(IndexName.of("books"));

		// Then the mark of the second delete still stands
		assertThat(hook.get(), is(nullValue()));
		assertThat(removed, is(false));
		assertThat(other.markedAt(IndexName.of("books")).isPresent(), is(true));
	}

	/**
	 * Another node creates the name again between the check of the mark and
	 * the batch that follows it, and pushes a first manifest under the same
	 * key the old index used. The sweep must not remove the objects of the
	 * new index.
	 */
	@Test
	public void sweepDoesNotRemoveObjectsOfAnIndexCreatedAfterItsMarkCheck() throws IOException {
		// Given an index deleted long ago
		put("books/1/" + LocalCopy.MANIFEST_FILE, "old manifest");
		put("books/1/e1/_0.cfs", "old segment");
		other.mark(IndexName.of("books"));

		// And another node creates it again right after the sweep checked the mark before its batch
		hookMethod.set("headObject");
		hookAfter.set(2);
		hook.set(() -> {
			try {
				other.prepareForIndex("books");
				other.prepareForGeneration(IndexName.of("books", "1"));
				put("books/1/e1/_0.cfs", "new segment");
				put("books/1/" + LocalCopy.MANIFEST_FILE, "new manifest");
			} catch(IOException e) {
				throw new RuntimeException(e);
			}
		});

		// When
		var removed = sweeping.remove(IndexName.of("books"));

		// Then the objects of the new index are still there
		assertThat(hook.get(), is(nullValue()));
		assertThat(removed, is(false));
		assertThat(keysUnder("books/"), hasItem("books/1/" + LocalCopy.MANIFEST_FILE));
		assertThat(keysUnder("books/"), hasItem("books/1/e1/_0.cfs"));
	}
}
