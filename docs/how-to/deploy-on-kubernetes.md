# Deploy on Kubernetes

Nodes coordinate through the bucket alone and never talk to each other, so
Kubernetes has nothing to wire together - it runs the containers and puts
traffic in front of them. What is left to decide is how many pools there are,
which of them may write, and how the pods find the disk and the host limits
that many indexes need.

This guide deploys two pools: a `Deployment` of nodes that only search, and a
small `StatefulSet` of indexer candidates. [Run more than one
node](run-multiple-nodes.md) is the same deployment without Kubernetes in the
way, and is worth reading first.

## Why two pools

One pool with `INDEXER=true` on a few of its pods works and is less to look
after. Two pools are worth the extra manifest because the roles want
different pods:

- **Different memory split.** A searching node reads its indexes through the
  page cache and wants a small heap; an indexer holds the buffered documents
  of uncommitted batches and the segments of running merges and wants a large
  one. One pool has one `JAVA_OPTS_APPEND`.
- **Different scaling signal.** Search capacity scales with query load and
  belongs behind an autoscaler. The number of indexer candidates is a fixed
  small number, and an autoscaler that removes pods when CPU drops will
  eventually remove the one holding the lease.
- **Different disk.** A searching node's disk is a cache that a bound may
  sweep. An indexer's disk holds commits the bucket has not got yet, which
  nothing may remove.
- **Different rollout.** Indexers restart one at a time and want long enough
  to push what they hold; searchers can be replaced as fast as the pulls
  allow.
