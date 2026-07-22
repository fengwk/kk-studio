package fun.fengwk.kkstudio.core.agent.model.runtime;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.model.repo.impl.mapper.AgentModelMapper;
import fun.fengwk.kkstudio.core.agent.model.repo.impl.model.AgentModelDO;
import fun.fengwk.kkstudio.share.model.AgentModelConfigDTO;

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

  public String resolve(long modelId, String overrideVariant) {
    if (modelId <= 0) {
      throw new IllegalArgumentException("modelId must be positive");
    }
    AgentModelDO model = agentModelMapper.getById(modelId);
    if (model == null) {
      throw new IllegalArgumentException("unknown agent model: " + modelId);
    }
    AgentModelConfigDTO config = configParser.decode(model.getConfigJson());
    String variant = trimToNull(overrideVariant);
    if (variant == null) {
      variant = config.getDefaultVariant();
    }
    if (variant == null || variant.isBlank()) {
      throw new IllegalStateException("model defaultVariant missing: " + modelId);
    }
    String effectiveVariant = variant.trim();
    boolean declared =
        config.getVariants().stream()
            .anyMatch(candidate -> effectiveVariant.equals(candidate.getId()));
    if (!declared) {
      throw new IllegalArgumentException(
          "unknown agent model variant: modelId=" + modelId + ", variant=" + effectiveVariant);
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
