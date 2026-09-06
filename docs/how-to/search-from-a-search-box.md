# Searching from a search box

This guide shows you how to connect an application search box to the engine using the `text` clause with `"match": "user"`. Use this guide when text typed by a person goes straight into a query: it covers the punctuation the engine reads, how many of the typed words a document must match, the word that is still being typed, and what to show when the search that ran differs from the search that was typed.

## Prerequisites

Before you begin, ensure that you have:

- A configured index with indexed documents. For more information, see [Defining an index](define-an-index.md).

## Steps

1. Pass raw search text with user match mode:

   To search text typed by a person, set `"match": "user"` on the `text` clause. Send the contents of the search box directly as `text` without stripping punctuation or constructing query strings manually:

   ```json
   {
     "query": [
       {
         "type": "text",
         "text": "running shoes \"trail ready\" -leather",
         "match": "user",
         "fields": { "name": 3, "description": null }
       }
     ]
   }
   ```

   In user mode, the engine evaluates common punctuation without raising parse errors:

   | Typed | Effect |
   | --- | --- |
   | `apple watch` | Two loose words. |
   | `"apple watch"` | The words as an ordered phrase. |
   | `-leather` | Leaves out documents holding the term. |
   | `-"apple watch"` | Leaves out documents holding the phrase. |

   Other punctuation remains normal text. A hyphen inside a word (`e-mail`), a quotation mark inside a word (`it"s`), or a trailing hyphen without a word stay part of the search string. An unclosed quotation mark extends to the end of the text.

   If a query contains only exclusions, it runs against every document in the index and removes what it names. If no terms survive analysis, the query matches no documents. If a quoted phrase targets a field configured only for `autocomplete`, the engine searches the loose words inside the phrase rather than rejecting the query.

2. Select the join mode for search terms:

   Set `join` to control how many typed parts a document must satisfy:

   ```json
   {
     "query": [
       {
         "type": "text",
         "text": "storage layout",
         "match": "user",
         "join": "any",
         "fields": { "title": 3, "heading": 2, "text": null }
       }
     ]
   }
   ```

   - `"all"` (the default): Every loose word and quoted phrase must match. In a product catalogue, adding more search words reduces the number of results returned.
   - `"any"`: Documents matching at least one word or phrase are returned. Use this setting for documentation or articles where a page holding some of the terms is still relevant.

   Excluded terms always apply regardless of the `join` mode. Setting `join` on any match mode other than `"user"` returns `search:clause:join_not_applicable`.

3. Configure prefix matching for live search:

   The `prefix` option controls whether the final word is treated as an incomplete token. By default, `prefix` is set to `"last_token"`, which allows results to update on every keystroke as the person types.

   To disable prefix matching when a search executes only after the person presses Enter, set `"prefix": "off"`:

   ```json
   {
     "query": [
       {
         "type": "text",
         "text": "running shoes",
         "match": "user",
         "prefix": "off"
       }
     ]
   }
   ```

   Prefix matching applies only to the unfinished term at the end of the string. Closed quotes and words typed after closed quotes are treated as complete. Exclusions are never treated as prefixes and do not receive typo tolerance.

4. Display query adjustments to the user:

   Inspect the search response to identify when the executed query differed from the typed input:

   - `relaxed`: When the search as a whole matches no documents, the engine drops words rather than return an empty result set. The response includes `relaxed.dropped` with the removed words and reasons, and `relaxed.text` with the search that ran instead. The response omits `relaxed` under `"join": "any"`, where one word is enough to match and so no word kept documents out of the results.
   - `interpreted`: When the engine extracts filters from the input (such as numeric units or comparative phrases), the response returns `interpreted.filters` listing the extracted filters and `interpreted.text` containing the remaining words. To turn off filter extraction, set `"interpret": "off"`.

   Show these adjustments in your user interface and allow users to dismiss them.

## Confirming the result

Inspect the JSON response from the search endpoint to verify search box behavior:

- `hits`: Contains the matching documents based on the specified `join` mode and field weights.
- `relaxed`: Contains `dropped` terms and the fallback query `text` if terms were dropped to prevent an empty result page.
- `interpreted`: Contains extracted `filters` and the remaining `text` if parts of the input were parsed as filters.

## Related

- [Searching an index](search-an-index.md) - Constructing queries with filters, facets, sorting, and pagination.
- [Reading numbers in the search box](read-numbers-in-the-search-box.md) - Reading numbers and units out of search text as filters.
- [Reading colours and brands in the search box](read-field-values-in-the-search-box.md) - Reading facet field values out of search text as filters.
- [Suggesting what to search for while it is typed](suggest-while-typing.md) - Providing query and completion suggestions.
- [Search API reference](../reference/search-api.md) - Complete syntax, options, and error codes for search clauses.