- **Somewhere to send writes.** A separate `Service` in front of the
  candidates is what lets writes reach the indexer directly instead of being
  redirected to a pod address the caller may not be able to reach - see [Send
  writes to the indexer](#send-writes-to-the-indexer).

Collapsing the two into one pool later is turning `INDEXER` on in the search
pool and deleting the `StatefulSet`. Splitting a single pool afterwards means
planning disk, routing and probes again.

## Give the host enough memory maps

Every index file a node holds open is at least one memory mapping, and Linux
caps the mappings a process may have with `vm.max_map_count` - 65530 on many
distributions. A node serving hundreds of indexes of many segments each
reaches that cap, and the open that crosses it fails.

The setting is not namespaced, so it is set on the host rather than on the
pod. Either configure it on the node pool, or raise it from a privileged init
container in both pools:

```yaml
initContainers:
  - name: raise-max-map-count
    image: busybox:1.36
    command: ["sysctl", "-w", "vm.max_map_count=262144"]
    securityContext:
      privileged: true
```

## Run the search pool

Nodes with `INDEXER` left off answer searches from their own copy and never
write. They hold no state worth keeping, so the volume is ephemeral and a
wiped pod is slow rather than broken while it refills.

Everything in angle brackets is sized against the deployment rather than
copied - [Size the pools](#size-the-pools) says what each one is measured
from.

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
          image: exofind/engine:dev
          ports:
            - { name: http, containerPort: 8080 }
          env:
            - { name: EXOFIND_STORAGE_MODE, value: object }
            - { name: REMOTE_STORAGE_URL, value: http://storage:9000 }
            - { name: REMOTE_STORAGE_BUCKET, value: exofind }
            - name: REMOTE_STORAGE_ACCESS_KEY
              valueFrom:
                secretKeyRef: { name: exofind-storage, key: access-key }
            - name: REMOTE_STORAGE_SECRET_KEY
              valueFrom:
                secretKeyRef: { name: exofind-storage, key: secret-key }
            - { name: INDEXES_DISK_MAX_SIZE, value: <disk-budget> }
            - { name: INDEXES_MAX_OPEN, value: "<search-max-open>" }
            - { name: INDEXES_REFRESH_CONCURRENCY, value: "<refresh-concurrency>" }
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

The image already points `LOCAL_STORAGE_DIRECTORY` at `/data`, so mounting
something there is all the volume needs. Put it on local SSD where the node
pool offers it - a pull writes the whole index and a search reads it back
through the page cache.

`MaxRAMPercentage=25` is the one value here that is not sized per deployment:
a node that only searches wants a heap that holds the searches themselves and
nothing more, and leaving three quarters of the limit to the page cache is
what the indexes are read out of.

## Run the indexer pool

Two candidates. They compete for the lease, one holds it, and the other exists
so that losing a node does not stop writes - a third buys almost nothing.

The volume is a claim rather than an `emptyDir` because an index this node has
changed holds commits the bucket has not got yet until the next push lands.

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
          image: exofind/engine:dev
          ports:
            - { name: http, containerPort: 8080 }
          env:
            # POD_IP and NODE_ID come first: Kubernetes expands $(...) against
            # the variables declared before it and leaves the rest as written,
            # so NODE_ADDRESS declared above POD_IP reaches the node as the
            # literal text and the lease records an address nothing can use.
            - name: POD_IP
              valueFrom:
                fieldRef: { fieldPath: status.podIP }
            - name: NODE_ID
              valueFrom:
                fieldRef: { fieldPath: metadata.name }
            - { name: NODE_ADDRESS, value: http://$(POD_IP):8080 }
            - { name: INDEXER, value: "true" }
            - { name: EXOFIND_STORAGE_MODE, value: object }
            - { name: REMOTE_STORAGE_URL, value: http://storage:9000 }
            - { name: REMOTE_STORAGE_BUCKET, value: exofind }
            - name: REMOTE_STORAGE_ACCESS_KEY
              valueFrom:
                secretKeyRef: { name: exofind-storage, key: access-key }
            - name: REMOTE_STORAGE_SECRET_KEY
              valueFrom:
                secretKeyRef: { name: exofind-storage, key: secret-key }
            - { name: INDEXES_MAX_OPEN, value: "<indexer-max-open>" }
            # Matching INDEXES_REFRESH_INTERVAL: committing more often than
            # searching nodes poll costs requests without them seeing anything
            # sooner.
            - { name: INDEXES_COMMIT_MAX_INTERVAL, value: 30s }
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

`NODE_ID` from the pod name is what the lease is held under and what every
later log line about the role is keyed by, which makes a failover readable
without mapping random suffixes back to pods.

Leave `INDEXES_DISK_MAX_SIZE` off here. The sweep refuses to remove a copy the
bucket does not fully hold, so on the pod doing the writing it frees the least
when the disk is most under pressure. Size the claim for what the pool writes
instead.

The startup log is where to check that the address came out right:

```
INFO  node=exofind-indexer-0 address=http://10.4.2.17:8080 Competing for the indexer role
```

## Size the pools

None of these have a value that is right in general - each is measured
against the indexes the deployment holds and the load it takes. Start from
what they are measured *from* rather than from a number, and change one at a
time.

In the search pool:

- **`<search-replicas>`, `<search-min-available>`** - query load, and what
  is left answering while a node is drained. This is the pool an autoscaler
  belongs on.
- **`<search-cpu>`, `<search-memory>`** - a search is CPU work over
  memory-mapped files. Size the memory so the indexes a pod holds fit in what
  the heap leaves of it.
- **`<search-volume>`** - the indexes one pod ends up holding, plus room for a
  pull to land beside what it replaces. Whether that is all of them or a share
  of them is [Spread the indexes across the search
  pool](#spread-the-indexes-across-the-search-pool).
- **`<disk-budget>`** - under `<search-volume>` by enough that the sweep runs
  before the volume fills. It frees down to a tenth under the bound and only
  removes copies the bucket fully holds.
- **`<search-max-open>`** - the indexes one pod is asked for within a few
  minutes of each other. Set below that, it shows up as `503`s and as indexes
  being closed and reopened under load.
- **`<refresh-concurrency>`** - raised from the default of 4 until a refresh
  pass fits inside `INDEXES_REFRESH_INTERVAL`. A pass is one conditional
  request per open index, so this follows the latency to the storage rather
  than the query load.
- **`<search-grace>`** - long enough to finish in-flight searches. There is
  nothing to push.

In the indexer pool:

- **`<indexer-cpu>`, `<indexer-memory>`** - analysis, merging, and the
  buffered documents of every index open for writing at once.
- **`<indexer-heap>`** - above the image default of 50. This is the one pool
  where heap is worth more than page cache; merge and buffer pressure decide
  by how much.
- **`<indexer-max-open>`** - the indexes written at the same time, not the
  indexes that exist. Each open one is a Lucene writer with a buffer and
  merges of its own.
- **`<indexer-volume>`** - the indexes this pool writes, at the size they
  reach between merges.
- **`<indexer-grace>`** - longer than closing and pushing the largest index
  takes. A pod killed before that finishes holds commits the bucket never got.

Memory is the one to get roughly right before the rest. A node reads its
indexes through memory maps, so what the heap does not take is what the index
is cached in, and a node whose indexes no longer fit in the remainder reads
from disk on every search that misses. Give the pod more memory before giving
the JVM a larger share of it.

## Send writes to the indexer

A write that reaches a node which is not the indexer is answered with `307`
and the address from the lease. That address is a pod IP, so a caller outside
the cluster cannot follow the redirect - and a `NODE_ADDRESS` that never
expanded cannot be turned into one at all, which shows up as the write being
refused with `409` and this in the log of the node that answered:

```
WARN  address=http://$(POD_IP):8080 Indexer address cannot be turned into a redirect; …
```

Give each pool a `Service` and route writes to the indexer one, so the
redirect is a fallback rather than the write path:

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

The indexer `Service` picks either candidate, and the one that is not holding
the lease redirects to the one that is - inside the cluster, where the pod
address resolves. Point definition changes, document writes and commit actions
at it, and searches at `exofind-search`.

## Spread the indexes across the search pool

A node pulls an index the first time it is asked for one and keeps the files
afterwards. Behind a round-robin `Service`, every pod is eventually asked for
every index, so every pod ends up holding the whole corpus and pulling every
change to it.

Whether that matters is arithmetic: if the indexes together fit on one pod's
volume, let every pod hold all of them and skip this section - any pod can
then serve any index, which is one less thing to reason about. When they do
not fit, route searches by index name so each pod holds a share of them. The
name is in the path, so an ingress that hashes on it is enough:

```yaml
metadata:
  annotations:
    nginx.ingress.kubernetes.io/upstream-hash-by: "$request_uri"
```

A ring hash on the request path in Envoy or Istio does the same. Either way
each pod holds a subset, pulls a subset, and answers more of its searches from
a page cache that is not being shared with 400 other indexes.

The cost is that one very large index concentrates on one pod. Keep a way to
send the largest ones somewhere of their own - a second search pool with its
own ingress rule is the least clever way to do it.

## Probe the pods

Both pools serve health at the usual Quarkus paths, and readiness is the one
that matters: a pod that has not read the registry yet answers searches with
nothing found rather than with an error, so routing to it early looks like
missing data rather than a failure.

```yaml
readinessProbe:
  httpGet: { path: /q/health/ready, port: http }
  periodSeconds: 5
livenessProbe:
  httpGet: { path: /q/health/live, port: http }
  periodSeconds: 10
  failureThreshold: 6
```

Keep the liveness probe slack. A node under a heavy pull or a large merge is
working, and restarting it throws away the work and the lease with it.

## Roll out and shut down

Stopping a node closes its open indexes, and an index it has changed is pushed
as it closes. An indexer also hands its lease back, so a successor takes over
at once instead of after `INDEXER_LEASE_DURATION`. Both want time:
`terminationGracePeriodSeconds` has to cover the push, or the pod is killed
holding commits the bucket never got.

For the indexer pool that means one pod at a time and a grace period measured
against the largest index it writes. For the search pool there is nothing to
push and the only cost of replacing a pod is the pulls the new one has to do,
so a surge rollout is fine - with a `PodDisruptionBudget` so that a node drain
does not take the pool below what search traffic needs:

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

Nodes of different versions run against the same bucket, so a rolling update
is safe in itself. The two rules that decide the order - upgrade before
writing a definition that uses something new, reindex before crossing a
Lucene major - are in [Operate a
deployment](operate-a-deployment.md#upgrade-the-engine).

## What to watch with many indexes

A deployment holding hundreds of indexes runs into its limits on the write
side first, because one node writes all of them.

- **Open indexes are open Lucene writers.** The indexer holds a writer, its
  buffered documents and its merges for every index it has open at once.
  `INDEXES_MAX_OPEN` is what bounds that, and the right value is the number of
  indexes actually being written at the same time, not the number that exist.
  Set it too low and indexes are closed and reopened under a load that keeps
  asking for them; too high and the heap holds buffers for all of them.
- **Committing costs requests.** Each active index commits and pushes on
  `INDEXES_COMMIT_MAX_INTERVAL`, and at the 5s default a few hundred of them
  is a steady stream of uploads and conditional writes. Searching nodes see
  none of it sooner than `INDEXES_REFRESH_INTERVAL` anyway, so raising the
  commit interval to match it costs nothing in freshness.
- **Refreshing does not.** A pull sends `If-None-Match` and takes a `304` for
  an index that has not changed, so the interval costs one cheap request per
  open index. `INDEXES_REFRESH_CONCURRENCY` is what to raise if a pass stops
  fitting in the interval - the default of 4 is thin once a node holds
  hundreds.
- **Write throughput does not scale by adding pods.** Every write in the
  deployment goes through the one node holding the lease. Adding candidates
  buys failover, not capacity. This is the number a test deployment is for.

## Related

- [Run more than one node](run-multiple-nodes.md) - candidacy, failover and
  how writes find the indexer.
- [Operate a deployment](operate-a-deployment.md) - what to check once it is
  running, and what the log lines mean.
- [Configuration](../reference/configuration.md) - every variable named here,
  and what the JVM is started with.
- [Architecture](../explanation/architecture.md) - why a pod holds nothing
  worth backing up.
