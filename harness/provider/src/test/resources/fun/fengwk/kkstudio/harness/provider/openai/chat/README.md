# OpenAI Chat Upstream Test Manifest

## Source Information
- Upstream: [LangChain4j](https://github.com/langchain4j/langchain4j)
- Exact Commit: `3a2f4dca6fb447e4d191624b3d588952ed9f4ce9`
- Version: `1.20.0`
- License: Apache-2.0

## Inventory Scope & Summary
- Test Sources: 70 Java test files under `langchain4j-open-ai/src/test/java`
- Total Methods: 502 methods (including all declared active/parameterized methods and inherited methods from core/common/reactive test bases)
- Total Invocations: 659 invocations (fully expanded parameterized cases and TCK methods)
- In-Scope Ported: 240 invocations (`PORTED` / `PASSED`)
- In-Scope Pending: 0 invocations (`PORT_PENDING` / `NOT_EXECUTED_PENDING_IMPLEMENTATION`)
- Out-of-Scope: 419 invocations (`OUT_OF_SCOPE` / `NOT_EXECUTED_OUT_OF_SCOPE`)
- Real Credential IT: 390 invocations (`realInteropStatus: NOT_EXECUTED_REQUIRES_CREDENTIAL`, independently tracked; each invocation separately remains `PORTED` or `OUT_OF_SCOPE` according to its wire-contract applicability)

## Principles & Adaptation Rules
1. **100% Comprehensive Coverage**: Enumerates all 70 test files in `langchain4j-open-ai/src/test/java` along with tracked inherited bases (`AbstractBaseChatModelIT`, `AbstractChatModelIT`, `AbstractStreamingChatModelIT`, `AbstractChatModelListenerIT`, `AbstractStreamingChatModelListenerIT`, `AbstractStreamingChatModelPublisherListenerTest`, `AbstractModelCatalogIT`, `AbstractChatModelNonBlockingIT`, `AbstractEmbeddingModelIT`, `AbstractModerationModelListenerIT`, `PublisherVerification`).
2. **Honest Status Discipline & No False Equivalence**: Exactly 240 applicable invocations are `PORTED` and `PASSED`. Exactly 0 pending invocations remain; all 419 non-applicable invocations are strictly classified as `OUT_OF_SCOPE` with concrete architectural mismatch reasons. Tests asserting different contracts or incompatible facades are rejected or categorized as out-of-scope rather than creating pseudo-passes or false equivalence.
3. **Reflection-Backed Target Existence & JUnit-Annotation Validation**: Every `PORTED` invocation binds to a real local test target validated via JVM reflection in `UpstreamTestManifestTest`. Target classes must reside under `fun.fengwk.kkstudio.harness.provider.openai.chat.`, the referenced method must exist and carry JUnit `@Test` or `@ParameterizedTest`, and neither class nor method may have `@Disabled`.
4. **Classpath Fixture Validation & Representative Audit Families**: All `PORTED` fixtures are verified to be relative paths starting with `openai/chat/fixtures/`, free of path-traversal segments (`..`), resolvable under classpath `/fun/fengwk/kkstudio/harness/provider/`, non-empty, and valid JSON (when ending with `.json`) without trailing content. Representative audit fixture-family resources (`create-chat-completion.json`, `streaming-chat.sse`, `error-response.json`, `thinking-stream.json`, `tools-stream.json`) provide deterministic offline payloads for wire protocol, request mapping, and SSE stream decoding without live credentials.
5. **Native Reasoning Fidelity & Replay**: Native provider models upstream reasoning parameters into typed reasoning capability and effort settings while strictly preserving deterministic replay and output semantics (verified in `OpenAiChatThinkingTest` and `OpenAiChatStreamAccumulatorTest`).
6. **Explicit Architectural Mismatch (OOS Policy)**: Every `OUT_OF_SCOPE` invocation specifies an exact `capabilityMismatch` (e.g. OpenAI Responses API /v1/responses preview protocol, Embedding API /v1/embeddings, Image API /v1/images, Audio/TTS API /v1/audio, Moderation API /v1/moderations, Legacy Completions API /v1/completions, TokenCountEstimator client library, Reactive Streams Publisher / TCK / BlockHound policing, dynamic CustomHeadersSupplier facade, external CachingProxy, or LangChain4j high-level AiServices reflection proxies).
7. **Orthogonal Credential Invariant**: 390 credential-bound integration tests (`@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", ...)`) maintain their independent `realInteropStatus` marker of `NOT_EXECUTED_REQUIRES_CREDENTIAL`, tracked orthogonally to `mappingStatus`.
8. **Security & Sanitization**: Error body logging and credential header protections are tracked via `securityAdaptation`.
