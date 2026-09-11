# Generating an API client

This guide shows you how to locate the Exofind OpenAPI document, generate an API client, configure authentication, and keep the generated code current. Use this guide when building an application or service integration against an Exofind deployment.

## Prerequisites

Before generating an API client, ensure you have:

- Access to a running Exofind node or an engine build directory.
- An OpenAPI code generator that supports OpenAPI 3.1.0, such as `@openapitools/openapi-generator-cli`.
- An Exofind API key to authenticate requests.

## Obtaining the OpenAPI document

You can fetch the OpenAPI document from a running node, download the published copy, or read it from a local engine build.

- **From a running node**: Send a request to `GET /q/openapi`. The endpoint does not require an API key and answers regardless of the node authentication mode. The default format is YAML. To get JSON, query `GET /q/openapi?format=json`, query `GET /q/openapi.json`, or send an `Accept: application/json` header. Use this source to match the node you deploy.
- **From the website**: Download <https://exofind.dev/openapi.yaml>. This is the document the [REST API pages](https://exofind.dev/api/) are generated from, and it describes the current release.
- **From an engine build**: Run `mise run build` or `./mvnw package`. The build outputs `target/openapi/openapi.yaml` and `target/openapi/openapi.json`.

The document uses OpenAPI specification version `3.1.0`. Ensure your generator supports OpenAPI 3.1.

The document declares one server, `{node}`, a variable whose default is `http://localhost:8080`. Set it to the address of your deployment when you generate the client, or set the base URL on the client instance.

## Generating the client code

Run your generator against the document URL or the local file path.

To generate a TypeScript client using `openapi-generator-cli`, run:

```shell
npx @openapitools/openapi-generator-cli generate -i http://localhost:8080/q/openapi?format=json -g typescript-fetch -o ./exofind-client
```

To target a different programming language, replace `typescript-fetch` with the desired generator name in the `-g` flag. To generate from a build artifact, pass the path `target/openapi/openapi.json` to the `-i` flag.

The generated methods are named after the `operationId` of each operation, such as `search`, `add`, `scan`, `audit`, and `reindex`. Operations are grouped by tag, and each tag links to the reference page that describes it in full.

## Working with the tagged unions

Four schemas are tagged unions, where a `type` property selects the member and the properties available on it:

| Schema | Members | Where you use it |
| --- | --- | --- |
| `Clause` | `FieldClause`, `TextClause`, `KnnClause`, `NestedClause`, `AndClause`, `OrClause`, `NotClause`, `BoostClause`, `FuseClause` | The `query` and `filters` of a search. |
| `Matcher` | `EqualsMatcher`, `InMatcher`, `AnyMatcher`, `PrefixMatcher`, `UnderMatcher`, `RangeMatcher`, `RangesMatcher`, `TextMatcher`, `DistanceMatcher` | The `match` of a field clause. |
| `Sort` | `FieldSort`, `ScoreSort`, `DistanceSort` | The `sort` of a search. |
| `FieldDefinition` | One per field type, such as `StringFieldDefinition` and `GeoPointFieldDefinition` | The `fields` of an index definition. |

Each union declares `oneOf` over its members and a `discriminator` on `type`, so a generator produces one model per member and selects between them by the value of `type`. Each member declares `type` as a required property holding the single value that selects it.

The engine also accepts a payload that omits `type`. A clause or a sort without it is read as a field clause or a field sort, and a matcher without it is read as an `equals` matcher. Generated clients always send `type`, so the shorter form applies to JSON you write by hand.

Confirm that your generator supports `discriminator` before you build on these models. A generator that does not produces an untyped object for each union, and you construct the members yourself.

## Accepting values a release adds

The API can add a value to a field that carries a fixed set of values, such as a new sort order or a new error code. [API conventions](../reference/api-conventions.md#what-a-client-must-do) requires a client to accept a value it does not know.

Generators differ in how they meet this. Some produce a closed enumeration that refuses an unrecognized value, which makes a node newer than the generated client fail to parse a response it should have accepted. Check what your generator produces for an enumerated field, and choose the setting that keeps an unknown value over the one that refuses it. Regenerating the client after each upgrade of the deployment closes the same gap.

## Configuring the server address and authentication

Configure your generated client instance with the deployment host and API credential:

1. Set the client base URL to point to your deployment address rather than the default `http://localhost:8080` listed in the document.
2. Set the default `Authorization` header to `Bearer <key>`, where `<key>` is your API key.

Exofind endpoints do not read credentials from query parameters or cookies. Operations in the OpenAPI document do not declare security requirements directly, so generated clients do not attach credentials automatically. You must configure the default authorization header on the client instance.

## Regenerating after engine upgrades

Regenerate your client code after upgrading your Exofind deployment.

The API version is `v1alpha1`. A release can introduce breaking changes. Regenerating your client against the updated OpenAPI document ensures that method signatures, models, and endpoints match the upgraded node.

Most releases only add to the API. For expected changes between releases and how to handle them, see [Compatibility](../reference/api-conventions.md#compatibility).

## Confirming the result

Verify that the generated client communicates with the deployment:

1. Instantiate the generated client with your base URL and `Authorization: Bearer <key>` header.
2. Call an endpoint method, such as performing a search or reading an index.
3. Verify that the client sends the request to the `/v1alpha1` prefix and parses the response without errors.

## Related

- [API conventions](../reference/api-conventions.md) - The rules every endpoint shares, including media types, conditional requests, and status codes.
- [Errors](../reference/errors.md) - The error body and the code vocabulary.
- [Handle errors in a client](handle-api-errors.md) - Routing a failure by its code, and retrying without indexing anything twice.
- [Testing an application against a node](test-against-a-node.md) - Running the client against a node in a container.
