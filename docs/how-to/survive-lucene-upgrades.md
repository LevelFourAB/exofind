# Survive Lucene upgrades

Lucene reads an index created by its current major version and the one
before it. An index in object storage outlives that window, so an index left
alone across two major upgrades becomes unreadable - with its files intact.
The engine tracks this and warns in time; this page is what to do with the
warning. The background is in
[Lucene compatibility](../explanation/lucene-compatibility.md).

## Watch `luceneCompatibility`

Every index reports it in its status:

```http
GET /v1alpha1/admin/indexes/products
```

```json
"status": {
  "state": "USABLE",
  "luceneCompatibility": "ENDING",
  "luceneCreatedMajor": 9
}
```

`CURRENT` needs nothing. `ENDING` is the warning: the index is readable now
but the next Lucene major drops it. An `ENDING` index is also said once in
the log of a node holding it, while there is still a readable copy to
reindex from.

## Reindex an `ENDING` index before upgrading

Before upgrading the nodes across a Lucene major:

1. List the indexes and note every one reporting `ENDING`.
2. For each, add a generation carrying the same definition, and index the
   documents into it. An index that keeps full documents can be reindexed
   from itself; one with `"source": "none"` needs the documents from where
   they originally came.
3. Promote the new generation and delete the old one.

This is the rollout in [Roll out a definition
change](roll-out-a-definition-change.md), with the definition staying as it
was. Indexing rewrites the files under the current major, so the new
generation reports `CURRENT`, and callers never learn that it happened.

## If it is already too late

An index past the window reports state `INCOMPATIBLE` and
`luceneCompatibility: "UNREADABLE"`. It is refused while pulling, before a
single file is fetched, and cannot be opened on any current node - and
unlike `UNSUPPORTED`, upgrading moves further away, not closer. The
documents only come back by indexing them again from their original source
into a new generation. If no such source exists, a node old enough to read
the index can still be started against the same storage to read the documents
out.
