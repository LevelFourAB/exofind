package se.l4.exofind.engine.index.state;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.RandomStringUtils;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ListIterable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.ObjectStorageRegistryAudit;
import se.l4.exofind.engine.index.registry.ObjectStorageRegistryStorage;
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
		var removed = sweeping.remove(IndexName.of("books"), Instant.now());

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
		var removed = sweeping.remove(IndexName.of("books"), Instant.now());

		// Then the objects of the new index are still there
		assertThat(hook.get(), is(nullValue()));
		assertThat(removed, is(false));
		assertThat(keysUnder("books/"), hasItem("books/1/" + LocalCopy.MANIFEST_FILE));
		assertThat(keysUnder("books/"), hasItem("books/1/e1/_0.cfs"));
	}

	/**
	 * An operator restores a deleted index with a registry repair while the
	 * sweep removes it. The repair registers the index before it takes the
	 * mark off, and the sweep checks only the mark before each batch. Once
	 * the index is registered again, the sweep must not go on to its next
	 * batch.
	 */
	@Test
	public void sweepStopsBeforeItsNextBatchOnceARepairRegisteredTheIndexAgain() throws Exception {
		// Given an index deleted long ago that holds more objects than one batch removes
		put("books/1/" + LocalCopy.MANIFEST_FILE, "old manifest");
		IntStream.range(0, 1001).parallel().forEach(i -> put("books/1/e1/_" + i + ".cfs", "segment"));
		other.mark(IndexName.of("books"));
		var objects = keysUnder("books/1/").size();

		// And the sweep has checked the mark before its first batch
		var checked = new CountDownLatch(1);
		var goOn = new CountDownLatch(1);
		hookMethod.set("headObject");
		hook.set(() -> {
			checked.countDown();
			try {
				goOn.await(30, TimeUnit.SECONDS);
			} catch(InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});

		var sweep = CompletableFuture.supplyAsync(() -> {
			try {
				return sweeping.remove(IndexName.of("books"), Instant.now());
			} catch(IOException e) {
				throw new RuntimeException(e);
			}
		});
		assertThat(checked.await(30, TimeUnit.SECONDS), is(true));

		// When the operator restores books, and the sweep goes on between the registry write and the unmark
		var restoring = new ObjectStorageIndexRemovals(storage) {
			@Override
			public boolean unmark(IndexName target) throws IOException {
				goOn.countDown();
				try {
					sweep.get(30, TimeUnit.SECONDS);
				} catch(Exception e) {
					throw new IOException(e);
				}

				return super.unmark(target);
			}
		};
		var audit = new ObjectStorageRegistryAudit(
			storage,
			new ObjectStorageRegistryStorage(storage),
			restoring
		);
		var result = audit.repair(false, Lists.immutable.of(IndexName.of("books")));

		// Then the registry names books again
		assertThat(result.createdIndexes(), hasItem("books"));

		// And the sweep stopped before its second batch, so the objects past the first batch remain
		assertThat(
			"sweep removed objects of the restored index after it was registered",
			keysUnder("books/1/").size(),
			greaterThanOrEqualTo(objects - 1000)
		);
	}

	/**
	 * A repair restores an index and takes the mark off after it registered
	 * the index. Another node can delete the index again in between and
	 * write a new mark, which is the only thing that says the objects of
	 * that delete go.
	 */
	@Test
	public void restoreDoesNotTakeOffTheMarkOfADeleteAfterTheRegistryWrite() throws IOException {
		// Given an index deleted long ago
		put("books/1/" + LocalCopy.MANIFEST_FILE, "old manifest");
		put("books/1/e1/_0.cfs", "old segment");
		other.mark(IndexName.of("books"));

		// And another node deletes books again once the repair registered it, before the repair takes its mark off
		var otherRegistry = new IndexRegistry(
			new ObjectStorageRegistryStorage(storage),
			Duration.ofMinutes(5)
		);
		var deleteAgain = new AtomicBoolean(true);
		var restoring = new ObjectStorageIndexRemovals(storage) {
			@Override
			public boolean unmark(IndexName target) throws IOException {
				if(
					target.toString().equals("books")
						&& otherRegistry.refresh()
						&& otherRegistry.get("books").isPresent()
						&& deleteAgain.compareAndSet(true, false)
				) {
					otherRegistry.remove("books");
					other.mark(IndexName.of("books"));
				}

				return super.unmark(target);
			}
		};

		// When the operator restores books
		var audit = new ObjectStorageRegistryAudit(
			storage,
			new ObjectStorageRegistryStorage(storage),
			restoring
		);
		audit.repair(false, Lists.immutable.of(IndexName.of("books")));

		// Then the mark of the later delete still stands
		assertThat(deleteAgain.get(), is(false));
		otherRegistry.refresh();
		assertThat(otherRegistry.get("books").isPresent(), is(false));
		assertThat(
			"mark of the later delete removed by the restore",
			other.markedAt(IndexName.of("books")).isPresent(),
			is(true)
		);
	}
}
