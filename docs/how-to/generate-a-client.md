# Generating an API client

This guide shows you how to locate the Exofind OpenAPI document, generate an API client, configure authentication, and keep the generated code current. Use this guide when building an application or service integration against an Exofind deployment.

## Prerequisites

Before generating an API client, ensure you have:

- Access to a running Exofind node or an engine build directory.
- An OpenAPI code generator that supports OpenAPI 3.1.0, such as `@openapitools/openapi-generator-cli`.
- An Exofind API key to authenticate requests.

## Obtaining the OpenAPI document

You can fetch the OpenAPI document from a running node or read it from a local engine build.

- **From a running node**: Send a request to `GET /q/openapi`. The endpoint does not require an API key and answers regardless of the node authentication mode. The default format is YAML. To get JSON, query `GET /q/openapi?format=json`, query `GET /q/openapi.json`, or send an `Accept: application/json` header.
- **From an engine build**: Run `mise run build` or `./mvnw package`. The build outputs `target/openapi/openapi.yaml` and `target/openapi/openapi.json`.

The document uses OpenAPI specification version `3.1.0`. Ensure your generator supports OpenAPI 3.1.

## Generating the client code

Run your generator against the document URL or the local file path.

To generate a TypeScript client using `openapi-generator-cli`, run:

```shell
npx @openapitools/openapi-generator-cli generate -i http://localhost:8080/q/openapi?format=json -g typescript-fetch -o ./exofind-client
```

To target a different programming language, replace `typescript-fetch` with the desired generator name in the `-g` flag. To generate from a build artifact, pass the path `target/openapi/openapi.json` to the `-i` flag.

The generated methods are named after the `operationId` of each operation, such as `search`, `add`, `scan`, `audit`, and `reindex`. Operations are grouped by tag, and each tag links to the reference page that describes it in full.

## Configuring the server address and authentication

Configure your generated client instance with the deployment host and API credential:

1. Set the client base URL to point to your deployment address rather than the default `http://localhost:8080` listed in the document.
2. Set the default `Authorization` header to `Bearer <key>`, where `<key>` is your API key.

Exofind endpoints do not read credentials from query parameters or cookies. Operations in the OpenAPI document do not declare security requirements directly, so generated clients do not attach credentials automatically. You must configure the default authorization header on the client instance.

## Regenerating after engine upgrades

Regenerate your client code after upgrading your Exofind deployment.

The API version is `v1alpha1`. The API is experimental and changes without maintaining backward compatibility between releases. Rebuilding your client against the updated OpenAPI document ensures method signatures, models, and endpoints match the upgraded node.

## Confirming the result

Verify that the generated client communicates with the deployment:

1. Instantiate the generated client with your base URL and `Authorization: Bearer <key>` header.
2. Call an endpoint method, such as performing a search or reading an index.
3. Verify that the client sends the request to the `/v1alpha1` prefix and parses the response without errors.

## Related

- [API conventions](../reference/api-conventions.md) - the rules every endpoint
  shares, including media types, conditional requests and status codes.
- [Errors](../reference/errors.md) - the error body and the code vocabulary.
- [Handle errors in a client](handle-api-errors.md) - routing a failure by its
  code, and retrying without indexing anything twice.
