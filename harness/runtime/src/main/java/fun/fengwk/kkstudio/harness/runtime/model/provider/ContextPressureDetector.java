package fun.fengwk.kkstudio.harness.runtime.model.provider;

import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Context-pressure 纯检测器。
 *
 * <p>对 {@link ContextPressureFacts} 返回 boolean，不引入任何 durable 状态/status。判定顺序（一旦命中即返回）：
 *
 * <ol>
 *   <li>rate limit / throttling（HTTP 429、消息 word-boundary {@code 429} / {@code rate limit} / {@code
 *       too many requests} / {@code throttl}）永远为 false，即使消息同时含 context 字样；CANCELLED 不在本检测器表达，由
 *       调用方（adapter classify）先行排除。
 *   <li>HTTP 413（typed {@code httpStatus} 或消息 word-boundary {@code 413}）为 true。
 *   <li>显式 context code/type（如 {@code context_length_exceeded}、{@code request_too_large}、{@code
 *       model_context_window_exceeded}、{@code context_window_exceeded}）为 true；code 是跨厂商权威标记，在所有
 *       {@link ProviderType} 共享；普通 400 无 context code 不得误判。
 *   <li>按 {@link ProviderType} 分族的 Provider 特有语义 pattern 为 true：每个 pattern 是必须同时命中的 compound
 *       markers（如 Google 需 {@code input token count} + {@code exceeds} + {@code
 *       maximum}），宽泛单一子串不单独触发； OPENAI/OPENAI_RESPONSES（含 OpenAI-compatible/MiniMax/Kimi）归同一族。
 *   <li>权威 usage 的 prompt tokens（input + cacheRead）达到/超过 contextWindow 为 true。
 *   <li>严格 LENGTH：stopReason 为 LENGTH 且 outputTokens == 0 且 prompt tokens ≥ ceil(contextWindow *
 *       0.99) 为 true；普通 LENGTH（有 output 或明显低于 window）为 false。
 * </ol>
 */
public final class ContextPressureDetector {

  /** HTTP status 数字只在独立 token 上匹配，避免把 token 计数（如 {@code 142900}）误判为 status。 */
  private static final Pattern HTTP_STATUS_413 = Pattern.compile("\\b413\\b");

  private static final Pattern HTTP_STATUS_429 = Pattern.compile("\\b429\\b");

  private static final List<String> RATE_LIMIT_MESSAGE_MARKERS =
      List.of("rate limit", "too many requests", "throttl");

  /** 跨厂商权威 context code/type，共享于所有 {@link ProviderType}。 */
  private static final List<String> EXPLICIT_CONTEXT_CODES =
      List.of(
          "context_length_exceeded",
          "request_too_large",
          "model_context_window_exceeded",
          "context_window_exceeded");

  /**
   * Provider message pattern 的厂商族；OPENAI/OPENAI_RESPONSES（含 OpenAI-compatible/MiniMax/Kimi）归同一族。
   */
  private enum PatternFamily {
    ANTHROPIC,
    GOOGLE,
    OPENAI
  }

  /** 必须同时命中全部 {@code markers} 的语义组合 pattern；任一 marker 单独出现不触发。 */
  private record MessagePattern(PatternFamily family, List<String> markers) {

    private boolean matches(String message) {
      for (String marker : markers) {
        if (!message.contains(marker)) {
          return false;
        }
      }
      return true;
    }
  }

  private static final List<MessagePattern> PROVIDER_PATTERNS =
      List.of(
          // Anthropic
          new MessagePattern(PatternFamily.ANTHROPIC, List.of("prompt is too long")),
          // Google
          new MessagePattern(
              PatternFamily.GOOGLE, List.of("input token count", "exceeds", "maximum")),
          // OpenAI / OpenAI-compatible / OpenRouter
          new MessagePattern(
              PatternFamily.OPENAI, List.of("your input exceeds the context window")),
          new MessagePattern(PatternFamily.OPENAI, List.of("maximum context length")),
          new MessagePattern(
              PatternFamily.OPENAI, List.of("exceeds model's maximum context length")),
          new MessagePattern(PatternFamily.OPENAI, List.of("requested token count", "exceeds")),
          // xAI / Groq / llama.cpp / LM Studio / GitHub Copilot / MiniMax / Kimi
          new MessagePattern(PatternFamily.OPENAI, List.of("maximum prompt length")),
          new MessagePattern(
              PatternFamily.OPENAI, List.of("reduce the length of the messages", "completion")),
          new MessagePattern(PatternFamily.OPENAI, List.of("available context size")),
          new MessagePattern(
              PatternFamily.OPENAI, List.of("tokens to keep", "greater than", "context length")),
          new MessagePattern(PatternFamily.OPENAI, List.of("prompt token count", "exceeds")),
          new MessagePattern(PatternFamily.OPENAI, List.of("context window exceeds")),
          new MessagePattern(PatternFamily.OPENAI, List.of("exceeded model token limit")));

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
    if (matchesMessagePatterns(facts.providerType(), message)) {
      return true;
    }
    return reachedWindow(facts) || strictLengthNearWindow(facts);
  }

  /**
   * 只基于成功 terminal response 的 generation/usage/window 判断 silent context wall。
   *
   * <p>该路径没有 Provider 错误文本，因此不需要伪造 {@link ProviderType}；仅执行 authoritative usage 与严格 LENGTH 两条规则。
   */
  public static boolean detectResponse(
      GenerationStopReason stopReason, ModelUsage usage, long contextWindow) {
    if (contextWindow <= 0) {
      throw new IllegalArgumentException("contextWindow must be positive");
    }
    Objects.requireNonNull(usage, "usage");
    return promptTokens(usage) >= contextWindow
        || (stopReason == GenerationStopReason.LENGTH
            && usage.outputTokens() == 0
            && promptTokens(usage) >= (long) Math.ceil(contextWindow * 0.99d));
  }

  /** rate limit / throttling 永不为 context pressure；adapter classify 与 future ThreadProcessor 共用。 */
  public static boolean isRateLimited(ContextPressureFacts facts) {
    Objects.requireNonNull(facts, "facts");
    if (facts.httpStatus() != null && facts.httpStatus() == 429) {
      return true;
    }
    String message = lower(facts.errorMessage());
    if (message != null && HTTP_STATUS_429.matcher(message).find()) {
      return true;
    }
    return containsAny(message, RATE_LIMIT_MESSAGE_MARKERS);
  }

  private static boolean isHttp413(ContextPressureFacts facts) {
    if (facts.httpStatus() != null && facts.httpStatus() == 413) {
      return true;
    }
    String message = lower(facts.errorMessage());
    return message != null && HTTP_STATUS_413.matcher(message).find();
  }

  private static boolean matchesMessagePatterns(ProviderType providerType, String message) {
    if (message == null) {
      return false;
    }
    PatternFamily family = familyOf(providerType);
    for (MessagePattern pattern : PROVIDER_PATTERNS) {
      if (pattern.family() == family && pattern.matches(message)) {
        return true;
      }
    }
    return false;
  }

  private static PatternFamily familyOf(ProviderType providerType) {
    return switch (providerType) {
      case ANTHROPIC -> PatternFamily.ANTHROPIC;
      case GOOGLE -> PatternFamily.GOOGLE;
      case OPENAI, OPENAI_RESPONSES -> PatternFamily.OPENAI;
    };
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
