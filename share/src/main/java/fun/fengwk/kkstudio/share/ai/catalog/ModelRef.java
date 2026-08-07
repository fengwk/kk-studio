package fun.fengwk.kkstudio.share.ai.catalog;

import java.util.Objects;

/**
 * Agent 模型的 canonical 外部身份。
 *
 * <p>序列化形式为 {@code providerName/modelName}。解析时只在第一个斜杠处分隔，因此 provider 模型名本身可以包含额外的斜杠。
 */
public record ModelRef(
    /** Provider 资源名：非空白、无环绕空白且不得包含 {@code '/'}。 */
    String providerName,
    /** 模型名：非空白且无环绕空白。 */
    String modelName) {

  public ModelRef {
    providerName = requireCanonicalPart(providerName, "providerName");
    if (providerName.indexOf('/') >= 0) {
      throw new IllegalArgumentException("providerName must not contain '/'");
    }
    modelName = requireCanonicalPart(modelName, "modelName");
  }

  public static ModelRef parse(String value) {
    if (value == null) {
      throw new IllegalArgumentException("model must not be null");
    }
    int separator = value.indexOf('/');
    if (separator < 0) {
      throw new IllegalArgumentException("model must contain providerName/modelName");
    }
    return new ModelRef(value.substring(0, separator), value.substring(separator + 1));
  }

  @Override
  public String toString() {
    return providerName + "/" + modelName;
  }

  private static String requireCanonicalPart(String value, String field) {
    Objects.requireNonNull(field, "field");
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must not contain surrounding whitespace");
    }
    return value;
  }
}
