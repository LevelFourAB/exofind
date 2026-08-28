# Trust model

This document explains how Exofind draws its security boundaries, what an API key can reach, and why credentials operate at the deployment and index level rather than the end-user level. For the exact rules and status codes, see the [Authentication reference](../reference/auth.md). For setup steps, see [Secure a deployment](../how-to/secure-a-deployment.md).

## Keys are service credentials, not user accounts

An Exofind API key is a deployment-wide credential scoped by permissions and index patterns. It identifies a participating service or subsystem—such as an ingestion pipeline, an administrative script, or an application backend—rather than an individual person.

Exofind does not provide end-user accounts, record-level ownership, or per-user access control lists. A key grants access to entire indexes or matching index name patterns. There is no mechanism inside Exofind to narrow a key so that an end user can see only their own records. All tenant- or user-level access logic must live in the application layer that sits in front of Exofind.

## Why browsers must never hold a key

Because keys grant access across whole index patterns, frontend applications running in a browser must never hold an Exofind API key.

Anyone who inspects network traffic or opens browser developer tools can extract credentials embedded in client code. Once someone extracts a key, they can send arbitrary requests directly to the node. Filtering search results in client-side code such as JavaScript does not restrict access. A caller with the key can bypass client-side filters and query any document in any index matching the key's patterns.

To protect your data, place an application backend between your web clients and Exofind. The backend authenticates the end user, applies application-level filtering and business rules, and uses its own scoped key to query Exofind.

### The exception: public search nodes

The only scenario where direct browser access is acceptable is a node that anyone is permitted to search in full, such as a public catalog or demo application.

In this setup, you configure an anonymous key on a specific node that allows only the `search` permission on designated public indexes. The browser presents no credential, and the node strictly rejects write and administrative operations. For instructions on configuring this mode safely, see [Run a public demo node](../how-to/run-a-demo-node.md).

## Shared storage and propagation

Keys are stored in the same underlying storage as the indexes—in the storage bucket in object mode, or in a local key file in single-node mode. Keys are not written into static node configuration files.

This shared storage design provides several properties:

- **Consistent access:** A key created on any node is written to the shared store and immediately works across all nodes in the deployment.
- **Independent key administration:** Managing keys does not require reaching a designated indexer or writer node. Any node can handle key creation and revocation.
- **Fast adoption:** When a node receives a credential it has not seen yet, it reads the store immediately to validate the key without waiting for a background polling cycle.
- **Interval-based revocation:** Nodes periodically re-read key storage based on the configured refresh interval. When you revoke a key, the revocation takes effect immediately on the node that handled the deletion, and on all other nodes within their refresh interval.

## Immutability and expiration

Keys in Exofind are immutable: you can create them and revoke them, but you cannot edit them.

Immutability eliminates subtle authorization bugs and race conditions. When requirements change or credentials need rotation, you create a new key with the required grants, switch your service to use it, and revoke the old key. This workflow prevents service downtime during credential updates and guarantees that a key's permissions never drift over its lifetime.

Keys also support an optional expiration timestamp (`expiresAt`). Setting an expiration date provides automatic cleanup for short-lived access, such as temporary access for contractors, staging environments, or one-off data migrations.

## Disabling credential checking

Setting `EXOFIND_AUTH_MODE=none` turns off all credential checking. In this mode, the engine treats every incoming request as fully authorized, regardless of whether a credential is provided.

This setting exists for local development. In any environment accessible over a network, disabling authentication allows anyone who can reach the HTTP port to read, modify, or delete every index and definition across the deployment. Production deployments must always run with authentication enabled.
