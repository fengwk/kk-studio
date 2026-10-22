package fun.fengwk.kkstudio.harness.runtime.entry;

import java.util.Objects;

/** 冻结到 branch settings snapshot 中的统一逻辑模型选择：全局名称 + 可选单模型 Variant。 */
public record ModelSelection(String modelName, String variant) {

  public ModelSelection {
    modelName = requireCanonicalName(modelName, "modelName");
    if (variant != null) {
      variant = requireCanonicalName(variant, "variant");
    }
  }

  /** 纯单模型选择便利构造：显式 variant。 */
  public static ModelSelection model(String modelName, String variant) {
    return new ModelSelection(modelName, variant);
  }

  /** 模型组没有组级 Variant。 */
  public static ModelSelection group(String modelName) {
    return new ModelSelection(modelName, null);
  }

  private static String requireCanonicalName(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must not contain surrounding whitespace");
    }
    if (value.length() > 128) {
      throw new IllegalArgumentException(field + " must be <= 128 characters");
    }
    return value;
  }
}
