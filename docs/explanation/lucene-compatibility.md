# Lucene compatibility

An index in Exofind can outlive the code that is able to read it. This page
explains why that is, and why the engine handles it the way it does.

## The window

Lucene opens an index created by the current major version and the one
before it. An index that lives on a node's disk for the lifetime of a
deployment rarely notices; an index that lives in object storage for years
does. Upgrade the nodes across two majors and the files are still there,
intact, and unopenable.

Exofind therefore records the major Lucene version an index was created with
in the manifest, next to the file listing. Recording it there rather than
inside the Lucene files means the question "can this build read this index?"
is answered before a single segment file is downloaded - an index that has
fallen out of the window is refused while pulling, not after.

The judgment compares against Lucene's own `Version.MIN_SUPPORTED_MAJOR`
rather than a constant in Exofind, so upgrading Lucene moves the window
without anyone remembering to update a number.

## Two ways of being unreadable

The admin API reports an unreadable index as one of two states, and the
difference is which direction fixes it:

- `UNSUPPORTED`: the definition uses a capability this build does not know.
  A newer node wrote it; upgrading this node fixes it. Definitions record
  the features they need precisely so that an older node refuses cleanly
  instead of indexing without the part it does not understand.
- `INCOMPATIBLE`: the Lucene files are older than the window. Upgrading the
  node moves *away* from being able to read them. The only way back is
  reindexing the documents into a new index.

Because `INCOMPATIBLE` has no cheap recovery, the engine warns while there
is still time: an index one major from the edge is reported as `ENDING` in
`luceneCompatibility`, while a readable copy still exists to reindex from.
[Survive Lucene upgrades](../how-to/survive-lucene-upgrades.md) is the
task-side of this page.
