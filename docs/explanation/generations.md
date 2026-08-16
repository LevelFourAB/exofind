# Generations

An index in Exofind is a name with generations under it. The documents and the
definition belong to a generation; one of them is live, and that is what the
name answers from. `products` is the index, `products@2` is a generation of it.

This exists because a definition and the documents indexed under it cannot be
changed apart. Turning on `matching` for a field writes a Lucene field that no
document already indexed has, and the analysis chain a value was tokenized with
is fixed at the moment it was indexed - a changed chain, an edited synonym set
or a new locale reaches nothing already in the index. Applied in place, such a
change leaves searches returning less than they should with no error anywhere,
which is the worst shape a failure can take: nothing to catch, nothing to
report, and no way for a caller to tell a thin result from a correct one.

So the engine does not offer changing it in place. A definition change that the
indexed documents were not indexed under is rolled out by filling a new
generation and promoting it, and promotion is a single conditional write of one
object.

## Why the name is what callers hold

The alternative - build `products_v3` beside `products` and have every caller
switch - moves the problem rather than solving it. Every application config,
every stored query and every key naming the index has to change with it, and
they change at different times.

Keys are the sharpest case. A key is granted over index patterns, so a
deployment that rebuilds under new names either grants `products*` - and the
application can then reach and search the retired generations, which are stale
by definition - or rewrites its grants on every rollout. In a deployment with
one index per tenant, that is thousands of grants rewritten inside one object
for a change that was supposed to be invisible.

With generations the name never moves. A key granted `products` exactly follows
the index across rollouts and cannot address a generation at all, because a
generation is written `products@2` and no pattern for `products` matches it. The
key that runs the rollout is granted `products@*`, which reaches that index's
generations and no other index's - the `@` appears in no name of its own, so the
prefix cannot run past the index it names. Neither key is touched by a rollout.

## Why the separator is reserved

Making the relation structural rather than conventional is what buys the
guarantees above. Because `@` can appear in no index name and no generation
name, every full name takes apart into exactly one index and one generation, and
a generation is reachable only through the index it belongs to. A name can never
be made to answer from another index's data, whatever is stored in the registry
- not because something checks, but because it is unsayable.

The alternative was a free mapping of name to index, which would have needed a
check that a caller may not point their own name at another tenant's index, and
would have needed it to be right every time.

## The registry

Which indexes exist, which generations each has, and which one is live is one
object in the storage, replaced with a conditional write and re-read by every
node on the refresh interval. Two properties follow.

**Creating an index is a race exactly one node wins.** Existence is decided by a
single conditional write rather than by each node's view of a listing.

**Learning what a deployment holds costs one request, whatever it holds.** A
node reads the registry with `If-None-Match` and is answered with nothing when
it has not moved. Discovery by listing the storage would instead grow with the
number of indexes - and grow again with every generation left standing during a
rollout, since a rollout doubles the entries for as long as the old generation
is kept.

This is also why absence from the registry is definitive. A listing arrives in
pages and can be incomplete without saying so, so removing a local copy for
being absent from one had to wait for the absence to repeat. A single object is
read at a single version, so a node removes its copy the first time it reads
that the index is gone. A node that has never managed to read the registry
removes nothing.

The cost is that the registry is load-bearing: a deployment whose registry
object is lost forgets its indexes, though the index data itself is still in
storage under `indexes/<index>/<generation>/`. The key store is already the same
kind of single object for the same kind of reason.

## What a generation is in storage

A generation is a complete index of its own - its own Lucene files, its own
manifest, its own epochs - stored under the prefix of the index it belongs to.
Nesting them is what keeps the shared prefix naming indexes: listing what lies
under `indexes/` with a delimiter reports one entry per index however many
generations stand beneath it, and removing an index is removing one prefix.

Everything in [Synchronization](synchronization.md) applies to a generation
unchanged. Two generations of one index are as independent as two indexes are:
they are pushed and pulled separately, and the epoch-scoped keys that keep two
writer sessions from overwriting each other work the same within each.

## What this does not do

Filling a new generation still means indexing the documents into it. The engine
keeps every document as it was given ([the source](../reference/documents-api.md)),
so rebuilding from the generation being replaced does not need the documents to
be sent again - but the rebuild itself, and what happens to documents written
while it runs, is not yet something the engine does on its own.
