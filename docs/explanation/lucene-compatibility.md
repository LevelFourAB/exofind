# Lucene compatibility

An index in Exofind can outlive the code that can read it. Why does an index become unreadable over time, and how does the engine handle compatibility across versions?

## The window

Lucene opens an index created by the current major version and the previous major version. An index that lives on a node's disk for the lifetime of a deployment rarely reaches this limit. However, an index that remains in object storage for years can outlive the supported versions. If you upgrade nodes across two major versions, the index files remain intact but unopenable.

To prevent downloading unreadable files, Exofind records the major Lucene version used to create an index in the manifest next to the file listing. Storing the version in the manifest allows Exofind to verify compatibility before downloading segment files. An index that falls outside the supported window is rejected during the pull operation rather than after.

The engine compares the index version against Lucene's `Version.MIN_SUPPORTED_MAJOR` instead of a hardcoded constant in Exofind. Upgrading Lucene moves the compatibility window automatically.

## Two ways of being unreadable

The admin API reports an unreadable index in one of two states, depending on which direction resolves the issue:

- `UNSUPPORTED`: The definition uses a capability that this build does not support. A newer node wrote the definition. Upgrading this node resolves the issue. Definitions record the features they require so that an older node rejects them cleanly instead of indexing without the required capability.
- `INCOMPATIBLE`: The Lucene files are older than the supported window. Upgrading the node moves further away from being able to read the files. The only resolution is to reindex the documents into a new index.

Because resolving an `INCOMPATIBLE` state requires reindexing, the engine warns before an index becomes unreadable. When an index is one major version away from the compatibility limit, Exofind reports its `luceneCompatibility` value as `ENDING` while a readable copy still exists to reindex from.

For instructions on upgrading, see [Survive Lucene upgrades](../how-to/survive-lucene-upgrades.md).
