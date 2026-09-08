# Gemini Upstream Test Manifest

## Source Information
- Upstream: [LangChain4j](https://github.com/langchain4j/langchain4j)
- Exact Commit: `3a2f4dca6fb447e4d191624b3d588952ed9f4ce9`
- Version: `1.20.0`
- License: Apache-2.0

## Inventory Scope & Summary
- Test Sources: 51 Java test files under `langchain4j-google-ai-gemini/src/test/java`
- Total Methods: 676 methods (including all declared active/parameterized methods across nested classes and inherited methods from core/common test bases)
- Total Invocations: 676 invocations
- In-Scope Ported: 190 invocations (`PORTED` / `PASSED`)
- In-Scope Pending: 0 invocations (`PORT_PENDING` / `NOT_EXECUTED_PENDING_IMPLEMENTATION`)
- Out-of-Scope: 486 invocations (`OUT_OF_SCOPE` / `NOT_EXECUTED_OUT_OF_SCOPE`)
- Real Credential IT: 281 invocations (`realInteropStatus: NOT_EXECUTED_REQUIRES_CREDENTIAL`, independently tracked; each invocation separately remains `PORTED` or `OUT_OF_SCOPE` according to its wire-contract applicability)

## Principles & Adaptation Rules
1. **100% Comprehensive Coverage**: Enumerates all 51 test files in `langchain4j-google-ai-gemini/src/test/java` along with tracked inherited bases (`AbstractBaseChatModelIT`, `AbstractChatModelIT`, `AbstractStreamingChatModelIT`, `AbstractChatModelListenerIT`, `AbstractStreamingChatModelListenerIT`, `AbstractModelCatalogIT`, `AbstractAiServiceIT`, `AbstractAiServiceWithJsonSchemaIT`, `AbstractAiServiceWithToolsIT`, `AbstractStreamingAiServiceIT`, `AbstractEmbeddingModelIT`).
2. **Honest Status Discipline & No False Equivalence**: Exactly 190 applicable invocations are `PORTED` and `PASSED`. Exactly 0 pending invocations remain; all 486 non-applicable invocations are strictly classified as `OUT_OF_SCOPE` with concrete architectural mismatch reasons. Tests asserting different contracts or incompatible facades are rejected or renamed rather than creating pseudo-passes or false equivalence.
3. **Reflection-Backed Target Existence & JUnit-Annotation Validation**: Every `PORTED` invocation binds to a real local test target validated via JVM reflection in `UpstreamTestManifestTest`. Target classes must reside under `fun.fengwk.kkstudio.harness.provider.gemini.`, the referenced method must exist and carry JUnit `@Test` or `@ParameterizedTest`, and neither class nor method may have `@Disabled`.
4. **Classpath Fixture Validation**: All `PORTED` fixtures are verified to be relative paths starting with `gemini/fixtures/`, free of path-traversal segments (`..`), resolvable under classpath `/fun/fengwk/kkstudio/harness/provider/`, non-empty, and valid JSON (when ending with `.json`) or valid SSE without trailing content. Representative audit fixture-family resources (`stream-incremental.sse`, `stream-snapshot.sse`, `stream-thinking.sse`, `stream-tool-call.sse`) provide deterministic offline payloads for wire protocol, request mapping, and SSE stream decoding without live credentials.
5. **Native Thinking Adaptation**: Native provider adapts upstream `thinkingConfig` and reasoning effort to `thinkingLevel` and `includeThoughts` while strictly preserving deterministic replay and output semantics (thinking blocks and tool-use thinking verified in `GeminiThinkingTest`).
6. **Explicit Architectural Mismatch (OOS Policy)**: Every `OUT_OF_SCOPE` invocation specifies an exact `capabilityMismatch` (e.g. Google AI Caching REST API, Files upload API, Embedding models, Batch API, Imagen models, AiServices facade, Model catalog, countTokens, listener, proprietary Grounding tools).
7. **Orthogonal Credential Invariant**: 281 credential-bound integration tests (`@EnabledIfEnvironmentVariable(named = "GOOGLE_AI_GEMINI_API_KEY", ...)`) maintain their independent `realInteropStatus` marker of `NOT_EXECUTED_REQUIRES_CREDENTIAL`, tracked orthogonally to `mappingStatus`.
8. **Security & Sanitization**: API key is exclusively passed via `x-goog-api-key` header and never in URI, request body, or exception traces.
