# Paginating search results

Use this guide to paginate search results for infinite scrolling, page navigation, or complete data exports.

To set where results start, specify at most one of `offset`, `after`, or `before` in your request. Choose the parameter based on your use case:

- Use cursors (`after` and `before`) for infinite scrolling or walking every result.
- Use `offset` to jump directly to a result index.
- Use `pages` to render numbered pagination.

## Following cursors

Cursors carry the position of the hit where the result window ended rather than a count. Following cursors costs the same at any depth and has no page depth cap. Use cursors for infinite scrolling and for walking every result.

To paginate with cursors:

1. Send your initial search request with a `limit`.
2. Find the opaque cursor strings in the response:
   - `next` continues past the current result window.
   - `previous` precedes the current result window.
3. Send the cursor back in your next request using `after` or `before`:

   ```json
   { "query": [ ... ], "limit": 20, "after": "AW8..." }
   ```

When using cursors, keep the following behaviors in mind:

- If you change the sort order, the server rejects the cursor with `search:cursor:sort_mismatch`. You can change the query while keeping the position.
- If you reach a response through a cursor, the response omits `page.offset` because no results were counted to determine an offset.

## Skipping with an offset

To skip a specific number of results, set the `offset` parameter in your request:

```json
{ "query": [ ... ], "limit": 20, "offset": 40 }
```

Skipping costs as much as ranking, so the depth an offset can reach is capped by `SEARCH_MAX_PAGE_DEPTH` (10000 by default). If a request exceeds this cap, the server rejects the request with `search:page:too_deep`. To retrieve results past the cap, follow cursors instead.

## Rendering numbered pages

To render a user interface pager with numbered pages:

1. Add `"pages": {}` to your search request. If you want to limit how many page entries return, set the `max` field:

   ```json
   { "query": [ ... ], "limit": 20, "offset": 0, "pages": { "max": 9 } }
   ```

   Requesting pages calculates an exact total match count because pages cannot be numbered against a lower bound. Numbered pages require a numbered position, so combine `pages` with `offset` or a page entry's cursor, but not with `after` or `before`.

2. Inspect the `page.pages` object in the response. The response splits entries into `start`, `middle`, and `end` runs so that you can render ellipses at run boundaries, such as `1 2 3 … 7`.
3. Use the cursor in a page entry to fetch that page. A page cursor is a count, so it works across different sorts and stays under the depth cap. The server does not offer pages past the cap, preventing navigation to jumps that would be refused.

## Counting matches without fetching

To return only the number of matching documents without fetching results:

1. Set `limit` to `0` in your request.
2. If you need an exact count instead of the default lower bound, add `"total": "exact"` to the request.
