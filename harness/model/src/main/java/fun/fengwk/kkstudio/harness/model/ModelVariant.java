package fun.fengwk.kkstudio.harness.model;

import java.util.List;

/** 模型的一组可命名请求参数。 */
public record ModelVariant(
    String name,
    Integer maxOutputTokens,
    Double temperature,
    Double topP,
    Integer topK,
    List<String> stopSequences) {

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
  }
}
