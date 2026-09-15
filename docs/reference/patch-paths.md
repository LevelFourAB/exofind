# Change paths

A change path is the syntax used in the keys of a change object to name a place in a document or in search settings.

## Supported endpoints

The following endpoints use change paths:

| Endpoint | What a path names |
| --- | --- |
| `PATCH /v1alpha1/indexes/{name}/documents/{key}` | A place in one document. |
| `POST /v1alpha1/indexes/{name}/documents/actions/update` | A place in each document of a batch. |
| `PATCH /v1alpha1/admin/indexes/{name}/settings` | A place in the search settings. |

For more information on these endpoints, see the [Documents API](documents-api.md) and the [Admin API](admin-api.md). For a task-based guide, see [Updating parts of documents](../how-to/update-parts-of-documents.md).

## Change object behavior

Change objects apply their properties in the order they are written:

- A path with a value replaces what the path names.
- A path set to `null` clears what the path names.
- A path that is omitted leaves what it names unchanged.
- If two paths name the same place, the later path replaces the value set by the earlier path.

## Syntax

A path consists of names joined by `.`. A name can carry an optional selector in brackets.

| Path | Names |
| --- | --- |
| `price` | The place itself. |
| `dimensions.width` | A place inside it. |
| `tags[]` | A value added to the ones the place holds. |
| `title[sv]` | The value identified by one word. |
| `variants[sku=V-2]` | The values whose `sku` reads as `V-2`. |
| `variants[sku=V-2].price` | One name inside those values. |
| `fields.variants\.colour` | A name holding a `.` of its own. |

### Selector forms

Selectors match values by what they hold rather than by their position in a list. Reordering a list does not change which value a selector targets.

The syntax supports three selector forms:

- **Empty brackets (`[]`):** Adds a value. The operation matches nothing, replaces no values, and places the added value last.
- **Key-value match (`[field=value]`):** Names values whose `field` reads as `value`. Values are compared as text, so a value stored as the number `2` matches the selector `[field=2]`. Only the first unescaped `=` splits the selector, so `[sku=a=b]` matches a `sku` of `a=b`.
- **Single word (`[word]`):** Names a value by one word, such as `[sv]` or `[V-2]`. The meaning of the word depends on the target.

### Escaping

A backslash (`\`) escapes the character immediately following it in both names and selectors.

Escape the following characters:

- In a name: `.`, `[`, and `\`.
- In a selector: `]`, `\`, and an `=` in single-word form.

An `=` in the `[field=value]` form requires no backslash after the first unescaped `=`, because the first `=` splits the selector.

All other characters represent themselves. Hyphens and locale tags such as `en-GB` require no escaping.

Because JSON strings use backslashes as escape characters, write each backslash twice in a JSON request body: `"fields.variants\\.colour"` or `"variants[sku=a\\]b]"`.

## Single-word selectors

The target endpoint determines what a single word in brackets means:

| Target | Meaning of a single word |
| --- | --- |
| A document | A BCP 47 language tag on a locale-specific field, or the declared key on an object field. A field is never both. |
| Search settings | The key the list declares: `field` on `ranking.signals` and `ranking.tieBreakers`, and `value` on `fields.<name>.values`. Every other list declares none, and a word on one returns `settings:patch:key_unsupported`. |

In document fields, a BCP 47 tag resolves against the variants declared on the field. For example, `title[nb-NO]` changes a field that declares `no`. If the field declares no variant for the tag, the endpoint returns `document:locale_unknown`. For more details on fields, see [Field types](field-types.md).

## Target depth and creation

A path replaces what it names based on how deeply it reaches:

- `variants` replaces every value in the field.
- `variants[sku=V-2]` replaces values whose `sku` matches `V-2`, leaving other values unchanged.
- `variants[sku=V-2].price` replaces only the `price` field inside matching values, leaving the rest of the object unchanged.

A value replaced in place retains its position in the list.

Paths follow these creation rules:

- Paths automatically create missing objects that they reach through.
- A path ending in `[]` with a value creates a list if none exists.
- A selector never creates the value it names. If a selector matches no stored value, the endpoint returns `no_match`.

## Target differences

Document paths and search settings paths differ in the following ways:

- **Selector count:** A path into a document supports one selector. A path into search settings supports a selector at each name.
- **Name resolution:** A document resolves names before and after a selector as a dotted path through object values against the index definition. Because declared field names contain no `.`, escaping a dot reaches the same place as writing it without an escape.
- **How many values a key names:** A key of a document names at most one value, because a document holding two values under one key is refused when it is indexed. A key of `ranking.signals` names every signal reading that field, because two signals may read one field with different shapes. A key of `ranking.tieBreakers` and of `fields.<name>.values` names at most one.
- **Which lists declare a key:** An index definition declares one per object field, with `"key": "sku"`. The search settings have a shape no client defines, so the lists that carry a key are fixed, and are listed above.

## Error codes

Endpoints return errors in two prefix families: `document:patch:*` for documents, and `settings:patch:*` for search settings. Both families return `400 Bad Request` and share the same error arguments and last segments.

For general error handling, see [Errors](errors.md).

| Error suffix | Cause |
| --- | --- |
| `path_invalid` | The key cannot be parsed as a change path. |
| `field_unknown` | The path reaches a name that is not declared. |
| `not_an_object` | The path reaches inside a value that holds no fields. |
| `selector_required` | The path reaches into a list without specifying which value. |
| `selector_unsupported` | The path specifies a selector on a place that holds no list. |
| `match_not_an_object` | The path matches on a field inside values that are not objects. |
| `key_unsupported` | The path names a value by a single word on a field that declares no key. |
| `add_unsupported` | The path adds a value using `[]` to a place that holds a single value. |
| `add_reaches_inside` | The path attempts to reach inside a value that the same change adds. |
| `no_match` | The selector names no stored value. |

### Target-specific error codes

The following error codes occur on specific targets:

| Code | Cause |
| --- | --- |
| `settings:patch:value_invalid` | The path names a settings location that cannot accept the provided value. Documents report field validation errors per type, such as `document:number:value_invalid`. |
| `document:locale_unknown` | The document field holds no variant matching the specified locale tag. |
