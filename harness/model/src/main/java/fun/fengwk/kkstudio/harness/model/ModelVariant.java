package fun.fengwk.kkstudio.harness.model;

import java.util.List;
import java.util.Locale;

/**
 * 模型的一组可命名请求参数预设（variant）。
 *
 * <p>{@code name} 对应 config 中的 variant {@code id}。采样字段与 {@code reasoningEffort} 均为可选；{@code null}
 * 表示不传，走厂商默认。{@code reasoningEffort} 为 {@code off} 或空白时归一为 {@code null}。
 */
public record ModelVariant(
    String name,
    Integer maxOutputTokens,
    Double temperature,
    Double topP,
    Integer topK,
    Double frequencyPenalty,
    Double presencePenalty,
    List<String> stopSequences,
    String reasoningEffort) {

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
    if (reasoningEffort != null) {
      String normalized = reasoningEffort.trim().toLowerCase(Locale.ROOT);
      if (normalized.isEmpty() || "off".equals(normalized)) {
        reasoningEffort = null;
      } else {
        reasoningEffort = normalized;
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

  /** 兼容 8 参数构造（无 reasoningEffort）。 */
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
