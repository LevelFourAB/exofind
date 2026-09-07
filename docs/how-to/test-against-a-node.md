# Testing an application against a node

This guide shows how to run integration tests for an application against a local
Exofind node. Use this approach to run integration test suites on a local
workstation or in continuous integration (CI) environments without provisioning
shared infrastructure or external storage.

## Prerequisites

Before you begin, ensure you have the following:

- Docker or a compatible container runtime installed.
- Access to the container image `ghcr.io/levelfourab/exofind`. Pin a release tag
  such as `0.1.0` in your test suite so test runs remain repeatable.

Exofind uses `local` storage mode by default (`EXOFIND_STORAGE_MODE=local`). In
this mode, the node stores all indexes and keys on local disk inside the
container, so test runs do not require an object storage bucket.

## Steps

### 1. Start a node for the test run

Choose an authentication strategy based on what your test suite verifies:

1. **Disable authentication (`EXOFIND_AUTH_MODE=none`):** Use this option for
   simpler test setup when your test suite focuses on search logic rather than
   authentication. Every request is allowed without an `Authorization` header.
2. **Use a root key (`EXOFIND_AUTH_ROOT_KEY`):** Use this option to test the
   exact request shape used in production environments. Pass the configured key
   as a bearer token in the `Authorization` header on every API request. A test
   key requires the `indexes.write`, `indexes.delete`, `documents.write`,
   `indexes.commit`, and `search` permissions. The root key holds all of these
   permissions.

To start a standalone container from the command line, run:

```shell
docker run -d --name exofind-test -p 8080:8080 \
  -e EXOFIND_AUTH_MODE=none \
  ghcr.io/levelfourab/exofind:0.1.0
```

Do not mount a volume on `/data`. Without a persistent volume, all state is
discarded when the container stops. You can map port `8080` to a fixed host port
or allow Docker to assign a random port.

If you use Java and Testcontainers, configure a `GenericContainer` with a readiness
wait strategy:

```java
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

GenericContainer<?> exofind = new GenericContainer<>("ghcr.io/levelfourab/exofind:0.1.0")
    .withExposedPorts(8080)
    .withEnv("EXOFIND_AUTH_MODE", "none")
    .waitingFor(Wait.forHttp("/q/health/ready").forStatusCode(200));
```

### 2. Wait for the node to be ready

Poll the readiness endpoint before sending index or document requests:

```http
GET /q/health/ready HTTP/1.1
Host: localhost:8080
```

The readiness endpoint requires no credentials. It returns `200 OK` when the
node is ready to accept requests and `503 Service Unavailable` while startup is
in progress.

### 3. Create an index and load documents

1. Define an index using `PUT /v1alpha1/admin/indexes/{name}`:

   ```http
   PUT /v1alpha1/admin/indexes/products HTTP/1.1
   Host: localhost:8080
   Content-Type: application/json

   {
     "fields": {
       "id": { "type": "string", "primaryKey": true, "required": true },
       "title": { "type": "string", "matching": {} }
     }
   }
   ```

2. Add documents using `POST /v1alpha1/indexes/{name}/documents`:

   ```http
   POST /v1alpha1/indexes/products/documents HTTP/1.1
   Host: localhost:8080
   Content-Type: application/json

   {
     "documents": [
       { "id": "1", "title": "Mechanical Keyboard" },
       { "id": "2", "title": "Wireless Mouse" }
     ]
   }
   ```

### 4. Commit pending writes

Make the written documents searchable before asserting on search results. Call
the commit endpoint:

```http
POST /v1alpha1/admin/indexes/products/actions/commit HTTP/1.1
Host: localhost:8080
```

This request commits pending document additions and schema changes, returning
only when the commit is complete. In `local` storage mode, a single node serves
both writes and searches, so any search executed after the commit request returns
immediately sees the committed documents.

Do not rely on the automatic commit interval during test runs. By default, the
node commits only after accumulating 10 000 changes or waiting 5 seconds
(`EXOFIND_INDEXES_COMMIT_MAX_INTERVAL=5s`). Relying on the timer slows down
test execution and causes intermittent test failures. If your test harness cannot
call the commit endpoint directly, lower `EXOFIND_INDEXES_COMMIT_MAX_INTERVAL`
when starting the container.

### 5. Reset between tests

Choose an isolation strategy for your test suite:

1. **Delete and recreate the index:** Send `DELETE /v1alpha1/admin/indexes/{name}`
   at the end of each test, then create the index again for the next test. An
   index created under the same name starts completely empty. This option is fast
   because it reuses the running container across test cases.
2. **Start a new container per test suite:** Start a fresh container for each
   test suite and stop it at completion. This option guarantees complete
   isolation across suites without residual state.

To delete an index between tests:

```http
DELETE /v1alpha1/admin/indexes/products HTTP/1.1
Host: localhost:8080
```

### 6. Stop the container

Stop the container when the test run finishes:

```shell
docker stop exofind-test
docker rm exofind-test
```

If you use Testcontainers, call `exofind.stop()` in your test lifecycle teardown.
Because no volume was attached, all indexes and node state are removed.

## Confirming the result

To verify that your test setup works, execute a search query against the
committed index:

```http
POST /v1alpha1/indexes/products/search HTTP/1.1
Host: localhost:8080
Content-Type: application/json

{
  "query": [
    { "type": "text", "text": "Keyboard" }
  ]
}
```

The node returns a `200 OK` response containing the matching document:

```json
{
  "hits": [
    {
      "id": "1",
      "score": 1.0,
      "document": {
        "id": "1",
        "title": "Mechanical Keyboard"
      }
    }
  ],
  "total": { "count": 1, "exact": true },
  "page": { "limit": 10, "offset": 0 },
  "tookMs": 1.25
}
```

## Related

- [Getting started](../tutorials/getting-started.md) - Defining and searching a
  first index by hand before a test suite does it.
- [Make a write visible to search](make-writes-visible.md) - The commit and
  refresh delays between a write and a search that can see it.
- [Indexing documents](index-documents.md) - Loading a dataset and keeping it
  current outside a test run.
- [Handle errors in a client](handle-api-errors.md) - The failures a test suite
  asserts on, and how a client routes them.
- [Running on one node](run-on-one-node.md) - Running the same local-mode node
  outside a test run.
- [Configuration](../reference/configuration.md) - Every environment variable
  the container takes.
- [Authentication](../reference/auth.md) - Keys, permissions, roles, and the
  keys API.
