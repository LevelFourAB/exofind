#!/usr/bin/env bash
#
# Load the food example into a node: define the index, put every food in it
# and commit, which is what makes them searchable and pushes them to storage.
#
# Point it at another node by setting NODE, give it the credential that node
# wants with KEY, and use another index name with INDEX:
#
#   NODE=https://search.example.com KEY=exok_... ./load.sh
#
# Loading writes, so KEY has to be granted `indexes.write`, `documents.write`
# and `indexes.commit` over INDEX - the search-only key a demo node answers
# readers as cannot do it. A node that checks no credentials, such as dev mode,
# wants none at all.
#
set -euo pipefail

NODE="${NODE:-http://localhost:8080}"
INDEX="${INDEX:-livsmedel}"
KEY="${KEY:-}"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ ! -f "$here/documents.jsonl.gz" ]; then
	echo "No documents to load - run ./prepare.py first" >&2
	exit 1
fi

# Every request is the same curl with the same credential, so both are decided
# here. Without a KEY nothing is sent and the node decides for itself what an
# unauthenticated caller may do.
request() {
	if [ -n "$KEY" ]; then
		curl -fsS -H "Authorization: Bearer $KEY" "$@"
	else
		curl -fsS "$@"
	fi
}

echo "Defining $INDEX on $NODE"
request -X PUT "$NODE/v1alpha1/admin/indexes/$INDEX" \
	-H 'Content-Type: application/json' \
	--data-binary "@$here/definition.json" \
	-o /dev/null

# A node refuses a request body past a limit of its own, so the documents go
# in batches rather than as one post of every food there is.
BATCH="${BATCH:-5000}"

echo "Indexing documents, $BATCH at a time"
batches="$(mktemp -d)"
trap 'rm -rf "$batches"' EXIT

gunzip -c "$here/documents.jsonl.gz" | split -l "$BATCH" - "$batches/batch."

for batch in "$batches"/batch.*; do
	request -X POST "$NODE/v1alpha1/indexes/$INDEX/documents" \
		-H 'Content-Type: application/x-ndjson' \
		--data-binary "@$batch"
	echo
done
echo "Committing"
request -X POST "$NODE/v1alpha1/admin/indexes/$INDEX/actions/commit" -o /dev/null

echo "Done - run 'npm run dev' in examples/ to search it"
