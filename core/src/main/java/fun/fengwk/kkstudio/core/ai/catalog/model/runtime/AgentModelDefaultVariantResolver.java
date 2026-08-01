package fun.fengwk.kkstudio.core.ai.catalog.model.runtime;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.catalog.model.repo.impl.mapper.AgentModelMapper;
import fun.fengwk.kkstudio.core.ai.catalog.model.repo.impl.model.AgentModelDO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;

import java.util.Objects;

/**
 * Resolves the effective model variant for an Agent or Thread.
 *
 * <p>Blank override means "use the model's configured {@code defaultVariant}". Both the configured
 * default and a non-blank override must identify a Variant declared by the current Model config.
 */
@Component
public class AgentModelDefaultVariantResolver {

  private final AgentModelMapper agentModelMapper;
  private final AgentModelRuntimeConfigParser configParser;

  public AgentModelDefaultVariantResolver(
      AgentModelMapper agentModelMapper, AgentModelRuntimeConfigParser configParser) {
    this.agentModelMapper = Objects.requireNonNull(agentModelMapper, "agentModelMapper");
    this.configParser = Objects.requireNonNull(configParser, "configParser");
  }

  public String resolve(String providerName, String modelName, String overrideVariant) {
    if (providerName == null || providerName.isBlank()) {
      throw new IllegalArgumentException("providerName must not be blank");
    }
    if (modelName == null || modelName.isBlank()) {
      throw new IllegalArgumentException("modelName must not be blank");
    }
    AgentModelDO model = agentModelMapper.getByProviderNameAndName(providerName, modelName);
    if (model == null) {
      throw new IllegalArgumentException("unknown agent model: " + providerName + "/" + modelName);
    }
    AgentModelConfigDTO config = configParser.decode(model.getConfigJson());
    String variant = trimToNull(overrideVariant);
    if (variant == null) {
      variant = config.getDefaultVariant();
    }
    if (variant == null || variant.isBlank()) {
      throw new IllegalStateException(
          "model defaultVariant missing: " + providerName + "/" + modelName);
    }
    String effectiveVariant = variant.trim();
    boolean declared =
        config.getVariants().stream()
            .anyMatch(candidate -> effectiveVariant.equals(candidate.getId()));
    if (!declared) {
      throw new IllegalArgumentException(
          "unknown agent model variant: model="
              + providerName
              + "/"
              + modelName
              + ", variant="
              + effectiveVariant);
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
