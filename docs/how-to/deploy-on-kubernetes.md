# Deploying on Kubernetes

This guide shows you how to deploy Exofind on Kubernetes using two separate pools: a `Deployment` of search-only nodes and a `StatefulSet` of indexer candidates. Use this guide to set up a production Exofind deployment where search and indexing workloads scale independently.

Nodes coordinate through object storage and do not communicate with each other directly. To learn how Exofind coordinates nodes without Kubernetes, see [Run more than one node](run-multiple-nodes.md).

## Prerequisites

Before you begin, ensure you have the following:

- A Kubernetes cluster.
- An S3-compatible object storage bucket.
- A Kubernetes Secret named `exofind-storage` containing the keys `access-key` and `secret-key`.

## Why two pools

This guide runs a `Deployment` that searches and a `StatefulSet` of indexer candidates, because the two want different heap, different scaling signals, different disk rules and different rollout order - see [Separating search and indexing nodes](../explanation/deployment-shapes.md) for the whole argument.

One pool with `EXOFIND_INDEXER_ENABLED=true` on a few of its pods also works and requires less management. Collapsing the two pools into one later involves enabling `EXOFIND_INDEXER_ENABLED` in the search pool and deleting the `StatefulSet`. Splitting a single pool afterwards requires planning disk, routing, and probes again.

## Give the host enough memory maps

Every index file a node holds open requires at least one memory mapping. Linux caps the number of mappings a process can have with `vm.max_map_count`, which defaults to 65530 on many distributions. A node serving hundreds of indexes with multiple segments reaches that cap, causing file open operations to fail.

The setting is not namespaced, so you must configure it on the host rather than in the container. Configure `vm.max_map_count` on the node pool, or raise it with a privileged init container in both pools:

```yaml
initContainers:
  - name: raise-max-map-count
    image: busybox:1.36
    command: ["sysctl", "-w", "vm.max_map_count=262144"]
    securityContext:
      privileged: true
```

## Run the search pool

Nodes with `EXOFIND_INDEXER_ENABLED` set to `false` (the default) answer searches from their local copy and never write. They hold no persistent state, so the volume is ephemeral. A wiped pod slows down temporarily while it refills its cache.

