# OpenAI Responses Upstream Test Manifest

## Source Information
- Upstream: [LangChain4j](https://github.com/langchain4j/langchain4j)
- Exact Commit: `3a2f4dca6fb447e4d191624b3d588952ed9f4ce9`
- Version: `1.20.0`
- License: Apache-2.0

## Inventory Scope & Summary
- Test Sources: 15 Java test files under `langchain4j-open-ai/src/test/java`
- Total Methods: 165 methods (including all declared active methods and tracked inherited base class test methods)
- Total Invocations: 165 invocations
- In-Scope Ported: 53 invocations (`PORTED` / `PASSED`)
- In-Scope Pending: 0 invocations (`PORT_PENDING`)
- Out-of-Scope: 112 invocations (`OUT_OF_SCOPE` / `NOT_EXECUTED_OUT_OF_SCOPE`)
- Real Credential IT: 93 invocations (`realInteropStatus: NOT_EXECUTED_REQUIRES_CREDENTIAL`, independently tracked)

## Principles & Adaptation Rules
1. **100% Comprehensive Coverage**: Enumerates all 15 test files under `langchain4j-open-ai/src/test/java` and tracked inherited bases (`AbstractChatModelIT`, `AbstractStreamingChatModelIT`, `AbstractBaseChatModelIT`, `AbstractChatModelListenerIT`, `AbstractStreamingChatModelListenerIT`, `AbstractChatModelNonBlockingIT`, `PublisherVerification`).
2. **Honest Status Discipline & No False Equivalence**: Exactly 53 applicable invocations are `PORTED` and `PASSED`. Exactly 0 pending invocations remain; all 112 non-applicable invocations are strictly classified as `OUT_OF_SCOPE` with concrete architectural mismatch reasons.
3. **Reflection-Backed Target Existence & JUnit-Annotation Validation**: Every `PORTED` invocation binds to a real local test target validated via JVM reflection in `UpstreamTestManifestTest`. Target classes reside under `fun.fengwk.kkstudio.harness.provider.openai.responses.`, and referenced methods exist and carry JUnit `@Test` or `@ParameterizedTest` without `@Disabled`.
4. **Classpath Fixture Validation**: All `PORTED` fixtures are verified to be relative paths starting with `openai/responses/fixtures/`, free of path-traversal segments, resolvable under classpath `/fun/fengwk/kkstudio/harness/provider/`, non-empty, and valid JSON (when ending with `.json`).
5. **Native Reasoning Adaptation**: Native provider adapts OpenAI reasoning/thinking summaries and encrypted reasoning contents while preserving deterministic replay and output semantics.
6. **Explicit Architectural Mismatch (OOS Policy)**: Every `OUT_OF_SCOPE` invocation specifies an exact `capabilityMismatch` (e.g., Live credentials, BlockHound non-blocking thread checks, Reactive Streams TCK, Observability Listeners, Dynamic Tools, Custom Headers).
7. **Orthogonal Credential Invariant**: 93 credential-bound integration tests maintain their independent `realInteropStatus` marker of `NOT_EXECUTED_REQUIRES_CREDENTIAL` orthogonally to `mappingStatus`.
8. **Security & Sanitization**: Error bodies and sensitive credentials are scrubbed from exception logs to ensure no secret leakage.
