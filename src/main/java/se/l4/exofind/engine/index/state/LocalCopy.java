package se.l4.exofind.engine.index.state;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
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

	/**
	 * Name of the record a pull writes before it changes the first file in
	 * the directory. The record holds the manifest the pull is moving the
	 * directory to, in the format of {@link #MANIFEST_FILE}. The record is
	 * removed when the manifest is written. While it exists, the directory
	 * can hold files of that manifest beside an older {@link #MANIFEST_FILE}.
	 * The record never leaves the node.
	 */
	public static final String PULL_FILE = "pull.ef.bin";

	private LocalCopy() {
	}

	/**
	 * When the generation that the copy in the directory belongs to was
	 * created, as the manifest of the copy states it.
	 *
	 * @return
	 *   the time, or empty when the directory holds no manifest, or one that
	 *   does not state the time
	 * @throws IOException
	 *   if the manifest could not be read
	 */
	public static Optional<Instant> generationCreatedAt(Path directory) throws IOException {
		var manifestPath = directory.resolve(MANIFEST_FILE);
		if(!Files.exists(manifestPath)) {
			return Optional.empty();
		}

		Manifest manifest;
		try(var in = Files.newInputStream(manifestPath)) {
			manifest = Manifest.parseFrom(in);
		}

		return manifest.hasGenerationCreatedAt()
			? Optional.of(Instant.ofEpochMilli(manifest.getGenerationCreatedAt()))
			: Optional.empty();
	}

	/**
	 * Whether the directory holds anything that never reached the remote,
	 * making it the only copy: a Lucene commit newer than the one the local
	 * manifest describes, a definition differing from the one the manifest
	 * lists, or - with no manifest at all - a definition whose first push
	 * never landed.
	 *
	 * <p>A pull that stopped before it wrote its manifest leaves files of a
	 * newer manifest in the directory, see {@link #PULL_FILE}. Those files
	 * came from the remote and do not count. A commit or a definition that
	 * neither manifest describes counts.
	 *
	 * <p>Errs on the side of yes: a directory this can not read is reported
	 * as holding changes. A directory with neither manifest nor definition
	 * holds nothing another node could miss and is reported as holding none.
	 */
	public static boolean hasUnpushedChanges(Path directory) {
		try {
			var pulling = readManifest(directory.resolve(PULL_FILE));

			var manifest = readManifest(directory.resolve(MANIFEST_FILE));
			if(manifest == null) {
				return Files.exists(directory.resolve(Index.DEFINITION_FILE))
					&& (pulling == null || definitionDiffers(directory, pulling));
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
			var latestSegment = pulling == null
				? manifest.getLatestSegment()
				: Math.max(manifest.getLatestSegment(), pulling.getLatestSegment());

			if(SegmentInfos.getLastCommitGeneration(names) > latestSegment) {
				return true;
			}

			return definitionDiffers(directory, manifest)
				&& (pulling == null || definitionDiffers(directory, pulling));
		} catch(IOException e) {
			return true;
		}
	}

	/**
	 * Read a manifest from a file.
	 *
	 * @return
	 *   the manifest, or {@code null} when the file does not exist
	 * @throws IOException
	 *   if the file could not be read
	 */
	private static Manifest readManifest(Path path) throws IOException {
		if(!Files.exists(path)) {
			return null;
		}

		try(var in = Files.newInputStream(path)) {
			return Manifest.parseFrom(in);
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
