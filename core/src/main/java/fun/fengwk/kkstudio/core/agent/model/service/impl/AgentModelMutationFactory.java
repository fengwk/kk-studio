package fun.fengwk.kkstudio.core.agent.model.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.persistence.id.PostgresqlSequenceIdGenerator;
import fun.fengwk.kkstudio.share.model.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.model.AgentModelEditablePropertiesDTO;

/** Normalizes mutable model configuration through the shared typed config codec. */
@Component
final class AgentModelMutationFactory {

  private static final String RESOURCE = "agent_model";
  private static final int NAME_MAX_LENGTH = 128;
  private static final int DESCRIPTION_MAX_LENGTH = 512;

  private final AgentEditableSupport editableSupport;
  private final AgentModelRuntimeConfigParser runtimeConfigParser;
  private final PostgresqlSequenceIdGenerator idGenerator;

  AgentModelMutationFactory(
      AgentEditableSupport editableSupport,
      AgentModelRuntimeConfigParser runtimeConfigParser,
      PostgresqlSequenceIdGenerator idGenerator) {
    this.editableSupport = editableSupport;
    this.runtimeConfigParser = runtimeConfigParser;
    this.idGenerator = idGenerator;
  }

  AgentModel newModel(long providerId, AgentModelEditablePropertiesDTO properties) {
    if (providerId <= 0) {
      throw new AiValidationException(RESOURCE, "providerId must be positive");
    }
    Mutation mutation = newMutation(properties);
    AgentModel model = new AgentModel();
    model.setId(idGenerator.next());
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
      throw new AiValidationException(RESOURCE, RESOURCE + " body must not be null");
    }
    String name = editableSupport.trimToNull(properties.getName());
    if (name == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " name must not be blank");
    }
    String description = editableSupport.trimToNull(properties.getDescription());
    editableSupport.validateMaxLength(RESOURCE, "name", name, NAME_MAX_LENGTH);
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
    return new Mutation(name, description, configJson);
  }

  record Mutation(String name, String description, String configJson) {}
}
