# Roll out a definition change

Some changes to a definition reach documents that are already indexed, and some
do not. Turning on `matching` for a field writes a Lucene field no indexed
document has; a changed analysis chain, an edited synonym set or a new locale is
applied when a value is indexed and never afterwards. Applied in place, these
leave searches returning less than they should with no error anywhere.

Roll such a change out by filling a new generation and promoting it. Callers go
on using the index by name and never learn that it happened. The background is
in [Generations](../explanation/generations.md).

## Decide whether you need this

You do not, if the change only affects documents indexed from here on and the
index is empty, or if you are about to reindex everything anyway.

You do, if the change alters how existing values would have been indexed:

- a field gaining `matching`, `filter`, `sort`, `facet` or a vector
- a different analyzer, preset, tokenizer or token filter
- a changed or newly referenced stopword list or synonym set
- a new locale on a field, or a changed locale fallback chain
- different vector dimensions

## Roll it out

Send the whole definition you want, at the generation you want it in. The index
goes on answering from the generation it has.

```http
PUT /v1alpha1/admin/indexes/products@2
Content-Type: application/json

{
  "fields": {
    "id":    { "type": "string", "primaryKey": true, "required": true },
    "title": { "type": "string", "matching": { "typoTolerance": {} } },
    "brand": { "type": "string", "filter": {}, "facet": {} }
  }
}
```

Index the documents into it, exactly as into any index, and commit:

```http
POST /v1alpha1/indexes/products@2/documents
POST /v1alpha1/admin/indexes/products@2/actions/commit
```

Check it before anyone else sees it. A generation is searchable by name while
another is live, so the new one can be searched next to the old:

```http
POST /v1alpha1/indexes/products@2/search
POST /v1alpha1/indexes/products/search
```

Promote it when it looks right:

```http
POST /v1alpha1/admin/indexes/products@2/actions/promote
```

Searches for `products` are answered from generation `2` on the node that served
the promotion at once, and on every other node within
`INDEXES_REFRESH_INTERVAL`.

## Undo it

Promote what was live before. Nothing a caller holds changed, so nothing has to
change back:

```http
POST /v1alpha1/admin/indexes/products@1/actions/promote
```

## Clean up

Keep the previous generation until you are confident, then remove it:

```http
DELETE /v1alpha1/admin/indexes/products@1
```

Until it is removed it costs storage, and a node that has pulled it costs local
disk for it too. The generation an index answers from cannot be removed - promote
another one first.

## Keys do not change

A key granted `products` follows the index across the rollout and can neither
search nor list a generation by name. The key doing the rollout needs
`products@*` as well - see [patterns and
generations](../reference/auth.md#patterns-and-generations).
