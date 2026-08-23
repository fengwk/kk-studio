package fun.fengwk.kkstudio.platform.harness.model.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.exception.HttpException;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ContextPressureFacts;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

/** Provider 异常到 context-pressure facts 的集中抽取契约。 */
class ProviderErrorFactsExtractorTest {

  /** 意图：优先从 LangChain4j 1.19 typed HttpException.statusCode() 抽取 HTTP status，直接与嵌套 cause 都命中。 */
  @Test
  void extractsHttpStatusFromTypedHttpExceptionDirectlyAndNested() {
    ContextPressureFacts direct =
        ProviderErrorFactsExtractor.extract(
            ProviderType.OPENAI, new HttpException(413, "Request payload too large"));
    assertEquals(413, direct.httpStatus());
    assertTrue(direct.errorMessage().contains("Request payload too large"));

    ContextPressureFacts nested =
        ProviderErrorFactsExtractor.extract(
            ProviderType.ANTHROPIC,
            new IllegalStateException(
                "provider request failed", new HttpException(429, "rate limited")));
    assertEquals(429, nested.httpStatus());
  }

  /**
   * 意图：从标准 JSON error body 抽取 providerErrorCodeOrType（OpenAI error.code / Anthropic error.type）。
   */
  @Test
  void extractsErrorCodeOrTypeFromJsonBody() {
    String openAi =
        "HTTP 400 body={\"error\":{\"message\":\"This model's maximum context length is 128000 "
            + "tokens.\",\"type\":\"invalid_request_error\",\"param\":null,\"code\":\"context_length_exceeded\"}}";
    ContextPressureFacts openAiFacts =
        ProviderErrorFactsExtractor.extract(ProviderType.OPENAI, new RuntimeException(openAi));
    assertEquals("context_length_exceeded", openAiFacts.providerErrorCodeOrType());

    String anthropic =
        "{\"type\":\"error\",\"error\":{\"type\":\"request_too_large\",\"message\":\"Your prompt is too long.\"}}";
    ContextPressureFacts anthropicFacts =
        ProviderErrorFactsExtractor.extract(
            ProviderType.ANTHROPIC, new RuntimeException(anthropic));
    assertEquals("request_too_large", anthropicFacts.providerErrorCodeOrType());
  }

  /** 意图：错误文本汇总保留 cause 链内容，且最多遍历 MAX_CAUSE_DEPTH 层，避免无限递归。 */
  @Test
  void aggregatesNestedMessagesUpToMaxDepth() {
    ContextPressureFacts facts =
        ProviderErrorFactsExtractor.extract(ProviderType.OPENAI, chain(10));
    String message = facts.errorMessage();
    assertTrue(message.contains("m0"));
    assertTrue(message.contains("m7"));
    assertFalse(message.contains("m8"));
    assertFalse(message.contains("m9"));
  }

  /** 意图：无 JSON / 非 object / 畸形 JSON / 无 error 字段都优雅降级为 null，绝不抛出。 */
  @Test
  void degradesGracefullyWithoutParseableJsonOrStatus() {
    ContextPressureFacts plain =
        ProviderErrorFactsExtractor.extract(
            ProviderType.OPENAI, new RuntimeException("HTTP 500 upstream blew up"));
    assertNull(plain.httpStatus());
    assertNull(plain.providerErrorCodeOrType());

    assertNull(
        ProviderErrorFactsExtractor.extract(
                ProviderType.OPENAI, new RuntimeException("stray { not json"))
            .providerErrorCodeOrType());
    assertNull(
        ProviderErrorFactsExtractor.extract(ProviderType.OPENAI, new RuntimeException("[1,2,3]"))
            .providerErrorCodeOrType());
    assertNull(
        ProviderErrorFactsExtractor.extract(
                ProviderType.OPENAI, new RuntimeException("{\"message\":\"generic body\"}"))
            .providerErrorCodeOrType());
  }

  /** 意图：extract 只构造错误侧 facts，providerType 透传，stopReason/usage/contextWindow 保持 null。 */
  @Test
  void preservesProviderTypeAndLeavesResponseFieldsNull() {
    ContextPressureFacts facts =
        ProviderErrorFactsExtractor.extract(ProviderType.GOOGLE, new RuntimeException("boom"));
    assertEquals(ProviderType.GOOGLE, facts.providerType());
    assertNull(facts.stopReason());
    assertNull(facts.usage());
    assertNull(facts.contextWindow());
  }

  private static RuntimeException chain(int depth) {
    RuntimeException current = new RuntimeException("m" + (depth - 1));
    for (int i = depth - 2; i >= 0; i--) {
      current = new RuntimeException("m" + i, current);
    }
    return current;
  }
}
