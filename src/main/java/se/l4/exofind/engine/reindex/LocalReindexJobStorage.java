package se.l4.exofind.engine.reindex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32C;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ListIterable;

import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.storage.DurableFiles;

/**
 * ReindexJobStorage for a node keeping everything on its own disk, held as
 * one file per index in a jobs directory beside the indexes.
 *
 * <p>Without a remote this node is the only one there is, so the conditional
 * write only has to hold against itself. The version is taken from the
 * contents of the file, the way the local registry takes its.
 */
public class LocalReindexJobStorage implements ReindexJobStorage {
	private final Path directory;
	private final ReentrantLock lock;

	public LocalReindexJobStorage(Path directory) {
		this.directory = directory;
		this.lock = new ReentrantLock();
	}

	@Override
	public Optional<Stored> read(String index) throws IOException {
		lock.lock();
		try {
			byte[] contents;
			try {
				contents = Files.readAllBytes(fileOf(index));
			} catch(NoSuchFileException e) {
				return Optional.empty();
			}

			return Optional.of(new Stored(
				ReindexJobStore.parseFrom(contents),
				versionOf(contents)
			));
		} finally {
			lock.unlock();
		}
	}

	@Override
	public String write(String index, ReindexJobStore record, String expectedVersion)
		throws IOException {
		lock.lock();
		try {
			var file = fileOf(index);

			String current;
			try {
				current = versionOf(Files.readAllBytes(file));
			} catch(NoSuchFileException e) {
				current = null;
			}

			if(expectedVersion == null ? current != null : !expectedVersion.equals(current)) {
				return null;
			}

			var contents = record.toByteArray();

			Files.createDirectories(directory);
			DurableFiles.replace(file, contents);

			return versionOf(contents);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void delete(String index) throws IOException {
		lock.lock();
		try {
			Files.deleteIfExists(fileOf(index));
		} finally {
			lock.unlock();
		}
	}

	@Override
	public ListIterable<Stored> list() throws IOException {
		lock.lock();
		try {
			if(!Files.isDirectory(directory)) {
				return Lists.immutable.empty();
			}

			var found = Lists.mutable.<Stored>empty();
			try(var files = Files.list(directory)) {
				for(var file : files.toList()) {
					var name = file.getFileName().toString();
					if(name.endsWith(DurableFiles.TEMP_SUFFIX)) {
						continue;
					}

					byte[] contents;
					try {
						contents = Files.readAllBytes(file);
					} catch(NoSuchFileException e) {
						continue;
					}

					found.add(new Stored(
						ReindexJobStore.parseFrom(contents),
						versionOf(contents)
					));
				}
			}

			return found;
		} finally {
			lock.unlock();
		}
	}

	@Override
	public ListIterable<Stored> listUnfinished() throws IOException {
		/*
		 * A local read is cheap, so the phases come out of the records
		 * themselves and there is no marker beside them to keep in step.
		 */
		return list().reject(stored -> ReindexPhase.isFinished(stored.record()));
	}

	/**
	 * The file one index's record lives in. The name is validated as an index
	 * name, so a record can never land outside the directory.
	 */
	private Path fileOf(String index) {
		return directory.resolve(IndexName.of(index).toString());
	}

	/**
	 * The version of a stored record, which only has to tell one set of
	 * contents from another.
	 */
	private static String versionOf(byte[] contents) {
		var crc = new CRC32C();
		crc.update(contents);

		return "\"" + Long.toHexString(crc.getValue()) + "-" + contents.length + "\"";
	}
}
