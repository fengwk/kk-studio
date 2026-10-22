package fun.fengwk.kkstudio.platform.catalog.model.runtime;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.catalog.model.repo.impl.model.AgentModelDO;
import fun.fengwk.kkstudio.platform.catalog.model.repo.impl.store.AgentModelJdbcStore;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;

import java.util.Objects;

/** 解析 Agent 或 Thread 的生效单 Model Variant；模型组没有组级 Variant。 */
@Component
public class AgentModelDefaultVariantResolver {

  private final AgentModelJdbcStore agentModelJdbcStore;
  private final AgentModelRuntimeConfigParser configParser;

  public AgentModelDefaultVariantResolver(
      AgentModelJdbcStore agentModelJdbcStore, AgentModelRuntimeConfigParser configParser) {
    this.agentModelJdbcStore = Objects.requireNonNull(agentModelJdbcStore, "agentModelJdbcStore");
    this.configParser = Objects.requireNonNull(configParser, "configParser");
  }

  public String resolve(String modelName, String overrideVariant) {
    if (modelName == null || modelName.isBlank()) {
      throw new IllegalArgumentException("modelName must not be blank");
    }
    AgentModelDO model = agentModelJdbcStore.getByName(modelName);
    if (model == null) {
      throw new IllegalArgumentException("unknown agent model: " + modelName);
    }
    if (!"MODEL".equals(model.getKind())) {
      throw new IllegalArgumentException("model group has no variant: " + modelName);
    }
    AgentModelConfigDTO config = configParser.decode(model.getConfigJson());
    return resolveConfiguredVariant(modelName, overrideVariant, config);
  }

  /**
   * 纯 variant 解析：空 override 使用 {@code defaultVariant}，非空 override 与 defaultVariant 都必须由 {@code
   * config} 声明。
   */
  public static String resolveConfiguredVariant(
      String modelName, String overrideVariant, AgentModelConfigDTO config) {
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
