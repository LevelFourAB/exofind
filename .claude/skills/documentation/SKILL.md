---
name: documentation
description: House rules for writing or revising documentation in this repo — the Diátaxis prose under docs/, Javadoc, OpenAPI @Schema descriptions, protobuf comments, and body comments. The baseline is the Google developer documentation style guide. Load before adding or editing a page under docs/, writing or revising a doc comment, changing a @Schema description, commenting a .proto field, or reviewing someone else's prose or comments; also when asked to "document", "add docs", "write doc comments" or "clean up comments".
---

# Documentation guidelines

The baseline is the [Google developer documentation style
guide](https://developers.google.com/style). Where this file is silent, follow
Google. Where this file differs, follow this file.

Where a rule here and an existing page disagree, read the page before you
change it. The page can be right and this file out of date.

## Before you write

Answer three questions:

1. **Which surface?** A page under `docs/`, a Javadoc comment, a `@Schema`
   description, a protobuf comment, or a comment inside a method. Each has its
   own reader and its own section below.
2. **Which Diátaxis mode?** For `docs/`, the directory decides it. You do not
   blend modes to be helpful.
3. **What do you actually know?** State only what you verified in the code. A
   fact you assumed is a bug that outlives you.

## Voice

These rules hold on every surface that carries prose:

- Address the reader as "you". Never "we", "our", or "the user".
- Use active voice and present tense. "The server returns an error", not "will
  return".
- One idea per sentence. Split a sentence that carries two.
- Use the ordinary word: "use" not "utilize", "start" not "initiate", "before"
  not "prior to", "about" not "regarding".
- Write "for example" and "that is". Never `e.g.` or `i.e.`.
- Expand an abbreviation on first use in a file, then use the short form.
- Use "they" as the singular pronoun for a person.
- Use the serial comma: "define an index, add documents, and run queries".
- Do not write "please" in an instruction.

Do not write these:

- **Filler**: "note that", "it's important to", "simply", "just", "basically",
  "in order to" (write "to"), "under the hood".
- **Empty superlatives**: "robust", "powerful", "seamless", "elegant", "easy",
  "blazingly fast". The reader cannot check them.
- **Reassurance**: the reader is not anxious. Do not comfort them.
- **Literary register**: "pushes no more", "goes on following", "whatever the
  hints say". Write the sentence you would say out loud to a colleague.
- **The trailing significance clause**: a fact, a comma, then "which is what
  keeps ...". Make it a second sentence or cut it.
- **The cleft**: "The registry is what decides" for "The registry decides".
- **Contrast nobody asked for**: "X rather than Y", where no reader expected Y.
- **Compressed domain shorthand**: a domain verb standing in for a whole event
  ("as soon as it claims them"), or a verb turned into a noun to save words
  ("an idle claim", "opens ahead"). Name who does what to what: "when a node is
  given an index to write". Write the event out in full before you use a short
  form for it.

## Prose under `docs/`

### Structure

Shape every page the same way:

- One H1 per file. H2 and H3 below it, and do not skip a level.
- Sentence case in every heading: a capital on the first word and proper nouns
  only.
- Start a section with what it is about.
- Keep a paragraph to three sentences where you can.
- Introduce a list, a table, or a code block with a sentence ending in a colon.
- Keep the items of one list parallel in grammar and length.
- Use a numbered list where the order matters, for steps to follow or checks to
  run in sequence. Start a step with its verb: "Run", "Open", "Copy". Use a
  bulleted list for everything else.

### The four modes

The directory a page sits in decides its mode and the form of its H1:

| Directory           | Mode        | H1 form                            | Example                            |
| ------------------- | ----------- | ---------------------------------- | ---------------------------------- |
| `docs/tutorials`    | Tutorial    | Gerund                             | `# Getting started`                |
| `docs/how-to`       | How-to      | Gerund                             | `# Indexing documents`             |
| `docs/reference`    | Reference   | Noun phrase naming the thing       | `# Field types`                    |
| `docs/explanation`  | Explanation | Noun phrase naming the subject     | `# Synchronization`                |

An imperative H1 ("Make a write visible to search") is drift. Write a how-to
with a gerund.

What each mode owes its reader:

- **Tutorial**: one path that works, start to finish, with a stated outcome in
  the opening paragraph and a prerequisites section before the first step.
- **How-to**: one task for someone who already runs the engine. State the
  prerequisites, including the permissions the API key needs. Where the task
  has real alternatives, present them as numbered options and say how to
  choose.
- **Reference**: what is. Tables of fields, settings, and codes. No rationale,
  no walkthrough.
- **Explanation**: why it is that way. Rationale, trade-offs, and the failure
  a design prevents. This is the only place rationale belongs.

### Conventions

Keep to these conventions:

- **Callouts**: `**Note:**` at the start of a paragraph. It is the only
  admonition. Do not invent "Warning" or "Tip".
- **Defaults**: give the value in parentheses after the setting —
  `` `EXOFIND_INDEXES_REFRESH_INTERVAL` (default: `30s`) ``.
- **Tables**: a header row, then `| --- |` separators. Give a Description
  column a full sentence.
- **Code fences**: always carry a language — `shell`, `json`, `http`, `yaml`.
- **Links inside `docs/`**: relative and ending in `.md`, such as
  `[Configuration reference](../reference/configuration.md)`. Link text names
  the target. Never "here".
- **Links from Java or protobuf**: absolute, to `https://exofind.dev/...`.
  Those comments are read outside the docs tree.
- **Line width**: match the file you are editing. Some files wrap near 80
  columns and others keep a paragraph on one line. Never reflow a file to
  change its wrapping — it buries the real change in the diff.
- **Indentation**: two spaces, per `.editorconfig`.

### Publishing

The website reads `docs/` directly and builds its sidebar from
`docs/README.md`. A new page is two changes: the file, and a line in
`docs/README.md` under the right heading, with a one-sentence summary of what
the page gets the reader.

Check a change with `mise run site`.

## Javadoc

The reader is a consumer who can see the signature and nothing else.

**The boundary test**: state only what a caller can observe. **The rewrite
test**: a sentence that stops being true when the body is rewritten was never
part of the contract — delete it, or move it into the method body as a comment.

Rules:

- Open with one summary sentence, then a blank line. Later paragraphs open with
  `<p>`. The first sentence stands alone in class listings.
- Never restate the signature. Delete a `@param` that expands the parameter
  name into a sentence, and a `@return` that names the return type. No tag
  beats an empty tag.
- Document every failure with `@throws`, naming the exception type and the
  condition. Unchecked exceptions count.
- Document concurrency on every public type that holds state. It is the fact
  most often needed and least often written.
- Document what the signature cannot carry: units and ranges, ordering
  guarantees, whether the call blocks or does I/O, who closes what, what an
  empty or absent argument means, and cost when it would surprise a caller.
- `{@link}` a type on first use, `{@code}` for literals and parameter names.
- `@deprecated` carries the migration, not only the notice.
- `@implNote` is the one place an implementation fact may appear, because it is
  marked as something a caller must not rely on.
- Rationale goes in the commit message or `docs/explanation/`, never here.

A class comment that carries its weight:

```java
/**
 * Definition of a single field in an {@link IndexDefinition}.
 *
 * <p>Fields are structured as a tagged union, where {@code type} selects the
 * field type and the properties available on it:
 * ...
 * <p>Field usages are opt-in. Adding an empty configuration object enables a
 * usage with engine defaults, allowing options to be added without changing
 * existing request payloads.
 */
```

## OpenAPI `@Schema` descriptions

These are Javadoc's published twin: Markdown, read by API users on the site and
by anyone generating a client. Follow the docs voice, not the Javadoc voice:

- Write them as Java text blocks, with a trailing `\` to join lines.
- Say what the property is and what setting it does. Give the default.
- Link on to the reference page with an absolute `https://exofind.dev/` link.
- Put a shared description in a constant so one wording serves every type that
  has the property.
- An example on a parameter or an `@ExampleObject` on a body is rendered by the
  site. An example on a `@Schema` is not — it reaches generated clients only.

After changing any annotation the API document is built from, run
`mise run site:openapi`. Nothing compares `website/public/openapi.yaml` with
the build output, so until you do, the site publishes the previous API.

## Protobuf comments

The protos are a storage format, not an API. The reader is judging, possibly
years later, whether a stored value can still be read. Write for that reader:

- Use block comments, `/* */`.
- A file-level comment states the compatibility rules the file lives under.
- Say what presence means: that setting a message turns a capability on, or
  that an absent scalar means the engine picks the default.
- Give units, encodings, and value spaces: a `string` that is a BCP 47 tag, an
  `int64` that is epoch milliseconds. The type says none of it.
- A `reserved` number keeps a comment saying what it used to mean.

A field comment that gives presence, encoding, and lifetime:

```proto
/*
 * BCP 47 tag of the locale the values were analyzed under. Absent means the
 * field's default locale. A document keeps the tag it was indexed with even
 * after the definition changes.
 */
optional string locale = 4;
```

## Comments inside a method

The reader is a maintainer about to change this code. Comment the why, never
the what. If the code does not say what it does, fix the code.

Write one only when a maintainer would otherwise simplify the code and break
it: a non-obvious ordering dependency, a workaround for an upstream bug (link
it), a deliberate break from the surrounding pattern, or a performance decision
with a measurement behind it. For example:

```java
// Reset before close: close flushes, and a flush of stale state re-enqueues
// the batch. See upstream issue #4412.
batch.reset();
```

## Revision pass

Run this over anything you wrote or are reviewing:

1. Does the reader learn something the signature, the table, or the code does
   not already tell them? If not, delete it.
2. Is every sentence still true after the implementation is rewritten?
3. Which sentence can go with the least loss? Delete it. Repeat until the next
   deletion would cost real information.
4. Is any sentence about the author rather than the reader? Move it to the
   commit message.
5. Which required facts are missing — errors, units, concurrency, defaults,
   cost?
6. Does the longest sentence hold more than two clauses? Split it.
7. Would you say each sentence out loud to a colleague? Rewrite the ones you
   would not.
8. Read each sentence as if to someone who knows the product but has not read
   this code. If they cannot say who does what to what, unpack it.

Then grep what you changed:

```shell
grep -nEi ', which (is|was|are) what|\bis what\b|\brather than\b|\b(e\.g\.|i\.e\.)\b|\b(simply|just|easily|robust|seamless|powerful)\b|\bwe\b|\bwill\b' <files>
```

A hit is a candidate, not a verdict. Quoted bad examples and "just" in the
temporal sense ("a document you just indexed") are fine.

The grep does not catch compressed domain shorthand. Those phrases are short
and grammatical, so step 8 is the only check that finds them.
