# Object storage requirements

A node in `object` storage mode keeps everything in one bucket of an
S3-compatible object storage. This page states what that storage has to
support. For the settings that name the storage, see
[Object storage](configuration.md#object-storage). For how a node gets the
credentials it signs requests with, see
[Authenticating to object storage](../how-to/authenticate-to-object-storage.md).

## Operations

The node uses the following operations of the S3 API:

| Operation | Used for |
| --- | --- |
| `PutObject` | Writing index files, the manifest of a generation, the index registry, the leadership table, the API keys, search settings, reindex job records, and the mark a deleted index leaves. |
| `GetObject` | Reading those objects back. |
| `HeadObject` | Checking that an object exists, and reading its size and entity tag without its body. |
| `DeleteObject` | Removing one object. |
| `DeleteObjects` | Removing the objects of a deleted index in batches. |
| `ListObjectsV2` | Listing the indexes under the prefix, the objects of one index, and the reindex job records. Listings use continuation tokens, and `/` as the delimiter. |

The node does not use multipart uploads, object copies, object versioning,
object tagging, or lifecycle rules. It does not create buckets.

## Conditional requests

Every object that more than one node can replace is written with a condition,
and every object a node polls is read with one:

| Header | Operation | What the node expects |
| --- | --- | --- |
| `If-None-Match: *` | `PutObject` | The write succeeds only when no object exists at the key. Otherwise the storage answers `412 Precondition Failed`. |
| `If-Match: <entity tag>` | `PutObject` | The write succeeds only when the entity tag of the current object equals the given one. Otherwise the storage answers `412 Precondition Failed`. |
| `If-None-Match: <entity tag>` | `GetObject` | The storage answers `304 Not Modified` without a body when the entity tag is unchanged. |

A storage can answer `409 Conflict` to a conditional write it could not
decide, because another write to the same key was in progress. The node treats
`409` the same as `412`: nothing was written, so it reads the object again and
retries. Amazon S3 answers this way.

A storage that predates conditional writes accepts the write and ignores the
condition. A node that can index checks for this at startup by writing an
object under conditions that do not hold, and refuses to start when the storage
accepts them. The check writes one object under the configured prefix and
removes it again.

## Entity tags

The node compares entity tags for equality only. It does not read them as MD5
digests. The storage has to return an entity tag on `PutObject`, `GetObject`,
and `HeadObject`, and the tag has to change whenever the content of the object
changes. The tag the node keeps for an object on Google Cloud Storage also
holds a generation, see [Google Cloud Storage](#google-cloud-storage).

## Consistency

The node expects the following of a storage:

- A `GetObject` or `HeadObject` after a successful `PutObject` returns the
  object that was written.
- A `GetObject` or `HeadObject` after a `DeleteObject` answers `404 Not Found`.
- A `ListObjectsV2` after a `PutObject` includes the object, and after a
  `DeleteObject` does not.
- A conditional write is decided against the latest version of the object.

## Addressing and signing

The node reaches and signs requests in the following way:

- **Signing**: AWS Signature Version 4, for the region in
  `EXOFIND_STORAGE_REMOTE_REGION` (default: `us-east-1`). The `gcp` source
  sends an access token and signs nothing.
- **Addressing**: path style when `EXOFIND_STORAGE_REMOTE_URL` is set, so the
  bucket is part of the path and the storage needs no wildcard DNS. Without a
  URL, the node reaches Amazon S3 with the bucket in the host name.
- **Checksums**: the node adds a checksum only to the operations that require
  one, such as `DeleteObjects`. It does not send checksums as trailers with the
  `aws-chunked` content encoding, and it does not require checksums on
  responses.
- **Retries**: the node makes each request once. The code that made the
  request decides whether to make it again, so a storage that answers
  `503 Slow Down` sees no automatic retry from the client library.

## Google Cloud Storage

The node uses the Google Cloud Storage spelling of a conditional write when the
host of `EXOFIND_STORAGE_REMOTE_URL` is `storage.googleapis.com` or ends with
`.storage.googleapis.com`.

Google Cloud Storage accepts `If-Match` and `If-None-Match` on `GetObject` and
`HeadObject` only, not on writes. The node sends the following headers instead:

| Header | Operation | Sent as |
| --- | --- | --- |
| `If-None-Match: *` | `PutObject` | `x-goog-if-generation-match: 0` |
| `If-Match: <version>` | `PutObject` | `x-goog-if-generation-match: <generation>` |
| `If-None-Match: <version>` | `GetObject`, `HeadObject` | `If-None-Match: <entity tag>` |

The entity tag for an object on Google Cloud Storage holds two parts, formatted
as `"<entity tag>@<generation>"`. A read compares the entity tag part, and a
write states the generation part in `x-goog-if-generation-match`. If a write is
answered without an `x-goog-generation` header, the node fails with an error
naming `x-goog-generation`, because the version of the written object is
unknown and every later write of it would be refused.

A tag that names no generation is sent as generation `1`, which no object
carries, so the write is refused. A client that invents a value for an
`If-Match` header of the API gets a conflict.

Google Cloud Storage limits write rates to about one write per second per
object. A deployment writes two objects on a schedule:

- The leadership table: one object for the whole deployment, written by every
  indexer candidate at one third of `EXOFIND_INDEXER_LEASE_DURATION` (default
  `30s`, so every 10 seconds).
- The manifest of an index: written by the node that writes that index.

Raise `EXOFIND_INDEXER_LEASE_DURATION` when a deployment has enough indexer
candidates to write the leadership table more than once a second.

## Storages

Amazon S3 and SeaweedFS enforce conditional writes, and the engine is tested
against both. For every other storage, including Google Cloud Storage, the
startup check on a node that can index is the test: a node that starts as an
indexer candidate runs against a storage that enforces them.
