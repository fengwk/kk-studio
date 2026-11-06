package fun.fengwk.kkstudio.platform.catalog.model;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.catalog.dto.ModelConfigDTO;

import java.util.Objects;

/** 解析 Agent 或 Thread 的生效单 Model Variant；模型组没有组级 Variant。 */
@Component
public class ModelDefaultVariantResolver {

  private final ModelRepository agentModelRepository;
  private final ModelRuntimeConfigParser configParser;

  public ModelDefaultVariantResolver(
      ModelRepository agentModelRepository, ModelRuntimeConfigParser configParser) {
    this.agentModelRepository =
        Objects.requireNonNull(agentModelRepository, "agentModelRepository");
    this.configParser = Objects.requireNonNull(configParser, "configParser");
  }

  public String resolve(String modelName, String overrideVariant) {
    if (modelName == null || modelName.isBlank()) {
      throw new IllegalArgumentException("modelName must not be blank");
    }
    Model model = agentModelRepository.getByName(modelName);
    if (model == null) {
      throw new IllegalArgumentException("unknown agent model: " + modelName);
    }
    if (model.kind() != ModelKind.MODEL) {
      throw new IllegalArgumentException("model group has no variant: " + modelName);
    }
    ModelConfigDTO config = configParser.decode(model.configJson());
    return resolveConfiguredVariant(modelName, overrideVariant, config);
  }

  /**
   * 纯 variant 解析：空 override 使用 {@code defaultVariant}，非空 override 与 defaultVariant 都必须由 {@code
   * config} 声明。
   */
  public static String resolveConfiguredVariant(
      String modelName, String overrideVariant, ModelConfigDTO config) {
    Objects.requireNonNull(config, "config");
    String variant = trimToNull(overrideVariant);
    if (variant == null) {
      variant = config.getDefaultVariant();
    }
    if (variant == null || variant.isBlank()) {
      throw new IllegalStateException("model defaultVariant missing: " + modelName);
    }
    String effectiveVariant = variant.trim();
    boolean declared =
        config.getVariants().stream()
            .anyMatch(candidate -> effectiveVariant.equals(candidate.getId()));
    if (!declared) {
      throw new IllegalArgumentException(
          "unknown agent model variant: model=" + modelName + ", variant=" + effectiveVariant);
    }
    return effectiveVariant;
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
