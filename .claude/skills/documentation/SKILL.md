---
name: documentation
description: House rules for writing or revising documentation in this repo — Javadoc, protobuf comments, body comments, and the Diátaxis prose under docs/. Load before writing a doc comment on a new public type or method, editing an existing one, adding a comment inside a method or a .proto file, or adding to docs/; also when reviewing someone else's comments or prose, or when asked to "document", "add docs", "write doc comments" or "clean up comments".
---

# Documentation & comment guidelines

Documentation in this codebase is written for a stranger arriving in 6–12
months, and every location has exactly one reader. A doc comment on an exported
identifier serves a consumer who can only see the API; a comment inside a
function body serves a maintainer who is about to change that code; `docs/`
serves whichever Diátaxis mode the file lives in. Writing for the wrong reader
is the failure this document exists to prevent — most commonly by leaking
implementation detail and decision history into a consumer's doc comment, where
it goes stale the moment the implementation changes.

Two rules carry most of the weight. **The boundary test** (§1): a consumer doc
may only state facts observable from the caller's side. **The rewrite test**
(§2): a sentence that stops being true when the body is rewritten was never part
of the contract. Rationale, alternatives considered, and benchmarks are valuable
— they belong in the commit message or in `docs/explanation/`, not on the API
surface.

The sections below are ordered as a working sequence: §1–§4 decide what a
comment may contain, §5–§9 what it must contain and how it reads per surface,
§10–§12 the cutting pass. Sections 5 and 11 are the two to reread when a comment
feels finished but long.

---

## 1. Know which reader you are writing for

Every comment has exactly one audience. Decide before writing; never mix.

| Location                              | Audience                                                           | May contain                                                     |
| ------------------------------------- | ------------------------------------------------------------------ | --------------------------------------------------------------- |
| Doc comment on an exported identifier | **Consumer** — arrives cold, from outside the package, with a task | Contract only: behaviour, inputs, outputs, errors, cost, safety |
| Comment inside a function body        | **Maintainer** — is changing this code                             | Why this approach and not the obvious alternative               |
| Prose docs (`/docs`, README)          | Depends on Diátaxis mode — see §3                                  | Per mode                                                        |
| Commit message / PR description       | Reviewer, archaeologist                                            | All rationale, alternatives considered, benchmarks, trade-offs  |

**The boundary test.** A consumer doc may only state facts observable from the
caller's side of the API. If the reader could not detect the fact by calling the
function, it does not belong there.

```java
// BAD — describes the implementation, not the contract
/**
 * Cache stores entries in a ConcurrentHashMap, which was the only thing fast
 * enough for our write-heavy load. A synchronized map was the honest option
 * but it contended badly under benchmark.
 */

// GOOD — describes the contract
/**
 * A bounded, in-memory key/value store with LRU eviction. Safe for
 * concurrent use from multiple threads.
 */
```

**Where the deliberation goes.** Reasoning about alternatives is genuinely
valuable — it just belongs in the commit message, not the API surface. When you
find yourself writing "we chose X because Y", move that sentence to the commit
body and delete it from the doc.

---

## 2. The rewrite test

> If a sentence stops being true when the implementation is replaced, it was
> never part of the contract.

Apply this to every sentence in a doc comment. Anything that fails is either
deleted or demoted to a body comment.

---

## 3. Diátaxis mode is an input, not a choice

The mode is determined by _where you are writing_, and you do not get to blend
modes to be helpful.

- **Doc comments are always Reference.** Austere, complete, impersonal. No
  encouragement, no rationale, no tutorial voice, no "you'll probably want to".
- **Tutorial and How-to** live in `/docs`. They may address the reader as "you"
  and may state goals.
- **Explanation** is where rationale, history, and trade-offs live. Link to it
  from a doc comment rather than inlining it.

