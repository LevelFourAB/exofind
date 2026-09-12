package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

		Files.createDirectory(dir.resolve("state.ef.bin.tmp"));

		assertThrows(IOException.class, () -> DurableFiles.replace(file, bytes("second")));
		assertThat(Files.readAllBytes(file), is(bytes("first")));
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
