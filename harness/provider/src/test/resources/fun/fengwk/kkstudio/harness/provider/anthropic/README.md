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
- In-Scope Ported: 6 invocations (`PORTED` / `PASSED`)
- In-Scope Pending: 272 invocations (`PORT_PENDING` / `NOT_EXECUTED_PENDING_IMPLEMENTATION`)
- Out-of-Scope: 299 invocations (`OUT_OF_SCOPE` / `NOT_EXECUTED_OUT_OF_SCOPE`)
- Real Credential IT: 121 invocations (`realInteropStatus: NOT_EXECUTED_REQUIRES_CREDENTIAL`, independently tracked and mapped to deterministic offline fixtures)

## Principles & Adaptation Rules
1. **100% Comprehensive Coverage**: Enumerates all 40 test files in `langchain4j-anthropic/src/test/java` along with tracked inherited bases (`AbstractBaseChatModelIT`, `AbstractChatModelIT`, `AbstractStreamingChatModelIT`, `AbstractChatModelListenerIT`, `AbstractStreamingChatModelListenerIT`, `AbstractStreamingChatModelPublisherListenerTest`, `AbstractModelCatalogIT`, `AbstractChatModelNonBlockingIT`, `AbstractAiServiceIT`, `AbstractAiServiceWithJsonSchemaIT`, `AbstractAiServiceWithToolsIT`, `AbstractStreamingAiServiceIT`, `PublisherVerification`).
2. **Honest Status Discipline**: No false passes. In-scope items with real local tests and equivalent assertions are marked `PORTED` and `PASSED` (6 items in `AnthropicStreamingDecoderTest`); unimplemented items strictly remain `PORT_PENDING` and `NOT_EXECUTED_PENDING_IMPLEMENTATION` (272 items); absolutely no items use `SKIPPED` as pseudo-passes.
3. **Explicit Architectural Mismatch**: Every `OUT_OF_SCOPE` invocation specifies an exact `capabilityMismatch` (e.g. SDK Builder equals/hash/toBuilder facade, Batch API, count_tokens, model catalog, AiServices facade, server tools/skills, SDK automatic retry, Reactive Streams Publisher/TCK/BlockHound, third-party caching proxy, arbitrary custom headers/userId).
4. **Credential Isolation**: Credential-bound tests (`@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", ...)`) covering wire/streaming protocol are mapped to deterministic offline fixture targets (`PORT_PENDING`), while preserving real interoperability tracking under `realInteropStatus: NOT_EXECUTED_REQUIRES_CREDENTIAL`.
5. **Replay Core Included**: Disabled thinking/signature tests (`AnthropicChatModelThinkingIT`, `AnthropicStreamingChatModelThinkingIT`) are tracked as replay core capabilities and mapped to offline deterministic fixtures (`PORT_PENDING`).
6. **Security & Sanitization**: Error body logging and credential header protections are tracked via `securityAdaptation`.

## Post-Integration TODO
- As remaining native Anthropic Messages wire/mapping/thinking fixtures and tests are integrated in future PR slices, bind corresponding `PORT_PENDING` invocations to concrete target test methods and transition `executionStatus` to `PASSED`.
