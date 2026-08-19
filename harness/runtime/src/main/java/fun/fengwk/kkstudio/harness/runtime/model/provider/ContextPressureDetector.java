package fun.fengwk.kkstudio.harness.runtime.model.provider;

import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Context-pressure 纯检测器。
 *
 * <p>对 {@link ContextPressureFacts} 返回 boolean，不引入任何 durable 状态/status。判定顺序（一旦命中即返回）：
 *
 * <ol>
 *   <li>rate limit / throttling（HTTP 429、消息含 {@code 429} / {@code rate limit} / {@code too many
 *       requests} / {@code throttl}）永远为 false，即使消息同时含 context 字样；CANCELLED 不在本检测器表达，由调用方（adapter
 *       classify）先行排除。
 *   <li>HTTP 413 为 true。
 *   <li>显式 context code/type（如 {@code context_length_exceeded}、{@code request_too_large}、{@code
 *       model_context_window_exceeded}、{@code context_window_exceeded}）为 true；普通 400 无 context code
 *       不得误判。
 *   <li>Provider 特有 context 消息 pattern 为 true。
 *   <li>权威 usage 的 prompt tokens（input + cacheRead）达到/超过 contextWindow 为 true。
 *   <li>严格 LENGTH：stopReason 为 LENGTH 且 outputTokens == 0 且 prompt tokens ≥ ceil(contextWindow *
 *       0.99) 为 true；普通 LENGTH（有 output 或明显低于 window）为 false。
 * </ol>
 */
public final class ContextPressureDetector {

  private static final List<String> RATE_LIMIT_MARKERS =
      List.of("429", "rate limit", "too many requests", "throttl");

  private static final List<String> EXPLICIT_CONTEXT_CODES =
      List.of(
          "context_length_exceeded",
          "request_too_large",
          "model_context_window_exceeded",
          "context_window_exceeded");

  private static final List<String> PROVIDER_PATTERNS =
      List.of(
          // Anthropic
          "prompt is too long",
          "request_too_large",
          // OpenAI / OpenAI-compatible / OpenRouter
          "your input exceeds the context window",
          "context_length_exceeded",
          "maximum context length",
          "exceeds model's maximum context length",
          "requested token count exceeds",
          // Google
          "input token count",
          "exceeds the maximum",
          // xAI / Groq / llama.cpp / LM Studio
          "maximum prompt length",
          "reduce the length of the messages or completion",
          "available context size",
          "tokens to keep",
          "greater than context length",
          // GitHub Copilot / MiniMax / Kimi
          "prompt token count exceeds",
          "context window exceeds",
          "exceeded model token limit");

  private ContextPressureDetector() {}

  /** 返回事实是否体现 context pressure；见类注释中的判定顺序。 */
  public static boolean detect(ContextPressureFacts facts) {
    Objects.requireNonNull(facts, "facts");
    if (isRateLimited(facts)) {
      return false;
    }
    if (isHttp413(facts)) {
      return true;
    }
    String code = lower(facts.providerErrorCodeOrType());
    String message = lower(facts.errorMessage());
    if (containsAny(code, EXPLICIT_CONTEXT_CODES) || containsAny(message, EXPLICIT_CONTEXT_CODES)) {
      return true;
    }
    if (containsAny(message, PROVIDER_PATTERNS)) {
      return true;
    }
    return reachedWindow(facts) || strictLengthNearWindow(facts);
  }

  private static boolean isRateLimited(ContextPressureFacts facts) {
    if (facts.httpStatus() != null && facts.httpStatus() == 429) {
      return true;
    }
    return containsAny(lower(facts.errorMessage()), RATE_LIMIT_MARKERS);
  }

  private static boolean isHttp413(ContextPressureFacts facts) {
    if (facts.httpStatus() != null && facts.httpStatus() == 413) {
      return true;
    }
    String message = lower(facts.errorMessage());
    return message != null && message.contains("413");
  }

  private static boolean reachedWindow(ContextPressureFacts facts) {
    ModelUsage usage = facts.usage();
    Long contextWindow = facts.contextWindow();
    if (usage == null || contextWindow == null || contextWindow <= 0) {
      return false;
    }
    return promptTokens(usage) >= contextWindow;
  }

  private static boolean strictLengthNearWindow(ContextPressureFacts facts) {
    if (facts.stopReason() != GenerationStopReason.LENGTH) {
      return false;
    }
    ModelUsage usage = facts.usage();
    Long contextWindow = facts.contextWindow();
    if (usage == null || contextWindow == null || contextWindow <= 0) {
      return false;
    }
    if (usage.outputTokens() != 0) {
      return false;
    }
    long threshold = (long) Math.ceil(contextWindow * 0.99d);
    return promptTokens(usage) >= threshold;
  }

  private static long promptTokens(ModelUsage usage) {
    long input = usage.inputTokens();
    long cache = usage.cacheReadTokens();
    return cache > Long.MAX_VALUE - input ? Long.MAX_VALUE : input + cache;
  }

  private static boolean containsAny(String haystack, List<String> needles) {
    if (haystack == null) {
      return false;
    }
    for (String needle : needles) {
      if (haystack.contains(needle)) {
        return true;
      }
    }
    return false;
  }

  private static String lower(String value) {
    return value == null ? null : value.toLowerCase(Locale.ROOT);
  }
}
