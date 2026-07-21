package fun.fengwk.kkstudio.harness.model;

import java.util.List;
import java.util.Locale;

/**
 * 模型的一组可命名请求参数预设（variant）。
 *
 * <p>采样字段与 {@code reasoningEffort} 均为可选；{@code null} 表示不传，走厂商默认。{@code reasoningEffort} 为 {@code
 * off} 或空白时归一为 {@code null}。
 */
public record ModelVariant(
    String id,
    Integer maxOutputTokens,
    Double temperature,
    Double topP,
    Integer topK,
    Double frequencyPenalty,
    Double presencePenalty,
    List<String> stopSequences,
    String reasoningEffort) {

  public ModelVariant {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("id must not be blank");
    }
    if (!id.equals(id.trim())) {
      throw new IllegalArgumentException("id must not have surrounding whitespace");
    }
    if (maxOutputTokens != null && maxOutputTokens <= 0) {
      throw new IllegalArgumentException("maxOutputTokens must be positive");
    }
    if (temperature != null) {
      requireFinite(temperature, "temperature");
      if (temperature < 0) {
        throw new IllegalArgumentException("temperature must not be negative");
      }
    }
    if (topP != null) {
      requireFinite(topP, "topP");
      if (topP <= 0 || topP > 1) {
        throw new IllegalArgumentException("topP must be in (0, 1]");
      }
    }
    if (topK != null && topK <= 0) {
      throw new IllegalArgumentException("topK must be positive");
    }
    if (frequencyPenalty != null) {
      requireFinite(frequencyPenalty, "frequencyPenalty");
    }
    if (presencePenalty != null) {
      requireFinite(presencePenalty, "presencePenalty");
    }
    stopSequences = stopSequences == null ? List.of() : List.copyOf(stopSequences);
    for (String stopSequence : stopSequences) {
      if (stopSequence == null || stopSequence.isBlank()) {
        throw new IllegalArgumentException("stopSequences must contain non-blank strings");
      }
    }
    if (reasoningEffort != null) {
      String normalized = reasoningEffort.trim().toLowerCase(Locale.ROOT);
      if (normalized.isEmpty() || "off".equals(normalized)) {
        reasoningEffort = null;
      } else {
        reasoningEffort = normalized;
      }
    }
  }

  private static void requireFinite(Double value, String name) {
    if (!Double.isFinite(value)) {
      throw new IllegalArgumentException(name + " must be finite");
    }
  }
}
