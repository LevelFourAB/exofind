package se.l4.exofind.engine.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import org.apache.lucene.util.IOUtils;

/**
 * Writes of the files the node keeps on its own disk, made so that the name
 * they are read from always holds the contents of a complete write.
 *
 * <p>These files are parsed as a whole and replaced in place, so a write that
 * stops part way through is not an error the reader can see: a truncated
 * Protocol Buffers message often parses, and answers with the fields it still
 * holds. The write goes to a temporary name and is moved into place once it is
 * on the disk, which leaves the previous contents behind instead.
 *
 * <p>The directory a file is written to has to exist.
 */
public final class DurableFiles {
	/**
	 * Suffix added to the name a file is written under before it is moved into
	 * place. A file left under this name is an interrupted write and holds
	 * nothing that is read back, so a directory listing has to pass it by.
	 */
	public static final String TEMP_SUFFIX = ".tmp";

	/**
	 * What a file written by {@link #replaceOwnerOnly(Path, byte[])} is
	 * readable and writable by, where the file system says who may read a file
	 * at all.
	 */
	private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(
		PosixFilePermission.OWNER_READ,
		PosixFilePermission.OWNER_WRITE
	);

	private DurableFiles() {
	}

	/**
	 * Write a file, replacing what it held. The file is left as it was if the
	 * write does not finish.
	 *
	 * @param file
	 *   file to write, which must be inside a directory that exists
	 * @param contents
	 *   contents to write
	 * @throws IOException
	 *   if the contents could not be written
	 */
	public static void replace(Path file, byte[] contents) throws IOException {
		replace(file, contents, false);
	}

	/**
	 * Write a file that only the user running the node can read, replacing
	 * what it held. For contents such as the keys, where a bucket would have
	 * access rules of its own and a file has whatever the umask gave it.
	 *
	 * <p>A file system that does not describe permissions this way decides who
	 * may read on its own. There is nothing to narrow there, and failing the
	 * write would leave the node unable to keep the contents at all.
	 *
	 * @param file
	 *   file to write, which must be inside a directory that exists
	 * @param contents
	 *   contents to write
	 * @throws IOException
	 *   if the contents could not be written
	 */
	public static void replaceOwnerOnly(Path file, byte[] contents) throws IOException {
		replace(file, contents, true);
	}

	private static void replace(Path file, byte[] contents, boolean ownerOnly)
		throws IOException
	{
		var directory = file.toAbsolutePath().getParent();
		var temp = file.resolveSibling(file.getFileName() + TEMP_SUFFIX);

		write(temp, contents, ownerOnly);

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

	/**
	 * Write the temporary file the contents go to.
	 *
	 * <p>Permissions are given when the file is created rather than set
	 * afterwards, so there is no moment at which the file exists with whatever
	 * the umask would have given it. A file left behind by an interrupted
	 * write is removed first, because it was created with the permissions of
	 * its time and creating over it would keep them.
	 */
	private static void write(Path temp, byte[] contents, boolean ownerOnly)
		throws IOException
	{
		if(!ownerOnly) {
			Files.write(temp, contents);
			return;
		}

		Files.deleteIfExists(temp);

		var options = Set.<OpenOption>of(
			StandardOpenOption.CREATE_NEW,
			StandardOpenOption.WRITE
		);

		try(var channel = open(temp, options)) {
			var buffer = ByteBuffer.wrap(contents);
			while(buffer.hasRemaining()) {
				channel.write(buffer);
			}
		}
	}

	/**
	 * Open a new file for writing, readable only by its owner where the file
	 * system can say so.
	 */
	private static SeekableByteChannel open(Path path, Set<OpenOption> options)
		throws IOException
	{
		try {
			return Files.newByteChannel(
				path,
				options,
				PosixFilePermissions.asFileAttribute(OWNER_ONLY)
			);
		} catch(UnsupportedOperationException e) {
			return Files.newByteChannel(path, options);
		}
	}
}
