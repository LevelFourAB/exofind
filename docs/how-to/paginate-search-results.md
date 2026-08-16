# Paginate search results

Where results start is said with at most one of `offset`, `after` and
`before`. Which to use depends on what the client is - an infinite scroll,
a pager with numbered pages, or an export walking everything.

## Follow cursors

Every response hands out opaque cursors: `next` continues past the window it
came from, `previous` is the window preceding it. Send them back as `after`
and `before`:

```json
{ "query": [ ... ], "limit": 20, "after": "AW8..." }
```

The cursors carry the position of the hit the window ended at rather than a
count, so following them costs the same at any depth and is never capped.
This is the right tool for infinite scroll and for walking every result.

Two rules:

- A cursor is tied to the sort it was handed out under and refused under
  another (`search:cursor:sort_mismatch`) - the position it carries means
  nothing there. Changing the query while keeping the position is fine.
- A response reached through a cursor leaves out `page.offset`, as nothing
  was counted to know one.

## Skip with an offset

```json
{ "query": [ ... ], "limit": 20, "offset": 40 }
```

Skipping costs as much as ranking, so how deep an offset may reach is capped
by `SEARCH_MAX_PAGE_DEPTH` (10000 by default) - a request past the cap is
refused with `search:page:too_deep`. Following cursors is the way past it.

## Render numbered pages

Ask for `"pages": {}`, with `max` optionally bounding how many entries come
back:

```json
{ "query": [ ... ], "limit": 20, "offset": 0, "pages": { "max": 9 } }
```

The response's `page.pages` is split into `start`, `middle` and `end` runs
so a pager renders `1 2 3 … 7` with the ellipses exactly where a run
boundary falls. Each entry carries a cursor that fetches its page; a page's
cursor is a count, so it keeps working whatever the sort and stays under the
cap. Pages past the cap are simply never offered, so a pager never renders
a jump that would be refused.

Asking for pages implies an exact total, as pages cannot be numbered against
a lower bound. Numbered pages need a numbered position, so they combine with
`offset` or a page's own cursor but not with `after`/`before`.

## Count without fetching

A `limit` of `0` answers only how many documents match. The total is a cheap
lower bound by default; `"total": "exact"` counts every match.
