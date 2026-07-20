package fun.fengwk.kkstudio.harness.model;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 模型的一组可命名请求参数（thinking profile / sampling profile）。 */
public record ModelVariant(
    String name,
    Integer maxOutputTokens,
    Double temperature,
    Double topP,
    Integer topK,
    Double frequencyPenalty,
    Double presencePenalty,
    List<String> stopSequences,
    String thinkingLevel) {

  private static final Set<String> THINKING_LEVELS =
      Set.of("off", "minimal", "low", "medium", "high", "xhigh", "max");

  public ModelVariant {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    if (maxOutputTokens != null && maxOutputTokens <= 0) {
      throw new IllegalArgumentException("maxOutputTokens must be positive");
    }
    if (temperature != null && temperature < 0) {
      throw new IllegalArgumentException("temperature must not be negative");
    }
    if (topP != null && (topP <= 0 || topP > 1)) {
      throw new IllegalArgumentException("topP must be in (0, 1]");
    }
    if (topK != null && topK <= 0) {
      throw new IllegalArgumentException("topK must be positive");
    }
    stopSequences = stopSequences == null ? List.of() : List.copyOf(stopSequences);
    if (thinkingLevel != null) {
      String normalized = thinkingLevel.trim().toLowerCase(Locale.ROOT);
      if (normalized.isEmpty()) {
        thinkingLevel = null;
      } else if (!THINKING_LEVELS.contains(normalized)) {
        throw new IllegalArgumentException("unsupported thinkingLevel: " + thinkingLevel);
      } else {
        thinkingLevel = normalized;
      }
    }
  }

  /** 保持早期 Model 契约调用方仅声明原有六项参数时的构造方式。 */
  public ModelVariant(
      String name,
      Integer maxOutputTokens,
      Double temperature,
      Double topP,
      Integer topK,
      List<String> stopSequences) {
    this(name, maxOutputTokens, temperature, topP, topK, null, null, stopSequences, null);
  }

  /** 兼容 8 参数构造（无 thinkingLevel）。 */
  public ModelVariant(
      String name,
      Integer maxOutputTokens,
      Double temperature,
      Double topP,
      Integer topK,
      Double frequencyPenalty,
      Double presencePenalty,
      List<String> stopSequences) {
    this(
        name,
        maxOutputTokens,
        temperature,
        topP,
        topK,
        frequencyPenalty,
        presencePenalty,
        stopSequences,
        null);
  }
}
