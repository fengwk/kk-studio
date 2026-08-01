package fun.fengwk.kkstudio.share.ai.catalog;

import java.util.Objects;

/**
 * Canonical external identity of an Agent model.
 *
 * <p>The serialized form is {@code providerName/modelName}. Parsing splits at the first slash so a
 * provider model name may itself contain additional slashes.
 */
public record ModelRef(String providerName, String modelName) {

  public ModelRef {
    providerName = requireCanonicalPart(providerName, "providerName");
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
    if (!value.equals(value.trim())) {
      throw new IllegalArgumentException(field + " must not contain surrounding whitespace");
    }
    return value;
  }
}
