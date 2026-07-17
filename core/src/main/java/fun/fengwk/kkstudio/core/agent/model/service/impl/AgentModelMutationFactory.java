package fun.fengwk.kkstudio.core.agent.model.service.impl;

import static fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator.nextModelId;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.share.model.AgentModelEditablePropertiesDTO;

/** Normalizes mutable model configuration. */
@Component
final class AgentModelMutationFactory {

  private final AgentEditableSupport editableSupport;
  private final AgentModelRuntimeConfigParser runtimeConfigParser;

  AgentModelMutationFactory(
      AgentEditableSupport editableSupport, AgentModelRuntimeConfigParser runtimeConfigParser) {
    this.editableSupport = editableSupport;
    this.runtimeConfigParser = runtimeConfigParser;
  }

  AgentModel newModel(long providerId, AgentModelEditablePropertiesDTO properties) {
    if (providerId <= 0) {
      throw new IllegalArgumentException("providerId must be positive");
    }
    Mutation mutation = newMutation(properties, null, null, null);
    AgentModel model = new AgentModel();
    model.setId(nextModelId());
    model.setProviderId(providerId);
    apply(model, mutation);
    return model;
  }

  void update(AgentModel model, AgentModelEditablePropertiesDTO properties) {
    apply(
        model,
        newMutation(
            properties, model.getName(), model.getCapabilitiesJson(), model.getConfigJson()));
  }

  private void apply(AgentModel model, Mutation mutation) {
    model.setName(mutation.name());
    model.setDescription(mutation.description());
    model.setCapabilitiesJson(mutation.capabilitiesJson());
    model.setConfigJson(mutation.configJson());
  }

  private Mutation newMutation(
      AgentModelEditablePropertiesDTO properties,
      String fallbackName,
      String fallbackCapabilitiesJson,
      String fallbackConfigJson) {
    if (properties == null) {
      throw new IllegalArgumentException("agent model body must not be null");
    }
    String name = editableSupport.firstNonBlank(properties.getName(), fallbackName);
    if (name == null) {
      throw new IllegalArgumentException("agent model name must not be blank");
    }
    String capabilitiesJson =
        editableSupport.firstNonBlank(properties.getCapabilitiesJson(), fallbackCapabilitiesJson);
    if (capabilitiesJson == null) {
      throw new IllegalArgumentException("agent model capabilitiesJson must not be blank");
    }
    String configJson =
        editableSupport.firstNonBlank(properties.getConfigJson(), fallbackConfigJson);
    if (configJson == null) {
      throw new IllegalArgumentException("agent model configJson must not be blank");
    }
    runtimeConfigParser.parse(capabilitiesJson, configJson);
    return new Mutation(
        name,
        editableSupport.trimToNull(properties.getDescription()),
        capabilitiesJson,
        configJson);
  }

  record Mutation(String name, String description, String capabilitiesJson, String configJson) {}
}
