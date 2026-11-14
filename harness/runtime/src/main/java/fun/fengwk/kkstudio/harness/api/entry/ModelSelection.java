package fun.fengwk.kkstudio.harness.api.entry;

/**
 * 统一逻辑模型身份：全局名称 + 可选单模型 Variant。
 *
 * <p>用于 AssistantMessageMetadata、MODEL_ATTEMPT_FAILURE 与 Builtin 执行设置；BranchSettings 经
 * ExecutionSelection 携带 Driver 私有设置。
 */
public record ModelSelection(String modelName, String variant) {

  public ModelSelection {
    modelName = CanonicalNames.requireName(modelName, "modelName");
    if (variant != null) {
      variant = CanonicalNames.requireName(variant, "variant");
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
}
