package se.l4.exofind.engine.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.apache.lucene.util.BytesRef;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;

import com.google.protobuf.ByteString;

import se.l4.exofind.engine.index.state.ChangeLogStore;

/**
 * Which documents of an index have changed since tracking began, by primary
 * key term.
 *
 * <p>The log answers one question: which documents may differ from a copy of
 * the index taken when tracking began. It says nothing about what changed - a
 * reader catches a copy up by taking a {@link #snapshot()}, reading the named
 * documents from the index as they are now, and handing the snapshot back to
 * {@link #forget(Snapshot)}. A key written again after the snapshot was taken
 * survives the forget, so nothing recorded is ever lost to a replay that ran
 * alongside new writes.
 *
 * <p>A key may be listed for a document that was written back unchanged, or
 * removed and no longer there; a changed document is never missing. Keys are
 * primary key terms as Lucene indexed them, comparable only with terms of an
 * index whose primary key field has the same type.
 *
 * <p>Safe for concurrent use. {@link Index} keeps the log alive: it records
 * into it on every write and persists it alongside its commits - see
 * {@link Index#beginChangeTracking()}.
 */
public class ChangeLog {
	private final Map<BytesRef, Long> keys;
	private long sequence;

	public ChangeLog() {
		this.keys = new HashMap<>();
	}

	/**
	 * Record that the document a primary key term names has changed. The bytes
	 * are copied.
	 */
	public synchronized void record(BytesRef key) {
		keys.put(BytesRef.deepCopyOf(key), ++sequence);
	}

	/**
	 * Get whether nothing has changed since tracking began or the last
	 * {@link #forget(Snapshot)} covered everything.
	 */
	public synchronized boolean isEmpty() {
		return keys.isEmpty();
	}

	/**
	 * Get how many documents the log currently names.
	 */
	public synchronized int size() {
		return keys.size();
	}

	/**
	 * Get the documents recorded so far, as of this moment. Keys recorded
	 * after this call are not in the snapshot and survive a
	 * {@link #forget(Snapshot)} of it.
	 */
	public synchronized Snapshot snapshot() {
		var copied = Lists.mutable.<BytesRef>withInitialCapacity(keys.size());
		copied.addAllIterable(keys.keySet());
		return new Snapshot(sequence, copied.toImmutable());
	}

	/**
	 * Drop the keys of a snapshot that has been replayed, keeping any of them
	 * that were recorded again after the snapshot was taken.
	 */
	public synchronized void forget(Snapshot snapshot) {
		for(var key : snapshot.keys()) {
			var recordedAt = keys.get(key);
			if(recordedAt != null && recordedAt <= snapshot.sequence()) {
				keys.remove(key);
			}
		}
	}

	/**
	 * Write the log to a file, replacing what the file held. The same set of
	 * keys always produces the same bytes.
	 *
	 * @throws IOException
	 *   if the file could not be written
	 */
	public synchronized void save(Path file) throws IOException {
		var sorted = new ArrayList<>(keys.keySet());
		sorted.sort(null);

		var builder = ChangeLogStore.newBuilder();
		for(var key : sorted) {
			builder.addKeys(ByteString.copyFrom(key.bytes, key.offset, key.length));
		}

		Files.write(file, builder.build().toByteArray());
	}

	/**
	 * Read a log back from a file {@link #save(Path)} wrote.
	 *
	 * @throws IOException
	 *   if the file could not be read or does not hold a log
	 */
	public static ChangeLog load(Path file) throws IOException {
		var store = ChangeLogStore.parseFrom(Files.readAllBytes(file));

		var log = new ChangeLog();
		for(var key : store.getKeysList()) {
			log.record(new BytesRef(key.toByteArray()));
		}

		return log;
	}

	/**
	 * The documents the log named when the snapshot was taken. Handed back to
	 * {@link #forget(Snapshot)} once every key has been replayed.
	 *
	 * @param sequence
	 *   position in the log the snapshot was taken at
	 * @param keys
	 *   primary key terms of the documents, in no particular order
	 */
	public record Snapshot(long sequence, ImmutableList<BytesRef> keys) {
	}
}
