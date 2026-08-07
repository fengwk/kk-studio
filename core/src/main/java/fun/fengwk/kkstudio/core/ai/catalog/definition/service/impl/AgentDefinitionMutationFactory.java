package fun.fengwk.kkstudio.core.ai.catalog.definition.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.core.ai.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.ai.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionEditablePropertiesDTO;

/**
 * 规范化可编辑的 Agent 字段，并序列化严格的结构化执行配置。
 *
 * @author fengwk
 */
@Component
final class AgentDefinitionMutationFactory {

  private static final String RESOURCE = "agent_definition";
  private static final int NAME_MAX_LENGTH = 64;
  private static final int DESCRIPTION_MAX_LENGTH = 512;
  private static final int VARIANT_MAX_LENGTH = 64;

  private final AgentEditableSupport editableSupport;
  private final AgentDefinitionConfigCodec configCodec;

  AgentDefinitionMutationFactory(
      AgentEditableSupport editableSupport, AgentDefinitionConfigCodec configCodec) {
    this.editableSupport = editableSupport;
    this.configCodec = configCodec;
  }

  AgentDefinition newAgent(
      String name,
      String modelProviderName,
      String modelName,
      AgentDefinitionEditablePropertiesDTO properties) {
    Mutation mutation = newMutation(name, properties);
    AgentDefinition definition = new AgentDefinition();
    definition.setModelProviderName(modelProviderName);
    definition.setModelName(modelName);
    apply(definition, mutation);
    return definition;
  }

  void update(AgentDefinition definition, AgentDefinitionEditablePropertiesDTO properties) {
    apply(definition, newMutation(definition.getName(), properties));
  }

  private void apply(AgentDefinition definition, Mutation mutation) {
    definition.setName(mutation.name());
    definition.setDescription(mutation.description());
    definition.setSystemPrompt(mutation.systemPrompt());
    definition.setVariant(mutation.variant());
    definition.setConfigJson(mutation.configJson());
  }

  private Mutation newMutation(String name, AgentDefinitionEditablePropertiesDTO properties) {
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
    if (normalizedName.indexOf('/') >= 0) {
      throw new AiValidationException(RESOURCE, RESOURCE + " name must not contain '/'");
    }
    String description = editableSupport.trimToNull(properties.getDescription());
    String systemPrompt = editableSupport.trimToNull(properties.getSystemPrompt());
    // null/blank = 不覆盖；runtime/thread 应用时解析 model.defaultVariant。
    String variant = editableSupport.trimToNull(properties.getVariant());
    editableSupport.validateMaxLength(RESOURCE, "name", normalizedName, NAME_MAX_LENGTH);
    editableSupport.validateMaxLength(RESOURCE, "description", description, DESCRIPTION_MAX_LENGTH);
    editableSupport.validateMaxLength(RESOURCE, "variant", variant, VARIANT_MAX_LENGTH);
    AgentDefinitionConfigDTO config = properties.getConfig();
    if (config == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " config must not be null");
    }
    String configJson;
    try {
      configJson = configCodec.encode(config);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, error.getMessage(), error);
    }
    return new Mutation(normalizedName, description, systemPrompt, variant, configJson);
  }

  record Mutation(
      String name, String description, String systemPrompt, String variant, String configJson) {}
}
