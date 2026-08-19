package fun.fengwk.kkstudio.harness.runtime.model.provider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;

import java.util.List;

/**
 * Context-pressure 纯检测器契约。CANCELLED 不在 facts 中表达，由调用方（adapter classify）先行排除，本测试只验证 detector 的
 * boolean 判定与判定顺序。
 */
class ContextPressureDetectorTest {

  /** 意图：facts 是错误/响应的不可变价值对象，除 providerType 外字段可空，contextWindow 非空必须为正。 */
  @Test
  void enforcesFactsInvariants() {
    assertThrows(
        NullPointerException.class,
        () -> new ContextPressureFacts(null, null, null, null, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ContextPressureFacts(ProviderType.OPENAI, null, null, null, null, null, 0L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ContextPressureFacts(ProviderType.OPENAI, null, null, null, null, null, -1L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ContextPressureFacts(ProviderType.OPENAI, 99, null, null, null, null, null));
  }

  /** 意图：rate limit / throttling 永远不是 context pressure，即使消息同时含 context 字样或带 context code。 */
  @Test
  void rateLimitAndThrottlingAreNeverContextPressure() {
    assertFalse(detect(errorFacts(429, "context_length_exceeded", "too many requests")));
    assertFalse(detect(errorFacts(null, null, "429 max retries: maximum context length exceeded")));
    assertFalse(detect(errorFacts(null, null, "rate limit reached, reduce your pace")));
    assertFalse(detect(errorFacts(null, null, "request throttled by upstream gateway")));
    assertFalse(detect(errorFacts(null, "request_too_large", "too many requests, retry later")));
  }

  /** 意图：HTTP 413 是确定的 context wall，无论过期消息是否另有说明。 */
  @Test
  void http413IsContextPressure() {
    assertTrue(detect(errorFacts(413, null, "Request Entity Too Large")));
    assertTrue(detect(errorFacts(null, null, "HTTP 413 average token usage is too high")));
  }

  /** 意图：400+ 显式 context code/type 一律判定为 context pressure。 */
  @Test
  void explicitContextCodesAreContextPressureOnFourHundredPlus() {
    for (String code :
        List.of(
            "context_length_exceeded",
            "request_too_large",
            "model_context_window_exceeded",
            "context_window_exceeded")) {
      assertTrue(detect(errorFacts(400, code, null)), code);
      assertTrue(detect(errorFacts(null, null, "body code=" + code)), code);
    }
  }

  /**
   * 意图：普通 400（无 context code、无 context pattern）不得误判；通用 invalid_request_error type 不是 context code。
   */
  @Test
  void plain400IsNotContextPressure() {
    assertFalse(detect(errorFacts(400, null, "Bad Request")));
    assertFalse(detect(errorFacts(400, "invalid_request_error", "bad request, fix the payload")));
  }

  /** 意图：Provider 特有消息 pattern 矩阵（case-insensitive、移植 Pi 语义）全部命中。每条对应一个真实厂商的 context 错误文本。 */
  @Test
  void providerSpecificMessagesAreContextPressure() {
    for (String message :
        List.of(
            "Request body: Your prompt is too long for this model", // Anthropic
            "anthropic error type request_too_large", // Anthropic
            "Your input exceeds the context window of 128000 tokens", // OpenAI
            "error code context_length_exceeded", // OpenAI
            "This model's maximum context length is 128000 tokens", // OpenAI-compatible
            "Input length (182301) exceeds model's maximum context length (128000)", // OpenAI-compatible
            "Requested token count exceeds the max tokens limit", // OpenAI-compatible
            "the input token count (90001) exceeds the maximum number of tokens", // Google
            "INPUT TOKEN COUNT 90001 EXCEEDS THE MAXIMUM ALLOWED", // Google (case-insensitive)
            "Your request exceeds the maximum prompt length of 60000 tokens", // xAI
            "prompt is too large, reduce the length of the messages or completion", // Groq
            "request past the available context size of llama server", // llama.cpp
            "the number of tokens to keep (90000) is not enough, greater than context length", // LM
            // Studio
            "prompt token count exceeds limit configured for Copilot", // GitHub Copilot
            "context window exceeds limit configured for the model", // MiniMax
            "request exceeded model token limit")) { // Kimi
      assertTrue(detect(errorFacts(null, null, message)), message);
    }
  }

  /** 意图：authoritative usage 的 prompt tokens 达到/超过 window 即 silent context wall；低于 window 不命中。 */
  @Test
  void silentPromptUsageReachingWindowIsContextPressure() {
    long window = 128_000L;
    assertTrue(
        detect(responseFacts(null, new ModelUsage(100_000, 10, 28_000, 0, 0, 0, 128_010), window)));
    assertTrue(
        detect(responseFacts(null, new ModelUsage(128_000, 10, 0, 0, 0, 0, 128_010), window)));
    assertFalse(
        detect(responseFacts(null, new ModelUsage(100_000, 10, 27_999, 0, 0, 0, 127_999), window)));
  }

  /** 意图：严格 LENGTH 恢复条件——LENGTH + output==0 + prompt ≥ ceil(window*0.99) 才成立；低于边界或含 output 不成立。 */
  @Test
  void strictLengthIsContextPressureOnlyAtTheWindowEdge() {
    long window = 128_000L;
    long threshold = 126_720L; // ceil(128000 * 0.99)
    assertTrue(
        detect(
            responseFacts(
                GenerationStopReason.LENGTH,
                new ModelUsage(threshold, 0, 0, 0, 0, 0, threshold),
                window)));
    assertTrue(
        detect(
            responseFacts(
                GenerationStopReason.LENGTH,
                new ModelUsage(90_000, 0, threshold - 90_000L, 0, 0, 0, threshold),
                window)));
    assertFalse(
        detect(
            responseFacts(
                GenerationStopReason.LENGTH,
                new ModelUsage(threshold - 1, 0, 0, 0, 0, 0, threshold - 1),
                window)));
    // 有 output 的普通 LENGTH 不是 context wall（模型只是到达 max tokens）。
    assertFalse(
        detect(
            responseFacts(
                GenerationStopReason.LENGTH,
                new ModelUsage(threshold, 16, 0, 0, 0, 0, threshold + 16),
                window)));
    // 明显低于 window 的 LENGTH 同样不命中。
    assertFalse(
        detect(
            responseFacts(
                GenerationStopReason.LENGTH,
                new ModelUsage(10_000, 0, 0, 0, 0, 0, 10_000),
                window)));
    // 非 LENGTH stop reason 即使 prompt 达到 0.99 阈值（仍低于 window）也不触发严格恢复。
    assertFalse(
        detect(
            responseFacts(
                GenerationStopReason.COMPLETE,
                new ModelUsage(126_720, 0, 0, 0, 0, 0, 126_720),
                window)));
  }

  /** 意图：缺少 usage 或 contextWindow 时，usage/LENGTH 两条路径都必须保守地不命中。 */
  @Test
  void missingUsageOrWindowIsNotContextPressure() {
    assertFalse(
        ContextPressureDetector.detect(
            new ContextPressureFacts(
                ProviderType.OPENAI,
                null,
                null,
                null,
                GenerationStopReason.LENGTH,
                null,
                128_000L)));
    assertFalse(
        ContextPressureDetector.detect(
            new ContextPressureFacts(
                ProviderType.OPENAI,
                null,
                null,
                null,
                GenerationStopReason.LENGTH,
                new ModelUsage(100_000, 0, 0, 0, 0, 0, 100_000),
                null)));
  }

  private static boolean detect(ContextPressureFacts facts) {
    return ContextPressureDetector.detect(facts);
  }

  private static ContextPressureFacts errorFacts(Integer httpStatus, String code, String message) {
    return new ContextPressureFacts(
        ProviderType.OPENAI, httpStatus, code, message, null, null, null);
  }

  private static ContextPressureFacts responseFacts(
      GenerationStopReason stopReason, ModelUsage usage, long contextWindow) {
    return new ContextPressureFacts(
        ProviderType.OPENAI, null, null, null, stopReason, usage, contextWindow);
  }
}
