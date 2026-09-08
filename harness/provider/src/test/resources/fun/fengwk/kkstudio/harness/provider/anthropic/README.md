# Anthropic Upstream Test Manifest

## Source Information
- Upstream: [LangChain4j](https://github.com/langchain4j/langchain4j)
- Exact Commit: `3a2f4dca6fb447e4d191624b3d588952ed9f4ce9`
- Version: `1.20.0`
- License: Apache-2.0

## Inventory Scope & Summary
- Test Sources: 40 Java test files under `langchain4j-anthropic/src/test/java`
- Total Methods: 423 methods (including all declared active/parameterized methods and inherited methods from core/common/reactive test bases)
- Total Invocations: 577 invocations (fully expanded parameterized cases and TCK methods)
- In-Scope Ported: 155 invocations (`PORTED` / `PASSED`)
- In-Scope Pending: 0 invocations (`PORT_PENDING` / `NOT_EXECUTED_PENDING_IMPLEMENTATION`)
- Out-of-Scope: 422 invocations (`OUT_OF_SCOPE` / `NOT_EXECUTED_OUT_OF_SCOPE`)
- Real Credential IT: 121 invocations (`realInteropStatus: NOT_EXECUTED_REQUIRES_CREDENTIAL`, independently tracked; each invocation separately remains `PORTED` or `OUT_OF_SCOPE` according to its wire-contract applicability)

## Principles & Adaptation Rules
1. **100% Comprehensive Coverage**: Enumerates all 40 test files in `langchain4j-anthropic/src/test/java` along with tracked inherited bases (`AbstractBaseChatModelIT`, `AbstractChatModelIT`, `AbstractStreamingChatModelIT`, `AbstractChatModelListenerIT`, `AbstractStreamingChatModelListenerIT`, `AbstractStreamingChatModelPublisherListenerTest`, `AbstractModelCatalogIT`, `AbstractChatModelNonBlockingIT`, `AbstractAiServiceIT`, `AbstractAiServiceWithJsonSchemaIT`, `AbstractAiServiceWithToolsIT`, `AbstractStreamingAiServiceIT`, `PublisherVerification`).
2. **Honest Status Discipline & No False Equivalence**: Exactly 155 applicable invocations are `PORTED` and `PASSED`. Exactly 0 pending invocations remain; all 422 non-applicable invocations are strictly classified as `OUT_OF_SCOPE` with concrete architectural mismatch reasons. Tests asserting different contracts or incompatible facades are rejected or renamed rather than creating pseudo-passes or false equivalence.
3. **Reflection-Backed Target Existence & JUnit-Annotation Validation**: Every `PORTED` invocation binds to a real local test target validated via JVM reflection in `UpstreamTestManifestTest`. Target classes must reside under `fun.fengwk.kkstudio.harness.provider.anthropic.`, the referenced method must exist and carry JUnit `@Test` or `@ParameterizedTest`, and neither class nor method may have `@Disabled`.
4. **Classpath Fixture Validation & Representative Audit Families**: All `PORTED` fixtures are verified to be relative paths starting with `anthropic/fixtures/`, free of path-traversal segments (`..`), resolvable under classpath `/fun/fengwk/kkstudio/harness/provider/`, non-empty, and valid JSON (when ending with `.json`) without trailing content. Representative audit fixture-family resources (`messages-wire.json`, `request-mapping.json`, `create-message.json`, `streaming-messages.sse`) provide deterministic offline payloads for wire protocol, request mapping, and SSE stream decoding without live credentials.
5. **Native Thinking Adaptation**: Native provider adapts upstream `thinkingType` and `budget` to typed reasoning capability and effort settings while strictly preserving deterministic replay and output semantics (positive thinking and tool-use thinking verified in `AnthropicThinkingTest`; upstream return/send toggle and per-request overrides marked `OUT_OF_SCOPE`).
6. **Explicit Architectural Mismatch (OOS Policy)**: Every `OUT_OF_SCOPE` invocation specifies an exact `capabilityMismatch` (e.g. SDK Builder equals/hash/toBuilder facade, Batch API, count_tokens, model catalog, AiServices facade, server tools/skills, SDK automatic retry, Reactive Streams Publisher/TCK/BlockHound, third-party caching proxy, missing `strict` tool definitions or arbitrary custom-parameter escape hatches, tool choice controls, `ResponseFormat` structured output beta, mid-conversation SYSTEM policy, or negative capability tests where Anthropic native wire supports the feature).
7. **Orthogonal Credential Invariant**: 121 credential-bound integration tests (`@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", ...)`) maintain their independent `realInteropStatus` marker of `NOT_EXECUTED_REQUIRES_CREDENTIAL`, tracked orthogonally to `mappingStatus`.
8. **Security & Sanitization**: Error body logging and credential header protections are tracked via `securityAdaptation`.
