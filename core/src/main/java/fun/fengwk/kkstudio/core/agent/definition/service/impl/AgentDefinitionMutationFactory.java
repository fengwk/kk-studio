package fun.fengwk.kkstudio.core.agent.definition.service.impl;

import static fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator.nextAgentId;

import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.share.model.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionUpdateDTO;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.share.model.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionUpdateDTO;
import org.springframework.stereotype.Component;

/**
 * AgentDefinitionMutationFactory 负责 agent 定义写路径的入参校验、标准化与实体组装。
 *
 * @author fengwk
 */
@Component
final class AgentDefinitionMutationFactory {

  private static final String DEFAULT_VARIANT = "default";
  private static final String EMPTY_ARRAY_JSON = "[]";

  private final AgentEditableSupport editableSupport;

  AgentDefinitionMutationFactory(AgentEditableSupport editableSupport) {
    if (editableSupport == null) {
      throw new IllegalArgumentException("editableSupport must not be null");
    }
    this.editableSupport = editableSupport;
  }

  Mutation newCreateMutation(AgentDefinitionCreateDTO createDTO) {
    validateEditable(createDTO, true);
    return new Mutation(
        editableSupport.trimToNull(createDTO.getName()),
        editableSupport.trimToNull(createDTO.getDescription()),
        editableSupport.trimToNull(createDTO.getSystemPrompt()),
        editableSupport.trimToNull(createDTO.getDefaultProvider()),
        editableSupport.trimToNull(createDTO.getDefaultModel()),
        editableSupport.firstNonBlank(createDTO.getDefaultVariant(), DEFAULT_VARIANT),
        editableSupport.firstNonBlank(createDTO.getToolsJson(), EMPTY_ARRAY_JSON),
        editableSupport.firstNonBlank(createDTO.getSubagentsJson(), EMPTY_ARRAY_JSON),
        editableSupport.firstNonBlank(createDTO.getSkillsJson(), EMPTY_ARRAY_JSON));
  }

  Mutation newUpdateMutation(String currentName, AgentDefinitionUpdateDTO updateDTO) {
    requireNonBlank(currentName, "currentName");
    validateEditable(updateDTO, false);
    return new Mutation(
        editableSupport.firstNonBlank(updateDTO.getName(), currentName),
        editableSupport.trimToNull(updateDTO.getDescription()),
        editableSupport.trimToNull(updateDTO.getSystemPrompt()),
        editableSupport.trimToNull(updateDTO.getDefaultProvider()),
        editableSupport.trimToNull(updateDTO.getDefaultModel()),
        editableSupport.firstNonBlank(updateDTO.getDefaultVariant(), DEFAULT_VARIANT),
        editableSupport.firstNonBlank(updateDTO.getToolsJson(), EMPTY_ARRAY_JSON),
        editableSupport.firstNonBlank(updateDTO.getSubagentsJson(), EMPTY_ARRAY_JSON),
        editableSupport.firstNonBlank(updateDTO.getSkillsJson(), EMPTY_ARRAY_JSON));
  }

  AgentDefinition newAgent(long providerId, long modelId, Mutation mutation) {
    requirePositive(providerId, "providerId");
    requirePositive(modelId, "modelId");
    requireNonNull(mutation, "mutation");
    AgentDefinition agent = new AgentDefinition();
    agent.setId(nextAgentId());
    apply(agent, providerId, modelId, mutation);
    return agent;
  }

  void apply(AgentDefinition agent, long providerId, long modelId, Mutation mutation) {
    requireNonNull(agent, "agent");
    requirePositive(providerId, "providerId");
    requirePositive(modelId, "modelId");
    requireNonNull(mutation, "mutation");
    agent.setName(mutation.name());
    agent.setDescription(mutation.description());
    agent.setSystemPrompt(mutation.systemPrompt());
    agent.setDefaultProviderId(providerId);
    agent.setDefaultModelId(modelId);
    agent.setDefaultVariant(mutation.defaultVariant());
    agent.setToolsJson(mutation.toolsJson());
    agent.setSubagentsJson(mutation.subagentsJson());
    agent.setSkillsJson(mutation.skillsJson());
  }

  private void validateEditable(
      AgentDefinitionEditablePropertiesDTO properties, boolean requireName) {
    if (properties == null) {
      throw new IllegalArgumentException("agent definition body must not be null");
    }
    if (requireName && editableSupport.trimToNull(properties.getName()) == null) {
      throw new IllegalArgumentException("agent name must not be blank");
    }
    if (editableSupport.trimToNull(properties.getDefaultProvider()) == null) {
      throw new IllegalArgumentException("agent defaultProvider must not be blank");
    }
    if (editableSupport.trimToNull(properties.getDefaultModel()) == null) {
      throw new IllegalArgumentException("agent defaultModel must not be blank");
    }
    editableSupport.validateJsonArray(
        editableSupport.firstNonBlank(properties.getToolsJson(), EMPTY_ARRAY_JSON), "toolsJson");
    editableSupport.validateJsonArray(
        editableSupport.firstNonBlank(properties.getSubagentsJson(), EMPTY_ARRAY_JSON),
        "subagentsJson");
    editableSupport.validateJsonArray(
        editableSupport.firstNonBlank(properties.getSkillsJson(), EMPTY_ARRAY_JSON), "skillsJson");
  }

  private static <T> T requireNonNull(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    return value;
  }

  private static void requirePositive(long value, String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  record Mutation(
      String name,
      String description,
      String systemPrompt,
      String defaultProvider,
      String defaultModel,
      String defaultVariant,
      String toolsJson,
      String subagentsJson,
      String skillsJson) {}
}
