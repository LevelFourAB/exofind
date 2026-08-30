# Surviving Lucene upgrades

Lucene reads an index created by its current major version and the one
before it. An index left in object storage across two major upgrades becomes
unreadable, even though its files are intact. The engine tracks this status and
warns you in time.

This guide shows you how to respond to compatibility warnings and reindex an
index before you upgrade nodes across a major Lucene version. For background
information, see [Lucene compatibility](../explanation/lucene-compatibility.md).

## Prerequisites

Before you begin:

- Check that you have access to the admin API.
- If your index uses `"source": "none"`, locate the original source documents.

## Checking index compatibility

Every index reports its compatibility state in its status. To check the status
of an index, send the following request:

```http
GET /v1alpha1/admin/indexes/products
```

The response includes the compatibility status:

```json
"status": {
  "state": "USABLE",
  "luceneCompatibility": "ENDING",
  "luceneCreatedMajor": 9
}
```

A value of `CURRENT` requires no action. A value of `ENDING` warns you that the
index is readable now, but the next major Lucene version drops support for it.
A node holding an `ENDING` index also writes a warning once to its log while a
readable copy is still available to reindex from.

## Reindexing an index before upgrading

Before you upgrade nodes across a major Lucene version, complete the following
steps:

1. List the indexes and identify every index that reports `ENDING`.
2. Add a new generation with the same definition for each `ENDING` index.
3. Index the documents into the new generation:
   - If the index keeps full documents, reindex the documents from the index
     itself.
   - If the index uses `"source": "none"`, index the documents from their
     original source.
4. Promote the new generation and delete the old generation.

This process follows the rollout procedure in [Roll out a definition
change](roll-out-a-definition-change.md), keeping the definition unchanged.

## Confirming the result

Check the status of the new generation. Indexing rewrites the files under the
current major version, so the new generation reports `CURRENT`, and callers
never learn that the update happened.

## Recovering an unreadable index

If an index passes beyond the compatibility window, it reports `state` as
`INCOMPATIBLE` and `luceneCompatibility` as `"UNREADABLE"`. The engine refuses
the index during pulling before it fetches any files, and no current node can
open the index. Unlike with `UNSUPPORTED`, upgrading nodes moves further away
from compatibility.

To recover the documents, use one of the following methods:

- Index the documents again from their original source into a new generation.
- If no original source exists, start a node with a Lucene version old enough to
  read the index against the same storage, and read the documents out.

## Related

- [Lucene compatibility](../explanation/lucene-compatibility.md) - Why an index can outlive the code that reads it.
- [Reindexing into a new generation](reindex-into-a-new-generation.md) - Refilling an index without sending every document again.
- [Admin API](../reference/admin-api.md) - The index status fields that report compatibility.
- [Operating a deployment](operate-a-deployment.md) - Rolling an upgrade across nodes.
- [Reading documents back](read-documents.md) - Reading documents out of an index that is still readable.