Replace the placeholder values in angle brackets with values sized for your deployment. For details on sizing each parameter, see [Size the pools](#size-the-pools). Replace `<version>` with a specific release version, such as `0.1.0`. Do not use `latest`, so that replaced pods run the same version as existing pods.

Apply the following `Deployment` manifest to create the search pool:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: exofind-search
spec:
  replicas: <search-replicas>
  selector:
    matchLabels: { app: exofind, role: search }
  template:
    metadata:
      labels: { app: exofind, role: search }
    spec:
      terminationGracePeriodSeconds: <search-grace>
      containers:
        - name: exofind
          image: ghcr.io/levelfourab/exofind:<version>
          ports:
            - { name: http, containerPort: 8080 }
          env:
            - { name: EXOFIND_STORAGE_MODE, value: object }
            - { name: EXOFIND_STORAGE_REMOTE_URL, value: http://storage:9000 }
            - { name: EXOFIND_STORAGE_REMOTE_BUCKET, value: exofind }
            - name: EXOFIND_STORAGE_REMOTE_ACCESS_KEY
              valueFrom:
                secretKeyRef: { name: exofind-storage, key: access-key }
            - name: EXOFIND_STORAGE_REMOTE_SECRET_KEY
              valueFrom:
                secretKeyRef: { name: exofind-storage, key: secret-key }
            - { name: EXOFIND_INDEXES_DISK_MAX_SIZE, value: <disk-budget> }
            - { name: EXOFIND_INDEXES_MAX_OPEN, value: "<search-max-open>" }
            - { name: EXOFIND_INDEXES_REFRESH_CONCURRENCY, value: "<refresh-concurrency>" }
            - { name: JAVA_OPTS_APPEND, value: -XX:MaxRAMPercentage=25 }
          resources:
            requests: { cpu: <search-cpu>, memory: <search-memory> }
            limits: { memory: <search-memory> }
          volumeMounts:
            - { name: data, mountPath: /data }
      volumes:
        - name: data
          emptyDir: { sizeLimit: <search-volume> }
```

The container image sets `EXOFIND_STORAGE_LOCAL_DIRECTORY` to `/data`, so mounting a volume at `/data` is sufficient. Use local SSD storage when available on the node pool. A pull writes the entire index, and subsequent searches read it back through the page cache.

The `-XX:MaxRAMPercentage=25` setting allocates 25% of the pod memory to the JVM heap. A search-only node requires heap space only for in-flight searches, leaving the remaining 75% of memory for the Linux page cache where indexes are cached.

## Run the indexer pool

Deploy two indexer candidates in a `StatefulSet`. The candidates divide indexes between themselves and handle failover if a node stops. Each index is written by exactly one candidate at a time. A third candidate is necessary only when the deployment manages many active indexes to distribute.

The indexer pool uses a persistent volume claim (`volumeClaimTemplates`) rather than `emptyDir` storage. When an indexer modifies an index, the local disk holds unpushed commits until the next push to object storage finishes.

Apply the following `StatefulSet` manifest to create the indexer pool:

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: exofind-indexer
spec:
  serviceName: exofind-indexer
  replicas: 2
  podManagementPolicy: Parallel
  selector:
    matchLabels: { app: exofind, role: indexer }
  template:
    metadata:
      labels: { app: exofind, role: indexer }
    spec:
      terminationGracePeriodSeconds: <indexer-grace>
      containers:
        - name: exofind
          image: ghcr.io/levelfourab/exofind:<version>
          ports:
            - { name: http, containerPort: 8080 }
          env:
            # POD_IP and EXOFIND_NODE_ID come first: Kubernetes expands $(...)
            # against the variables declared before it and leaves the rest as
            # written, so EXOFIND_NODE_ADDRESS declared above POD_IP reaches the
            # node as the literal text and the table records an address nothing
            # can use.
            - name: POD_IP
              valueFrom:
                fieldRef: { fieldPath: status.podIP }
            - name: EXOFIND_NODE_ID
              valueFrom:
                fieldRef: { fieldPath: metadata.name }
            - { name: EXOFIND_NODE_ADDRESS, value: http://$(POD_IP):8080 }
            - { name: EXOFIND_INDEXER_ENABLED, value: "true" }
            - { name: EXOFIND_STORAGE_MODE, value: object }
            - { name: EXOFIND_STORAGE_REMOTE_URL, value: http://storage:9000 }
            - { name: EXOFIND_STORAGE_REMOTE_BUCKET, value: exofind }
            - name: EXOFIND_STORAGE_REMOTE_ACCESS_KEY
              valueFrom:
                secretKeyRef: { name: exofind-storage, key: access-key }
            - name: EXOFIND_STORAGE_REMOTE_SECRET_KEY
              valueFrom:
                secretKeyRef: { name: exofind-storage, key: secret-key }
            - { name: EXOFIND_INDEXES_MAX_OPEN, value: "<indexer-max-open>" }
            # Matching EXOFIND_INDEXES_REFRESH_INTERVAL: committing more often
            # than searching nodes poll costs requests without them seeing
            # anything sooner.
            - { name: EXOFIND_INDEXES_COMMIT_MAX_INTERVAL, value: 30s }
            - { name: JAVA_OPTS_APPEND, value: -XX:MaxRAMPercentage=<indexer-heap> }
          resources:
            requests: { cpu: <indexer-cpu>, memory: <indexer-memory> }
            limits: { memory: <indexer-memory> }
          volumeMounts:
            - { name: data, mountPath: /data }
  volumeClaimTemplates:
    - metadata: { name: data }
      spec:
        accessModes: [ReadWriteOnce]
        resources:
          requests: { storage: <indexer-volume> }
```

Setting `EXOFIND_NODE_ID` to the pod name associates claims with specific pods and prefixes write log lines with the pod name. This makes failovers easier to track in logs.

Do not configure `EXOFIND_INDEXES_DISK_MAX_SIZE` on indexers. The disk sweeper does not remove local copies that are not yet committed to object storage, so it cannot free disk space when disk usage is highest. Size the persistent volume claim for your expected write volume instead.

## Size the pools

Size each pool based on the number of indexes and the query and write load of your deployment. Adjust one setting at a time.

For the search pool:

- **`<search-replicas>`, `<search-min-available>`:** Sized for query load and the minimum capacity required during node drains. Configure an autoscaler on this pool.
- **`<search-cpu>`, `<search-memory>`:** Searches consume CPU across memory-mapped files. Size memory so that indexes fit in the memory remaining after JVM heap allocation.
- **`<search-volume>`:** Sized for the total size of indexes a single pod holds, plus room for index downloads during updates. To decide whether a pod holds all indexes or a subset, see [Spread the indexes across the search pool](#spread-the-indexes-across-the-search-pool).
- **`<disk-budget>`:** Set below `<search-volume>` so that the disk sweep runs before the volume fills. The sweeper frees disk space down to 10% below this bound and only removes copies that are fully uploaded to object storage.
- **`<search-max-open>`:** The number of unique indexes a single pod serves within a few minutes. Setting this too low results in HTTP 503 errors and causes pods to repeatedly close and reopen indexes under load.
- **`<refresh-concurrency>`:** Increase this value from the default of 4 if a refresh pass exceeds `EXOFIND_INDEXES_REFRESH_INTERVAL`. A pass makes at most one conditional request per open index, and none for an index the registry reports as unchanged, so concurrency depends on storage latency rather than query load.
- **`<search-grace>`:** Set long enough to finish in-flight search requests. Search nodes have no uncommitted data to push.

For the indexer pool:

- **`<indexer-cpu>`, `<indexer-memory>`:** Sized for text analysis, segment merging, and the buffered documents of all simultaneously open writer indexes.
- **`<indexer-heap>`:** Set above the image default of 50. Indexers require more JVM heap than page cache to accommodate merge operations and write buffers.
- **`<indexer-max-open>`:** The number of indexes written simultaneously, not the total number of existing indexes. Each open index maintains an active Lucene writer with its own memory buffer and merge threads.
- **`<indexer-volume>`:** Sized for the indexes this pool writes, including the temporary space required during segment merges.
- **`<indexer-grace>`:** Set longer than the time required to close and push the largest index to object storage. If a pod terminates before pushing, uncommitted changes remain unpushed until failover.

Size memory before fine-tuning other settings. Nodes read indexes through memory-mapped files. Memory not allocated to the JVM heap serves as the operating system page cache. If indexes exceed available cache space, searches must read from disk. Increase pod memory limits before increasing the JVM heap percentage.

## Send writes to the indexer

You can send write requests to any Exofind pod. When a node receives a write for an index it does not manage, it forwards the request to the indexer candidate holding the lease for that index. The forwarding target is the pod IP address stored in the index registry table.

If `EXOFIND_NODE_ADDRESS` is misconfigured or fails to expand variable references, write forwarding fails with HTTP 409 errors. The receiving node logs a warning:

```
WARN  address=http://$(POD_IP):8080 Indexer address cannot be forwarded to; …
```

Forwarding adds a network hop through the receiving pod. To avoid forwarding overhead during high-volume indexing, create dedicated `Service` resources for each pool:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: exofind-indexer
spec:
  selector: { app: exofind, role: indexer }
  ports:
    - { name: http, port: 8080 }
---
apiVersion: v1
kind: Service
metadata:
  name: exofind-search
spec:
  selector: { app: exofind, role: search }
  ports:
    - { name: http, port: 8080 }
```

Send document write and commit requests to `exofind-indexer`. If the selected pod does not hold the lease for the target index, it forwards the request directly to the managing candidate within the cluster.

Send search queries to `exofind-search`, which balances traffic across search-only pods.

## Spread the indexes across the search pool

When a node receives a search request for an index, it pulls the index files from storage and caches them locally. With a round-robin `Service`, every pod eventually receives requests for every index, causing every pod to download and cache the entire index catalog.

If the combined size of all indexes fits comfortably within a single pod's local volume, allow all pods to hold all indexes and skip this section.

If total index size exceeds single-pod capacity, route search requests by index name using ingress hashing so that each pod caches only a subset of indexes:

```yaml
metadata:
  annotations:
    nginx.ingress.kubernetes.io/upstream-hash-by: "$request_uri"
```

You can configure equivalent path-based consistent hashing in Envoy or Istio. Hashing distributes index subsets across pods, reducing disk requirements and improving page cache hit rates.

**Note:** Consistent hashing concentrates traffic for a single high-traffic index onto specific pods. To isolate very large indexes, deploy a separate search pool with dedicated ingress routing rules.

## Probe the pods

Configure readiness and liveness probes for both pools using the Quarkus health endpoints:

```yaml
readinessProbe:
  httpGet: { path: /q/health/ready, port: http }
  periodSeconds: 5
livenessProbe:
  httpGet: { path: /q/health/live, port: http }
  periodSeconds: 10
  failureThreshold: 6
```

The readiness probe prevents traffic from reaching pods that have not yet loaded the index registry. A pod that receives queries before reading the registry returns empty search results instead of an error.

Set relaxed thresholds on the liveness probe. Large index downloads and merges consume significant resources; restarting a busy node cancels in-progress operations and triggers unnecessary index failovers.

## Roll out and shut down

When a node shuts down, it closes its open indexes and pushes modified index data to object storage. An indexer candidate also releases its index leases, allowing successor nodes to take over immediately instead of waiting for `EXOFIND_INDEXER_LEASE_DURATION` to expire.

Set `terminationGracePeriodSeconds` to allow sufficient time for final commits to upload. If a pod terminates before uploads complete, uncommitted data is lost from that node and must be recovered by another candidate.

For the indexer pool, update pods one at a time and set the grace period based on the upload duration of your largest index.

For the search pool, pods hold no uncommitted state, so surge rollouts are safe. Apply a `PodDisruptionBudget` to ensure minimum search capacity remains available during cluster node drains:

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: exofind-search
spec:
  minAvailable: <search-min-available>
  selector:
    matchLabels: { app: exofind, role: search }
```

Nodes running different Exofind versions can operate against the same object storage bucket concurrently. For version upgrade sequencing and Lucene major version requirements, see [Operate a deployment](operate-a-deployment.md#upgrade-the-engine).

## Confirm the deployment

Verify that all pods in both pools are running and ready:

```shell
kubectl get pods -l app=exofind
```

Check the indexer logs to confirm that the indexer node registered its address and is competing for the indexer role:

```shell
kubectl logs -l app=exofind,role=indexer
```

Look for a log line indicating successful startup:

```
INFO  node=exofind-indexer-0 address=http://10.4.2.17:8080 Competing for the indexer role
```

If the address displays unresolved template syntax such as `http://$(POD_IP):8080`, verify the ordering of `POD_IP` and `EXOFIND_NODE_ADDRESS` in the indexer `StatefulSet` environment variables.

## Tune a deployment with many indexes

Deployments with hundreds of indexes reach their limits on the indexer pool first. Set the following on that pool:

- Set `EXOFIND_INDEXES_MAX_OPEN` to the number of indexes written at the same time, not the total number of indexes.
- Set `EXOFIND_INDEXES_COMMIT_MAX_INTERVAL` to match `EXOFIND_INDEXES_REFRESH_INTERVAL`. Committing more often than the search pool polls produces storage requests without making changes visible any sooner.
- Raise `EXOFIND_INDEXES_REFRESH_CONCURRENCY` above its default of 4 if a refresh pass does not finish within `EXOFIND_INDEXES_REFRESH_INTERVAL`. Passes are cheap: a node skips an index the registry reports as unchanged, and checks the rest with `If-None-Match`.

For what each of these limits is, and why adding candidates does not make one index write faster, see [Separating search and indexing nodes](../explanation/deployment-shapes.md#scaling-limits-with-many-indexes).

## Related

- [Run more than one node](run-multiple-nodes.md) - Candidacy, failover, and write routing mechanisms.
- [Operate a deployment](operate-a-deployment.md) - Operational checks, upgrades, and log messages.
- [Configuration](../reference/configuration.md) - Environment variables and JVM configuration options.
- [Architecture](../explanation/architecture.md) - Storage model and stateless node architecture.
