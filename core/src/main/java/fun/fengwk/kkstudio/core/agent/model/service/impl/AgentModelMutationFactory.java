package fun.fengwk.kkstudio.core.agent.model.service.impl;

import static fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator.nextModelId;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.share.model.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.model.AgentModelEditablePropertiesDTO;

/** Normalizes mutable model configuration through the shared typed config codec. */
@Component
final class AgentModelMutationFactory {

  private final AgentModelRuntimeConfigParser runtimeConfigParser;

  AgentModelMutationFactory(AgentModelRuntimeConfigParser runtimeConfigParser) {
    this.runtimeConfigParser = runtimeConfigParser;
  }

  AgentModel newModel(long providerId, AgentModelEditablePropertiesDTO properties) {
    if (providerId <= 0) {
      throw new IllegalArgumentException("providerId must be positive");
    }
    Mutation mutation = newMutation(properties);
    AgentModel model = new AgentModel();
    model.setId(nextModelId());
    model.setProviderId(providerId);
    apply(model, mutation);
    return model;
  }

  void update(AgentModel model, AgentModelEditablePropertiesDTO properties) {
    apply(model, newMutation(properties));
  }

  private void apply(AgentModel model, Mutation mutation) {
    model.setName(mutation.name());
    model.setDescription(mutation.description());
    model.setConfigJson(mutation.configJson());
  }

  private Mutation newMutation(AgentModelEditablePropertiesDTO properties) {
    if (properties == null) {
      throw new IllegalArgumentException("agent model body must not be null");
    }
    String name = trimToNull(properties.getName());
    if (name == null) {
      throw new IllegalArgumentException("agent model name must not be blank");
    }
    AgentModelConfigDTO config = properties.getConfig();
    if (config == null) {
      throw new IllegalArgumentException("agent model config must not be null");
    }
    String configJson = runtimeConfigParser.encode(config);
    return new Mutation(name, trimToNull(properties.getDescription()), configJson);
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  record Mutation(String name, String description, String configJson) {}
}
