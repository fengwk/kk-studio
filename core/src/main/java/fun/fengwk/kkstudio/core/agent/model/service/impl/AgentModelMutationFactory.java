package fun.fengwk.kkstudio.core.agent.model.service.impl;

import static fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator.nextModelId;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.share.model.AgentModelEditablePropertiesDTO;

/**
 * @author fengwk
 */
@Component
final class AgentModelMutationFactory {

  private static final String EMPTY_ARRAY_JSON = "[]";
  private static final String EMPTY_OBJECT_JSON = "{}";

  private final AgentEditableSupport editableSupport;

  AgentModelMutationFactory(AgentEditableSupport editableSupport) {
    this.editableSupport = editableSupport;
  }

  AgentModel newModel(long workspaceId, long providerId, AgentModelEditablePropertiesDTO properties) {
    if (workspaceId <= 0 || providerId <= 0) {
      throw new IllegalArgumentException("workspaceId and providerId must be positive");
    }
    Mutation mutation = newMutation(properties, null, true);
    AgentModel model = new AgentModel();
    model.setId(nextModelId());
    model.setWorkspaceId(workspaceId);
    model.setProviderId(providerId);
    apply(model, mutation);
    return model;
  }

  void update(AgentModel model, AgentModelEditablePropertiesDTO properties) {
    apply(model, newMutation(properties, model.getName(), false));
  }

  private void apply(AgentModel model, Mutation mutation) {
    model.setName(mutation.name());
    model.setDescription(mutation.description());
    model.setCapabilitiesJson(mutation.capabilitiesJson());
    model.setConfigJson(mutation.configJson());
  }

  private Mutation newMutation(
      AgentModelEditablePropertiesDTO properties, String fallbackName, boolean creating) {
    if (properties == null) {
      throw new IllegalArgumentException("agent model body must not be null");
    }
    String name = editableSupport.firstNonBlank(properties.getName(), fallbackName);
    if (name == null) {
      throw new IllegalArgumentException("agent model name must not be blank");
    }
    String capabilitiesJson = editableSupport.firstNonBlank(properties.getCapabilitiesJson(), EMPTY_ARRAY_JSON);
    String configJson = editableSupport.firstNonBlank(properties.getConfigJson(), EMPTY_OBJECT_JSON);
    editableSupport.validateJsonArray(capabilitiesJson, "capabilitiesJson");
    editableSupport.validateJsonObject(configJson, "configJson");
    return new Mutation(
        name,
        editableSupport.trimToNull(properties.getDescription()),
        capabilitiesJson,
        configJson);
  }

  record Mutation(String name, String description, String capabilitiesJson, String configJson) {}
}
