package se.l4.exofind.engine.index.state;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32C;

import org.apache.lucene.index.SegmentInfos;

import se.l4.exofind.engine.index.Index;

/**
 * Judges the directory of an index that is not open, from its files alone.
 */
public final class LocalCopy {
	/**
	 * Name the manifest is stored under inside the index directory, describing
	 * the files as they were last synchronized with the remote.
	 */
	public static final String MANIFEST_FILE = "manifest.ef.bin";

	private LocalCopy() {
	}

	/**
	 * Whether the directory holds anything that never reached the remote,
	 * making it the only copy: a Lucene commit newer than the one the local
	 * manifest describes, a definition differing from the one the manifest
	 * lists, or - with no manifest at all - a definition whose first push
	 * never landed.
	 *
	 * <p>Errs on the side of yes: a directory this can not read is reported
	 * as holding changes. A directory with neither manifest nor definition
	 * holds nothing another node could miss and is reported as holding none.
	 */
	public static boolean hasUnpushedChanges(Path directory) {
		try {
			var manifestPath = directory.resolve(MANIFEST_FILE);
			if(!Files.exists(manifestPath)) {
				return Files.exists(directory.resolve(Index.DEFINITION_FILE));
			}

			Manifest manifest;
			try(var in = Files.newInputStream(manifestPath)) {
				manifest = Manifest.parseFrom(in);
			}

			String[] names;
			try(var files = Files.list(directory)) {
				names = files
					.map(p -> p.getFileName().toString())
					.toArray(String[]::new);
			}

			/*
			 * The segment number only moves when Lucene commits, so a commit
			 * past the one the manifest describes is one the remote never got.
			 * Files of a commit that was never pushed but also never committed
			 * are work in progress that no manifest would have listed either.
			 */
			if(SegmentInfos.getLastCommitGeneration(names) > manifest.getLatestSegment()) {
				return true;
			}

			return definitionDiffers(directory, manifest);
		} catch(IOException e) {
			return true;
		}
	}

	/**
	 * Whether the definition on disk is a different one than the manifest
	 * describes, which a definition replaced without its push landing is. A
	 * definition missing locally can be pulled again and does not count.
	 */
	private static boolean definitionDiffers(Path directory, Manifest manifest) throws IOException {
		var definition = directory.resolve(Index.DEFINITION_FILE);
		if(!Files.exists(definition)) {
			return false;
		}

		var described = manifest.getFilesList().stream()
			.filter(f -> f.getName().equals(Index.DEFINITION_FILE))
			.findFirst()
			.orElse(null);

		if(described == null) {
			return true;
		}

		if(described.getSize() != Files.size(definition)) {
			return true;
		}

		// A manifest from before checksums were recorded only has the size
		return described.hasChecksum() && described.getChecksum() != checksumOf(definition);
	}

	private static int checksumOf(Path path) throws IOException {
		var checksum = new CRC32C();
		var buffer = new byte[64 * 1024];

		try(var in = Files.newInputStream(path)) {
			int read;
			while((read = in.read(buffer)) != -1) {
				checksum.update(buffer, 0, read);
			}
		}

		return (int) checksum.getValue();
	}
}
