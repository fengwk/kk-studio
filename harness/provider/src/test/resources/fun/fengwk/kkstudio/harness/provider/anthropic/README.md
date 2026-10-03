# Anthropic Upstream Test Manifest

## Source Information
- Upstream: [LangChain4j](https://github.com/langchain4j/langchain4j)
- Exact Commit: `3a2f4dca6fb447e4d191624b3d588952ed9f4ce9`
- Version: `1.20.0`
- License: Apache-2.0

## Reading the inventory

[`upstream-test-manifest.json`](upstream-test-manifest.json) maps tests from
`langchain4j-anthropic/src/test/java` and tracked inherited methods to local wire-contract tests.
Its current inventory contains 40 source files, 423 methods and 577 invocation records:
145 `PORTED`, 0 `PORT_PENDING` and 432 `OUT_OF_SCOPE`. Independently, 121 records carry
`realInteropStatus: NOT_EXECUTED_REQUIRES_CREDENTIAL`.

These are counts of this manifest, including its expanded parameter cases and inherited-method
records. `UpstreamTestManifestTest` recounts them and checks the summary; verifying the upstream
inventory itself requires inspecting the pinned upstream commit.

## Verifying a mapping

For each `PORTED` record, the test checks that `targetTest` resolves to a class and method in
`fun.fengwk.kkstudio.harness.provider.anthropic`, carries `@Test` or `@ParameterizedTest`, and is
enabled at both class and method level. Declared fixtures must be non-empty classpath resources
under `anthropic/fixtures/`, use relative paths without traversal, and parse as strict JSON when
they have a `.json` extension. The fixture field is a manifest declaration; the manifest test
checks the resource independently of how the target test consumes payloads.

`PORTED` records declare `executionStatus: PASSED`; that field is checked as manifest metadata.
The target tests must also be run to establish a current test result. Reflection and fixture
checks establish that a mapping is usable, rather than proving equivalence to every upstream
assertion. `OUT_OF_SCOPE` records explain the contract difference in `capabilityMismatch`;
`PORT_PENDING` records identify remaining implementation work.

Credential status is a separate dimension: a record can have a local offline mapping while its
upstream live integration remains `NOT_EXECUTED_REQUIRES_CREDENTIAL`. A local pass therefore
establishes the tested wire behavior, not a successful request to Anthropic with real credentials.
Update the mapping, fixtures and summary together when changing a test.
