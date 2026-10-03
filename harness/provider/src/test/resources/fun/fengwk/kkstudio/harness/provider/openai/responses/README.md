# OpenAI Responses Upstream Test Manifest

## Source Information
- Upstream: [LangChain4j](https://github.com/langchain4j/langchain4j)
- Exact Commit: `3a2f4dca6fb447e4d191624b3d588952ed9f4ce9`
- Version: `1.20.0`
- License: Apache-2.0

## Reading the inventory

[`upstream-test-manifest.json`](upstream-test-manifest.json) records the Responses mapping from
`langchain4j-open-ai/src/test/java` and tracked inherited methods. Its current inventory has
15 source files, 165 methods and 165 invocation records: 45 `PORTED`, 0 `PORT_PENDING` and
120 `OUT_OF_SCOPE`. Independently, 93 records carry
`realInteropStatus: NOT_EXECUTED_REQUIRES_CREDENTIAL`.

`UpstreamTestManifestTest` recounts these records and checks the summary. The counts describe
this manifest; comparing them to the upstream test tree requires the pinned upstream commit.

## Verifying a mapping

For `PORTED` records, reflection checks that `targetTest` resolves within
`fun.fengwk.kkstudio.harness.provider.openai.responses`, has `@Test` or `@ParameterizedTest`, and
is enabled at both class and method level. Fixtures must be non-empty classpath resources under
`openai/responses/fixtures/`, use traversal-free relative paths, and parse as strict JSON when
they have a `.json` extension. The fixture field is a manifest declaration; the manifest test checks
the resource independently of how the target test consumes payloads. Responses tests cover request
encoding, stream completion and reasoning replay, including encrypted content and empty reasoning placeholders.

`executionStatus: PASSED` is checked manifest metadata. Run the target tests to establish their
current result; the manifest test establishes usable targets and fixtures, rather than executing
all targets or proving upstream assertion equivalence. `OUT_OF_SCOPE` records explain their
contract differences through `capabilityMismatch`, and `PORT_PENDING` identifies unfinished
local mappings.

`realInteropStatus` is orthogonal to `mappingStatus`: an offline port may exist while the
corresponding live integration remains `NOT_EXECUTED_REQUIRES_CREDENTIAL`. A local pass verifies
the tested protocol behavior, not a successful authenticated Responses request. Keep mappings,
fixtures and the summary synchronized when changing tests.
