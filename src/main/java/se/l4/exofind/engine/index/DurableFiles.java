package se.l4.exofind.engine.index;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.apache.lucene.util.IOUtils;

/**
 * Writes of the files the engine keeps beside the Lucene files, made so that
 * the name they are read from always holds the contents of a complete write.
 *
 * <p>These files are parsed as a whole and replaced in place, so a write that
 * stops part way through is not an error the reader can see: a truncated
 * Protocol Buffers message often parses, and answers with the fields it still
 * holds. The write goes to a temporary name and is moved into place once it is
 * on the disk, which leaves the previous contents behind instead.
 */
public final class DurableFiles {
	/**
	 * Suffix added to the name a file is written under before it is moved into
	 * place. A file left under this name is an interrupted write and holds
	 * nothing that is read back.
	 */
	public static final String TEMP_SUFFIX = ".tmp";

	private DurableFiles() {
	}

	/**
	 * Write a file, replacing what it held. The file is left as it was if the
	 * write does not finish.
	 *
	 * @param file
	 *   file to write, which must be inside a directory
	 * @param contents
	 *   contents to write
	 * @throws IOException
	 *   if the contents could not be written
	 */
	public static void replace(Path file, byte[] contents) throws IOException {
		var directory = file.toAbsolutePath().getParent();
		var temp = file.resolveSibling(file.getFileName() + TEMP_SUFFIX);

		Files.write(temp, contents);

		/*
		 * On the disk before the rename, so that what the rename publishes is
		 * a complete file and never a file of the right name holding part of
		 * one. A write that has only reached the page cache survives a process
		 * that stops but not a machine that loses power, and the rename can
		 * reach the disk first.
		 */
		IOUtils.fsync(temp, false);

		try {
			Files.move(
				temp,
				file,
				StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE
			);
		} catch(AtomicMoveNotSupportedException e) {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		}

		/*
		 * The rename itself lives in the directory rather than in the file, so
		 * it takes a sync of its own to survive. Without it a machine that
		 * loses power here comes back to the contents the file held before.
		 */
		IOUtils.fsync(directory, true);
	}
}
