# Gemini Upstream Test Manifest

## Source Information
- Upstream: [LangChain4j](https://github.com/langchain4j/langchain4j)
- Exact Commit: `3a2f4dca6fb447e4d191624b3d588952ed9f4ce9`
- Version: `1.20.0`
- License: Apache-2.0

## Reading the inventory

[`upstream-test-manifest.json`](upstream-test-manifest.json) maps tests from
`langchain4j-google-ai-gemini/src/test/java` and tracked inherited methods to local
wire-contract tests. The manifest contains 51 source files, 676 methods and 676 invocation records:
145 `PORTED`, 0 `PORT_PENDING` and 531 `OUT_OF_SCOPE`. Independently, 281 records carry
`realInteropStatus: NOT_EXECUTED_REQUIRES_CREDENTIAL`.

`UpstreamTestManifestTest` recounts these manifest records and checks the summary. These counts
describe the recorded inventory; checking its correspondence to the upstream test tree requires
the pinned upstream commit.

## Verifying a mapping

For a `PORTED` record, the test resolves `targetTest` by reflection within
`fun.fengwk.kkstudio.harness.provider.gemini`, checks the JUnit annotation and rejects disabled
classes or methods. Fixtures must resolve to non-empty classpath resources under
`gemini/fixtures/`, with relative paths free of traversal. The current declared fixtures are SSE
resources, checked for existence and non-empty content. The fixture field is a manifest declaration;
the manifest test checks the resource independently of how the target test consumes payloads.

`executionStatus: PASSED` is checked metadata for a `PORTED` mapping. Run the target tests to
establish their current result; the manifest test establishes target/fixture validity, rather
than executing each target or proving all upstream assertions equivalent. `capabilityMismatch`
explains each `OUT_OF_SCOPE` decision, and `PORT_PENDING` identifies unfinished local mappings.

Credential status is independent of the mapping: an offline port can exist while the corresponding
live integration remains `NOT_EXECUTED_REQUIRES_CREDENTIAL`. A local pass verifies the tested
protocol behavior, not an authenticated request to Gemini. Keep mappings, fixtures and the
summary in sync when changing tests.
