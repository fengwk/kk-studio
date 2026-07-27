package fun.fengwk.kkstudio.core.agent.definition.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.core.persistence.id.PostgresqlSequenceIdGenerator;
import fun.fengwk.kkstudio.share.model.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionEditablePropertiesDTO;

/**
 * Normalizes editable agent fields and serializes strict structured execution configuration.
 *
 * @author fengwk
 */
@Component
final class AgentDefinitionMutationFactory {

  private final AgentEditableSupport editableSupport;
  private final AgentDefinitionConfigCodec configCodec;
  private final PostgresqlSequenceIdGenerator idGenerator;

  AgentDefinitionMutationFactory(
      AgentEditableSupport editableSupport,
      AgentDefinitionConfigCodec configCodec,
      PostgresqlSequenceIdGenerator idGenerator) {
    this.editableSupport = editableSupport;
    this.configCodec = configCodec;
    this.idGenerator = idGenerator;
  }

  AgentDefinition newAgent(long modelId, AgentDefinitionEditablePropertiesDTO properties) {
    if (modelId <= 0) {
      throw new IllegalArgumentException("modelId must be positive");
    }
    Mutation mutation = newMutation(properties);
    AgentDefinition definition = new AgentDefinition();
    definition.setId(idGenerator.next());
    definition.setModelId(modelId);
    apply(definition, mutation);
    return definition;
  }

  void update(AgentDefinition definition, AgentDefinitionEditablePropertiesDTO properties) {
    apply(definition, newMutation(properties));
  }

  private void apply(AgentDefinition definition, Mutation mutation) {
    definition.setName(mutation.name());
    definition.setDescription(mutation.description());
    definition.setSystemPrompt(mutation.systemPrompt());
    definition.setVariant(mutation.variant());
    definition.setConfigJson(mutation.configJson());
  }

  private Mutation newMutation(AgentDefinitionEditablePropertiesDTO properties) {
    if (properties == null) {
      throw new IllegalArgumentException("agent definition body must not be null");
    }
    String name = editableSupport.trimToNull(properties.getName());
    if (name == null) {
      throw new IllegalArgumentException("agent name must not be blank");
    }
    AgentDefinitionConfigDTO config = properties.getConfig();
    if (config == null) {
      throw new IllegalArgumentException("agent config must not be null");
    }
    String configJson = configCodec.encode(config);
    // null/blank = no override; runtime/thread apply resolves model.defaultVariant.
    String variant = editableSupport.trimToNull(properties.getVariant());
    return new Mutation(
        name,
        editableSupport.trimToNull(properties.getDescription()),
        editableSupport.trimToNull(properties.getSystemPrompt()),
        variant,
        configJson);
  }

  record Mutation(
      String name, String description, String systemPrompt, String variant, String configJson) {}
}