Sparing exception: a short trailing paragraph (or `@apiNote`) may carry one
piece of rationale when it changes how a consumer uses the API — e.g. "This is
O(n) in the number of subscribers; prefer {@link #publishBatch} above ~100."

---

## 4. Never restate the signature

A doc comment that re-expresses the identifier and types in English carries zero
information and is worse than no comment, because it makes readers stop and read
it.

```java
// BAD
/**
 * Gets a user by ID.
 *
 * @param id the ID of the user
 * @return the user
 */
```

Delete any `@param` whose text is the parameter name expanded into a sentence.
Delete any `@return` that restates the return type. Prefer no tag to an empty
tag.

Never repeat a type in prose — the signature already says it and prose drifts.
If a constraint can be carried by the type, carry it there instead of
documenting it: an enum instead of a string, `Optional` instead of a nullable
return, `Duration` instead of a long of milliseconds. A doc comment reading
"must not be null" on every parameter is a smell.

---

## 5. Say the things that are actually missing

Cutting the filler exists to make room for these. Include every item that
applies; a doc comment that omits them is incomplete no matter how long it is.

- **Errors** — which failures, as which exception types, and whether a caller
  is expected to catch them
- **Edge inputs** — behaviour on null, empty collections and strings, absent
  optionals
- **Ownership** — whether arguments are retained, mutated, or copied; who closes what
- **Concurrency** — whether the value is safe for concurrent use
- **Blocking** — whether the call blocks or does I/O; how it responds to
  interruption or cancellation
- **Units and ranges** — milliseconds vs seconds, inclusive vs exclusive, time zone, encoding
- **Ordering** — guaranteed, or explicitly not guaranteed
- **Defaults** — what a zero value or omitted option means
- **Cost** — complexity or I/O, when it would surprise a caller

---

## 6. Examples outrank prose

A compiling example is worth three paragraphs of description.

- Short calls: a `{@snippet}` in the Javadoc.
- Anything longer: a test that exercises the common case, because a snippet
  nobody compiles drifts like prose. The doc comment can name the behaviour and
  leave the demonstration to the test.

Write the example for the common case, not the exhaustive case.

---

## 7. Javadoc specifics

- One-sentence summary first, then a blank line (`<p>` opens each later
  paragraph). The first sentence appears alone in package and class listings,
  so it must stand by itself.
- Document error behaviour with `@throws`, naming the exception type. An
  unchecked exception a caller is expected to handle is part of the contract
  and gets a tag just like a checked one — this codebase signals most failures
  that way (`SyncConflictException`, `IndexInvalidQueryTypeException`).
- Document concurrency safety on every public type that holds state. This is
  the most frequently omitted and most frequently needed fact.
- `{@link}` a type the first time it appears, `{@code}` for literals and
  parameter names.
- `@deprecated` carries a concrete migration path, not just a notice.
- Package comments go in `package-info.java` and describe what the package is
  for, not how it is built.
- `@implNote` is the one place a fact about the current implementation may
  appear on the API surface — it is explicitly marked as something a consumer
  must not rely on. Rationale still goes in the commit message.

```java
// GOOD
/**
 * Parses a duration string such as {@code "1h30m"} or {@code "-250ms"}.
 *
 * <p>Valid units are ns, us, ms, s, m and h. Values may be negative.
 *
 * @throws DurationFormatException if {@code s} is not a valid duration string
 * @throws ArithmeticException if the value overflows {@link Duration}
 */
static Duration parseDuration(String s)
```

---

## 8. Protobuf comments

The protos under `src/main/protobuf` are a storage format, not an API. Their
reader is someone judging, possibly years later, whether a stored value can
still be read or a schema change is compatible — the boundary test still
applies, but the boundary is what is written to disk, not what the engine
currently does with it.

- A file-level comment states the compatibility rules the file lives under,
  the way `definitions.proto` does.
- Document what presence means. When a message being set is what turns a
  capability on, or an absent scalar means the engine chooses the default, say
  so on the field — that contract is invisible in the schema itself.
- Give units, encodings and value spaces on the field: a `string` that is a
  BCP 47 tag, an `int64` that is epoch milliseconds. The type says none of it.
- A `reserved` number keeps a comment saying what it used to mean; the
  reservation is unreadable without it.
- Comments are block `/* */`, matching the existing files.

```proto
// GOOD
/*
 * BCP 47 tag of the locale the values were analyzed under. Absent means the
 * field's default locale. A document keeps the tag it was indexed with even
 * after the definition changes.
 */
optional string locale = 4;
```

---

## 9. Body comments

Comment the **why**, never the **what**. The code already says what it does; if
it does not, fix the code instead of narrating it.

Write a body comment only when a maintainer would otherwise be tempted to
"simplify" the code and break it. Good triggers: a non-obvious ordering
dependency, a workaround for an upstream bug (link it), a deliberate deviation
from the surrounding pattern, a performance decision with a measurement behind
it.

```java
// BAD — narrates the code
// increment the counter
count++;

// GOOD — protects a non-obvious decision
// Reset before close: close flushes, and a flush of stale state
// re-enqueues the batch. See upstream issue #4412.
batch.reset();
```

---

## 10. Prohibited language

**Filler and hedging.** `note that`, `it's important to`, `simply`, `just`,
`basically`, `essentially`, `of course`, `arguably`, `under the hood`,
`in order to` (use "to"), `at the end of the day`.

**Self-narration.** Any phrasing that reports the author's decision process
rather than the code's behaviour: `the only thing that`, `the honest option`,
`we went with`, `it turns out that`, `I chose`, `this felt cleaner`. If the
information matters, it goes in the commit message.

**Empty superlatives.** `robust`, `powerful`, `seamless`, `elegant`, `simple`,
`easy`, `flexible`, `blazingly fast`. These are claims a reader cannot verify
and will resent.

**Comparative asides.** Do not mention alternatives that were considered and
rejected in a consumer doc. There is no alternative in the reader's world.

**Apology and reassurance.** The reader is not anxious. Do not comfort them.

---

## 11. Revision pass

Drafting is not the problem; cutting is. After writing any doc comment, run
this checklist and edit accordingly.

1. Does the reader learn anything the signature does not already tell them? If
   not, delete the whole comment.
2. Is every sentence still true if the body is rewritten from scratch? Delete or
   demote the ones that aren't.
3. Which single sentence can be deleted with the least loss? Delete it. Repeat
   until the next deletion would cost real information.
4. Is any sentence about the author rather than the reader? Move it to the
   commit message.
5. Check §5. Which required facts are missing? Add them.
6. Would a consumer be surprised by anything in production — an error type, a
   blocking call, a mutation, a cost? If so it is undocumented.

Target: shorter than the first draft and containing strictly more information.

---

## 12. Worked corrections

```java
// BEFORE
/**
 * Creates a new client. It takes a config and returns a client. We use a
 * config record rather than a builder because it was the only thing that
 * stayed readable as the option count grew — a builder was the honest
 * option early on but this scales better. The client is robust and handles
 * errors gracefully.
 *
 * @param config the config
 * @return the client
 */
public static Client create(Config config)

// AFTER
/**
 * Returns a Client configured by {@code config}.
 *
 * <p>The returned Client is safe for concurrent use and holds a connection
 * pool; callers must {@link Client#close() close} it. Creation does not
 * dial — the first request establishes the connection.
 *
 * @throws InvalidConfigException if {@code config.endpoint()} is empty
 */
public static Client create(Config config)
```

```proto
// BEFORE
/*
 * The timeout. We went with an int64 here since Duration felt like
 * overkill under the hood.
 */
optional int64 timeout = 3;

// AFTER
/*
 * Time budget for the operation in milliseconds. Absent means the engine's
 * default; zero stored explicitly means no limit.
 */
optional int64 timeout = 3;
```
