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

  /** 意图：facts 是错误/响应的不可变价值对象，除 providerType 外字段可空，contextWindow 非空必须为正、httpStatus 必须在 100..599。 */
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
    assertThrows(
        IllegalArgumentException.class,
        () -> new ContextPressureFacts(ProviderType.OPENAI, 600, null, null, null, null, null));
  }

  /** 意图：rate limit / throttling 永远不是 context pressure，即使消息带 context code 或同时在 429 状态。 */
  @Test
  void rateLimitAndThrottlingAreNeverContextPressure() {
    assertFalse(
        detect(
            errorFacts(ProviderType.OPENAI, 429, "context_length_exceeded", "too many requests")));
    assertFalse(
        detect(
            errorFacts(
                ProviderType.OPENAI,
                null,
                null,
                "429 max retries: maximum context length exceeded")));
    assertFalse(
        detect(
            errorFacts(ProviderType.OPENAI, null, null, "rate limit reached, reduce your pace")));
    assertFalse(
        detect(
            errorFacts(ProviderType.OPENAI, null, null, "request throttled by upstream gateway")));
    assertFalse(
        detect(
            errorFacts(
                ProviderType.OPENAI, null, "request_too_large", "too many requests, retry later")));
  }

  /** 意图：HTTP status 数字只在独立 token 上匹配；token 计数中的 429/413 子串（如 142900/141300）不得误判 status。 */
  @Test
  void statusNumbersRequireHttpWordBoundaries() {
    assertFalse(
        detect(errorFacts(ProviderType.OPENAI, null, null, "processed 142900 tokens this minute")));
    assertFalse(
        detect(
            errorFacts(
                ProviderType.OPENAI, null, null, "141300 tokens were counted for the prompt")));
    assertFalse(
        detect(errorFacts(ProviderType.OPENAI, null, null, "HTTP 200 OK, no errors shown")));
    // 整数 413/429 独立出现仍是 status 语义：413 为 context wall，429 为 rate limit（永远非 context）。
    assertTrue(
        detect(errorFacts(ProviderType.OPENAI, null, null, "HTTP 413 payload exceeds limit")));
    assertFalse(detect(errorFacts(ProviderType.OPENAI, null, null, "HTTP 429 too many requests")));
  }

  /** 意图：HTTP 413（typed status 或消息独立 413 token）是确定的 context wall。 */
  @Test
  void http413IsContextPressure() {
    assertTrue(detect(errorFacts(ProviderType.OPENAI, 413, null, "Request Entity Too Large")));
    assertTrue(
        detect(
            errorFacts(
                ProviderType.OPENAI, null, null, "HTTP 413 average token usage is too high")));
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
      assertTrue(detect(errorFacts(ProviderType.OPENAI, 400, code, null)), code);
      assertTrue(detect(errorFacts(ProviderType.OPENAI, null, null, "body code=" + code)), code);
    }
  }

  /**
   * 意图：普通 400（无 context code、无 context pattern）不得误判；通用 invalid_request_error type 不是 context code。
   */
  @Test
  void plain400IsNotContextPressure() {
    assertFalse(detect(errorFacts(ProviderType.OPENAI, 400, null, "Bad Request")));
    assertFalse(
        detect(
            errorFacts(
                ProviderType.OPENAI,
                400,
                "invalid_request_error",
                "bad request, fix the payload")));
  }

  /**
   * 意图：Provider 特有消息 pattern 矩阵（case-insensitive、移植 Pi 语义）按 ProviderType 分族全部命中；OpenAI
   * compatible/MiniMax/Kimi/xAI/Groq/llama.cpp/OpenRouter/Copilot/LM Studio 归 OPENAI 族。
   */
  @Test
  void providerMatrixMessagesAreContextPressure() {
    for (Object[] row :
        List.of(
            new Object[] {
              ProviderType.ANTHROPIC, "Request body: Your prompt is too long for this model"
            },
            new Object[] {
              ProviderType.OPENAI, "Your input exceeds the context window of 128000 tokens"
            },
            new Object[] {
              ProviderType.OPENAI, "This model's maximum context length is 128000 tokens"
            },
            new Object[] {
              ProviderType.OPENAI,
              "Input length (182301) exceeds model's maximum context length (128000)"
            },
            new Object[] {
              ProviderType.OPENAI, "Requested token count (182301) exceeds the max tokens limit"
            },
            new Object[] {
              ProviderType.GOOGLE, "the input token count (90001) exceeds the maximum allowed"
            },
            new Object[] {
              ProviderType.GOOGLE, "INPUT TOKEN COUNT 90001 EXCEEDS THE MAXIMUM ALLOWED"
            },
            new Object[] {
              ProviderType.OPENAI, "Your request exceeds the maximum prompt length of 60000 tokens"
            },
            new Object[] {
              ProviderType.OPENAI,
              "prompt is too large, reduce the length of the messages or completion"
            },
            new Object[] {
              ProviderType.OPENAI, "request past the available context size of llama server"
            },
            new Object[] {
              ProviderType.OPENAI,
              "the number of tokens to keep (90000) is not enough, greater than context length"
            },
            new Object[] {
              ProviderType.OPENAI, "prompt token count exceeds limit configured for Copilot"
            },
            new Object[] {
              ProviderType.OPENAI, "context window exceeds limit configured for the model"
            },
            new Object[] {ProviderType.OPENAI, "request exceeded model token limit"},
            new Object[] {ProviderType.OPENAI, "error code request_too_large in body"})) {
      assertTrue(
          detect(errorFacts((ProviderType) row[0], null, null, (String) row[1])),
          String.valueOf(row[1]));
    }
  }

  /** 意图：compound pattern 需要全部 markers 同时命中，任一宽泛单一子串单独出现不触发；ProviderType 分族隔离且共享权威 code。 */
  @Test
  void compoundPatternsRequireAllMarkersAndFamiliesSeparate() {
    // Google compound 需要 input token count + exceeds + maximum 同时出现（旧实现只拆单独子串会误判）。
    assertFalse(
        detect(
            errorFacts(ProviderType.GOOGLE, null, null, "the input token count was 90000 tokens")));
    assertFalse(
        detect(
            errorFacts(ProviderType.GOOGLE, null, null, "exceeds maximum allowed by this model")));
    assertTrue(
        detect(
            errorFacts(
                ProviderType.GOOGLE,
                null,
                null,
                "the input token count (90001) exceeds the maximum allowed")));
    // LM Studio compound 需要 tokens to keep + greater than + context length 同时出现。
    assertFalse(
        detect(
            errorFacts(
                ProviderType.OPENAI, null, null, "the number of tokens to keep is fixed at 4096")));
    assertFalse(
        detect(
            errorFacts(ProviderType.OPENAI, null, null, "greater than the context length budget")));
    assertTrue(
        detect(
            errorFacts(
                ProviderType.OPENAI,
                null,
                null,
                "tokens to keep (90000) greater than context length")));
    // OpenAI 族不接受 Anthropic 专有 pattern，反之亦然；权威 code 在所有族共享。
    assertFalse(
        detect(
            errorFacts(
                ProviderType.ANTHROPIC,
                null,
                null,
                "This model's maximum context length is 1000")));
    assertFalse(detect(errorFacts(ProviderType.OPENAI, null, null, "Your prompt is too long")));
    assertTrue(
        detect(errorFacts(ProviderType.OPENAI, null, null, "error: context_length_exceeded")));
    assertTrue(detect(errorFacts(ProviderType.ANTHROPIC, null, null, "error: request_too_large")));
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

  @Test
  void successfulResponseDetectionUsesOnlyAuthoritativeUsageAndStrictLength() {
    // response 路径不伪造 providerType，只复用 reached-window 与 strict LENGTH 两条规则。
    assertTrue(
        ContextPressureDetector.detectResponse(
            GenerationStopReason.COMPLETE,
            new ModelUsage(128_000, 0, 0, 0, 0, 0, 128_000),
            128_000));
    assertTrue(
        ContextPressureDetector.detectResponse(
            GenerationStopReason.LENGTH, new ModelUsage(126_720, 0, 0, 0, 0, 0, 126_720), 128_000));
    assertFalse(
        ContextPressureDetector.detectResponse(
            GenerationStopReason.LENGTH, new ModelUsage(126_720, 1, 0, 0, 0, 0, 126_721), 128_000));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ContextPressureDetector.detectResponse(
                GenerationStopReason.COMPLETE, new ModelUsage(1, 0, 0, 0, 0, 0, 1), 0));
  }

  private static boolean detect(ContextPressureFacts facts) {
    return ContextPressureDetector.detect(facts);
  }

  private static ContextPressureFacts errorFacts(
      ProviderType providerType, Integer httpStatus, String code, String message) {
    return new ContextPressureFacts(providerType, httpStatus, code, message, null, null, null);
  }

  private static ContextPressureFacts responseFacts(
      GenerationStopReason stopReason, ModelUsage usage, long contextWindow) {
    return new ContextPressureFacts(
        ProviderType.OPENAI, null, null, null, stopReason, usage, contextWindow);
  }
}
