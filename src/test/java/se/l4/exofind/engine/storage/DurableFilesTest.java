package se.l4.exofind.engine.storage;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class DurableFilesTest {
	@TempDir
	Path dir;

	@Test
	public void theContentsAreReadBack() throws IOException {
		var file = dir.resolve("state.ef.bin");

		DurableFiles.replace(file, bytes("first"));

		assertThat(Files.readAllBytes(file), is(bytes("first")));
	}

	@Test
	public void aSecondWriteReplacesTheContents() throws IOException {
		var file = dir.resolve("state.ef.bin");

		DurableFiles.replace(file, bytes("first"));
		DurableFiles.replace(file, bytes("second"));

		assertThat(Files.readAllBytes(file), is(bytes("second")));
	}

	@Test
	public void theWriteLeavesNothingBesideTheFile() throws IOException {
		var file = dir.resolve("state.ef.bin");

		DurableFiles.replace(file, bytes("first"));
		DurableFiles.replace(file, bytes("second"));

		assertThat(names(dir), contains("state.ef.bin"));
	}

	/**
	 * A write that does not finish leaves the previous contents. The temporary
	 * name is taken by a directory here, which is what stops the write.
	 */
	@Test
	public void aWriteThatFailsKeepsThePreviousContents() throws IOException {
		var file = dir.resolve("state.ef.bin");
		DurableFiles.replace(file, bytes("first"));

		Files.createDirectory(dir.resolve("state.ef.bin" + DurableFiles.TEMP_SUFFIX));

		assertThrows(IOException.class, () -> DurableFiles.replace(file, bytes("second")));
		assertThat(Files.readAllBytes(file), is(bytes("first")));
	}

	@Test
	public void anOwnerOnlyFileIsReadBack() throws IOException {
		var file = dir.resolve("keys.ef.bin");

		DurableFiles.replaceOwnerOnly(file, bytes("secret"));

		assertThat(Files.readAllBytes(file), is(bytes("secret")));
	}

	@Test
	public void anOwnerOnlyFileIsReadableByItsOwnerAlone() throws IOException {
		assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));

		var file = dir.resolve("keys.ef.bin");

		DurableFiles.replaceOwnerOnly(file, bytes("secret"));

		assertThat(
			Files.getPosixFilePermissions(file),
			is(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
		);
	}

	/**
	 * An interrupted write left a temporary file with the permissions of its
	 * time, and writing over it would keep them.
	 */
	@Test
	public void anOwnerOnlyWriteOverALeftoverFileNarrowsIt() throws IOException {
		assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));

		var file = dir.resolve("keys.ef.bin");
		var leftover = dir.resolve("keys.ef.bin" + DurableFiles.TEMP_SUFFIX);

		Files.write(leftover, bytes("interrupted"));
		Files.setPosixFilePermissions(leftover, PosixFilePermissions.fromString("rw-rw-rw-"));

		DurableFiles.replaceOwnerOnly(file, bytes("secret"));

		assertThat(
			Files.getPosixFilePermissions(file),
			is(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
		);
	}

	/**
	 * Two writers of one file that run at the same time each finish, and the
	 * file then holds the whole contents of one of them. The two writes are
	 * started together many times, as nothing in the class lets a test hold
	 * one write between its steps.
	 */
	@Test
	public void twoWritesOfOneFileAtTheSameTimeBothFinish() throws Exception {
		// Given
		var file = dir.resolve("usage.ef.bin");
		var first = filled('a', 64 * 1024);
		var second = filled('b', 32 * 1024);
		var rounds = 200;
		var start = new CyclicBarrier(2);
		var failures = new ConcurrentLinkedQueue<Throwable>();
		var mixed = new AtomicInteger();

		// When
		var writers = Executors.newFixedThreadPool(2);
		try {
			for(var round = 0; round < rounds; round++) {
				var a = writers.submit(() -> write(file, first, start, failures));
				var b = writers.submit(() -> write(file, second, start, failures));
				a.get(30, TimeUnit.SECONDS);
				b.get(30, TimeUnit.SECONDS);

				var contents = Files.readAllBytes(file);
				if(!Arrays.equals(contents, first) && !Arrays.equals(contents, second)) {
					mixed.incrementAndGet();
				}
			}
		} finally {
			writers.shutdownNow();
		}

		// Then
		assertThat("writes that failed: " + failures.peek(), failures.size(), is(0));
		assertThat("rounds that left mixed contents", mixed.get(), is(0));
	}

	private static Void write(
		Path file,
		byte[] contents,
		CyclicBarrier start,
		ConcurrentLinkedQueue<Throwable> failures
	) throws Exception {
		start.await(30, TimeUnit.SECONDS);
		try {
			DurableFiles.replace(file, contents);
		} catch(IOException e) {
			failures.add(e);
		}

		return null;
	}

	private static byte[] filled(char value, int size) {
		var contents = new byte[size];
		Arrays.fill(contents, (byte) value);
		return contents;
	}

	private static byte[] bytes(String contents) {
		return contents.getBytes(StandardCharsets.UTF_8);
	}

	private static List<String> names(Path directory) throws IOException {
		try(var files = Files.list(directory)) {
			return files.map(f -> f.getFileName().toString()).sorted().toList();
		}
	}
}
