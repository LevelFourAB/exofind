# Relevance

When a search does not say how to order its results, the order is relevance:
how well each document answers what was asked. That order is built in layers,
and the layers are separate because they answer different questions - how good
the match itself was, where it was found, what the document is worth on its
own, and what to do about the documents left indistinguishable. This page is
about how they fit together. What each setting is called, and what values it
takes, is in [field types](../reference/field-types.md#ranking) and the
[search API](../reference/search-api.md#signals).

## What a match is worth

The base is the stock text scoring Lucene ships: a rare word counts more than
a common one, a word turning up repeatedly in a value counts more with
diminishing returns, and a value's length counts against it - the same words
covering a short value are a better answer than the same words sitting inside
a long one.

That last part is the one Exofind lets a field decide, because how much length
should count is a property of what the field holds rather than of the engine.
A field holding names wants it fully: the words left over are the difference
between what was asked for and something merely related to it. A field holding
prose wants far less. A field holding everything a document is about wants
none at all, since a fuller value there is not a worse answer. That is
`lengthNormalization`, and its three values are those three fields.

The length itself is written with the document; what a definition chooses is
only how much of it is read when a search runs. A changed setting therefore
reorders results from the next search on, with nothing reindexed.

## Where the match was found

A search box searches several fields at once, and a hit in a title is not a
hit in a footnote. `weight` on a field's `matching` is how much a hit there
counts, and a search can override the weights for that one request by mapping
each field to a number in its `text` clause.

How the fields are then combined is the `combine` option, and the default is
per word rather than per field: each word is looked for everywhere and counts
in whichever field holds it best. That is the right default for a search box,
where `red nike shoes` is one thing described across a colour, a brand and a
name. Fields that are parallel renderings of the same content - a title and
its body - want `field` instead, so that one field has to satisfy the search
on its own.

Combining words across fields only works where the fields cut text into the
same words. Where they do not - one decompounds, another drops a stopword the
rest keep - the fields that agree are combined among themselves, and a
document is ranked by whichever group matched it best.

## Matching the whole value

`exact` on a field lifts a document whose value the search matched *whole*
above one that merely holds the same words, which is what puts the product
named `iphone 15` above the case listed for it.

It is a lift and never a filter: the boost reaches documents the words had
already found, so the hits and the facet counts are the same as they would
have been without it. This is deliberate - a ranking feature that also removed
results would make every count depend on how the ranking was tuned. The price
is that `exact` is written with the documents, so turning it on only reaches
documents indexed from then on.

## Conditions that lift rather than narrow

A `boost` clause ranks documents satisfying it higher without leaving out the
ones that do not - featured products above the rest, in-stock above
out-of-stock. It exists next to filters because the two are opposite answers
to the same wish: a filter takes results away, a boost only moves them.

Boosts count as part of why a hit ranks where it does, which is why
[highlighting](../reference/search-api.md#highlighting) draws on them and not
on the clauses that only narrow. A document is not highlighted for the
category it happens to be filtered into.

## What the document is worth on its own

Text scoring only knows about the match. `signals` are the other half: a value
the documents carry - how often something sells, how recently it was published
- shaped into a number between zero and one and multiplied into the score as
`1 + weight * shape`.

The shape is what makes this safe to hand to a definition. Two properties
follow from it:

- A document holding no value contributes nothing rather than being multiplied
  away, so adding a signal never buries the documents that predate it.
- A signal can lift a document by at most its `weight`, however far the
  underlying value runs, so a runaway best seller cannot climb above a
  document that actually answers the search.

Which shape a value takes is a property of what it measures: a count with no
ceiling saturates toward one, an age halves over time. Both are read where the
search runs rather than written into the documents, so a ranking can be
changed, and a search can bring signals of its own to try one out against the
one in place, without touching a single document.

## What breaks the remaining ties

Signals are graded; tie breakers are what is left for documents that come out
equal anyway - the common case being a search that only narrows, where every
document matches exactly as well as every other. The index's `tieBreakers` are
appended after whatever order the search asked for and applied in turn until
one of them tells two documents apart, so they decide the order within ties
without ever disturbing the order that was asked for.

## When relevance is not the order

A search that gives a `sort` of its own is ordered by that, and nothing above
is read - no score is computed, and signals mean nothing. This is why a page
offering "sort by price" needs a way back to relevance, and why a `score` sort
exists to say so explicitly. Tie breakers are still appended, because a chosen
order leaves ties of its own.

## Vector scores are on their own scale

A [`knn`](../reference/search-api.md#knn) clause scores by how near a vector
is, which has nothing to do with how a text match scores. Combining the two -
the `or` that makes a hybrid search - adds the two rankings together as they
are, without normalizing either. What balance that gives depends on the model
and the text, so it is something to measure and then set with a `boost` rather
than something the engine can decide.

## Relaxing changes the result set, not the ranking

A `text` search that found nothing may drop words rather than answer an empty
page. What it dropped is reported alongside the results, and the words that
went are still counted in the ranking - a document that does hold one of them
comes first among the results that are left. Relaxing decides what there is to
rank, not how it is ranked.

## Where each part is decided

| Part | Decided in | Takes effect |
|------|------------|--------------|
| Length normalization | the definition | next search |
| Field weights | the definition, overridable per search | next search |
| Whole-value lift (`exact`) | the definition | documents indexed from then on |
| Boost clauses | the search | next search |
| Signals | the definition, replaceable per search | next search |
| Tie breakers | the definition | next search |

Almost everything about ranking is read where the search runs, which is what
makes tuning it cheap: change the definition, search again, and compare. Only
`exact` is written with the documents, and changing anything about *analysis*
- what the words are - is a rollout into a new generation rather than a
ranking change at all.

## Related

- [Field types](../reference/field-types.md#ranking) - `ranking`, `signals`
  and the per-field settings.
- [Search API](../reference/search-api.md) - `text`, `boost`, `signals` and
  `sort` as a request.
- [Search an index](../how-to/search-an-index.md) - putting a search together.
