# OpenAI Chat Upstream Test Manifest

## Source Information
- Upstream: [LangChain4j](https://github.com/langchain4j/langchain4j)
- Exact Commit: `3a2f4dca6fb447e4d191624b3d588952ed9f4ce9`
- Version: `1.20.0`
- License: Apache-2.0

## Reading the inventory

[`upstream-test-manifest.json`](upstream-test-manifest.json) records the Chat Completions mapping
from `langchain4j-open-ai/src/test/java` and tracked inherited methods. It contains 70 source files,
502 methods and 659 invocation records, including expanded parameter cases:
238 `PORTED`, 0 `PORT_PENDING` and 421 `OUT_OF_SCOPE`. Independently, 390 records carry
`realInteropStatus: NOT_EXECUTED_REQUIRES_CREDENTIAL`.

These numbers describe this manifest. `UpstreamTestManifestTest` recounts its records and checks
the summary; comparing the inventory with upstream requires the pinned commit. The mapping is
specific to Chat Completions, so applicability of another OpenAI API is recorded per invocation.

## Verifying a mapping

`PORTED` records reference local `targetTest` methods. Reflection checks that the target belongs
to `fun.fengwk.kkstudio.harness.provider.openai.chat`, has `@Test` or `@ParameterizedTest`, and is
enabled at class and method level. Fixtures must be non-empty classpath resources below
`openai/chat/fixtures/` with traversal-free relative paths; `.json` fixtures are strictly parsed.
The fixture field is a manifest declaration; the manifest test checks the resource independently
of how the target test consumes payloads.

`executionStatus: PASSED` is manifest metadata checked for ported records. Run the target tests
to establish their current result. Target existence and fixture validity alone establish a usable
mapping, not equivalence to every upstream assertion. Each `OUT_OF_SCOPE` record states its
`capabilityMismatch`; `PORT_PENDING` records identify unfinished mappings.

The credential dimension remains separate: a local offline port can coexist with
`NOT_EXECUTED_REQUIRES_CREDENTIAL` for its upstream live test. A local pass establishes the tested
wire behavior, not successful OpenAI interoperability with real credentials. Update mappings,
fixtures and the summary together.
