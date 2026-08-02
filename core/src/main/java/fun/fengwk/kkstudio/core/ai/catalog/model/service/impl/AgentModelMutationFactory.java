package fun.fengwk.kkstudio.core.ai.catalog.model.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.core.ai.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.ai.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelEditablePropertiesDTO;

/** Normalizes mutable model configuration through the shared typed config codec. */
@Component
final class AgentModelMutationFactory {

  private static final String RESOURCE = "agent_model";
  private static final int NAME_MAX_LENGTH = 128;
  private static final int DESCRIPTION_MAX_LENGTH = 512;

  private final AgentEditableSupport editableSupport;
  private final AgentModelRuntimeConfigParser runtimeConfigParser;

  AgentModelMutationFactory(
      AgentEditableSupport editableSupport, AgentModelRuntimeConfigParser runtimeConfigParser) {
    this.editableSupport = editableSupport;
    this.runtimeConfigParser = runtimeConfigParser;
  }

  AgentModel newModel(
      String providerName, String name, AgentModelEditablePropertiesDTO properties) {
    if (providerName == null || providerName.isBlank()) {
      throw new AiValidationException(RESOURCE, "providerName must not be blank");
    }
    Mutation mutation = newMutation(name, properties);
    AgentModel model = new AgentModel();
    model.setProviderName(providerName);
    apply(model, mutation);
    return model;
  }

  void update(AgentModel model, AgentModelEditablePropertiesDTO properties) {
    apply(model, newMutation(model.getName(), properties));
  }

  private void apply(AgentModel model, Mutation mutation) {
    model.setName(mutation.name());
    model.setDescription(mutation.description());
    model.setConfigJson(mutation.configJson());
  }

  private Mutation newMutation(String name, AgentModelEditablePropertiesDTO properties) {
    if (properties == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " body must not be null");
    }
    if (name == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " name must not be blank");
    }
    String normalizedName = name.strip();
    if (normalizedName.isEmpty()) {
      throw new AiValidationException(RESOURCE, RESOURCE + " name must not be blank");
    }
    if (!name.equals(normalizedName)) {
      throw new AiValidationException(
          RESOURCE, RESOURCE + " name must not contain surrounding whitespace");
    }
    String description = editableSupport.trimToNull(properties.getDescription());
    editableSupport.validateMaxLength(RESOURCE, "name", normalizedName, NAME_MAX_LENGTH);
    editableSupport.validateMaxLength(RESOURCE, "description", description, DESCRIPTION_MAX_LENGTH);
    AgentModelConfigDTO config = properties.getConfig();
    if (config == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " config must not be null");
    }
    String configJson;
    try {
      configJson = runtimeConfigParser.encode(config);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, error.getMessage(), error);
    }
    return new Mutation(normalizedName, description, configJson);
  }

  record Mutation(String name, String description, String configJson) {}
}
