package fun.fengwk.kkstudio.core.agent.model.service.impl;

import static fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator.nextModelId;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.share.model.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.model.AgentModelEditablePropertiesDTO;

/** Normalizes mutable model configuration. */
@Component
final class AgentModelMutationFactory {

  private final AgentEditableSupport editableSupport;
  private final AgentModelRuntimeConfigParser runtimeConfigParser;
  private final ObjectMapper objectMapper;

  AgentModelMutationFactory(
      AgentEditableSupport editableSupport,
      AgentModelRuntimeConfigParser runtimeConfigParser,
      ObjectMapper objectMapper) {
    this.editableSupport = editableSupport;
    this.runtimeConfigParser = runtimeConfigParser;
    this.objectMapper = objectMapper;
  }

  AgentModel newModel(long providerId, AgentModelEditablePropertiesDTO properties) {
    if (providerId <= 0) {
      throw new IllegalArgumentException("providerId must be positive");
    }
    Mutation mutation = newMutation(properties, null, null);
    AgentModel model = new AgentModel();
    model.setId(nextModelId());
    model.setProviderId(providerId);
    apply(model, mutation);
    return model;
  }

  void update(AgentModel model, AgentModelEditablePropertiesDTO properties) {
    apply(model, newMutation(properties, model.getName(), model.getConfigJson()));
  }

  private void apply(AgentModel model, Mutation mutation) {
    model.setName(mutation.name());
    model.setDescription(mutation.description());
    model.setConfigJson(mutation.configJson());
  }

  private Mutation newMutation(
      AgentModelEditablePropertiesDTO properties, String fallbackName, String fallbackConfigJson) {
    if (properties == null) {
      throw new IllegalArgumentException("agent model body must not be null");
    }
    String name = editableSupport.firstNonBlank(properties.getName(), fallbackName);
    if (name == null) {
      throw new IllegalArgumentException("agent model name must not be blank");
    }
    AgentModelConfigDTO config =
        editableSupport.firstNonNull(properties.getConfig(), previousConfig(fallbackConfigJson));
    if (config == null) {
      throw new IllegalArgumentException("agent model config must not be null");
    }
    String configJson = encode(config);
    runtimeConfigParser.parse(configJson);
    return new Mutation(name, editableSupport.trimToNull(properties.getDescription()), configJson);
  }

  private AgentModelConfigDTO previousConfig(String configJson) {
    if (configJson == null || configJson.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(configJson, AgentModelConfigDTO.class);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("persisted config_json is invalid", error);
    }
  }

  private String encode(AgentModelConfigDTO config) {
    try {
      return objectMapper.writeValueAsString(config);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("cannot serialize agent model config", error);
    }
  }

  record Mutation(String name, String description, String configJson) {}
}
