package fun.fengwk.kkstudio.core.agent.definition.service.impl;

import static fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator.nextAgentId;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.share.model.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.model.AgentExecutionPolicyDTO;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Normalizes the persisted agent definition and serializes its structured execution configuration.
 *
 * @author fengwk
 */
@Component
final class AgentDefinitionMutationFactory {

  private final AgentEditableSupport editableSupport;
  private final AgentDefinitionConfigCodec configCodec;

  AgentDefinitionMutationFactory(
      AgentEditableSupport editableSupport, AgentDefinitionConfigCodec configCodec) {
    this.editableSupport = editableSupport;
    this.configCodec = configCodec;
  }

  AgentDefinition newAgent(long modelId, AgentDefinitionEditablePropertiesDTO properties) {
    if (modelId <= 0) {
      throw new IllegalArgumentException("modelId must be positive");
    }
    Mutation mutation = newMutation(properties);
    AgentDefinition definition = new AgentDefinition();
    definition.setId(nextAgentId());
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
    String configJson = configCodec.encode(normalizeConfig(config));
    String variant = editableSupport.trimToNull(properties.getVariant());
    if (variant == null) {
      throw new IllegalArgumentException("agent variant must not be blank");
    }
    return new Mutation(
        name,
        editableSupport.trimToNull(properties.getDescription()),
        editableSupport.trimToNull(properties.getSystemPrompt()),
        variant,
        configJson);
  }

  private AgentDefinitionConfigDTO normalizeConfig(AgentDefinitionConfigDTO config) {
    AgentDefinitionConfigDTO result = config;
    result.setEnvironmentName(editableSupport.trimToNull(result.getEnvironmentName()));
    result.setTools(normalizeStrings(result.getTools(), "tools"));
    result.setSkills(normalizeStrings(result.getSkills(), "skills"));
    result.setAllowedSubagents(normalizeStrings(result.getAllowedSubagents(), "allowedSubagents"));
    result.setExecutionPolicy(normalizePolicy(result.getExecutionPolicy()));
    return result;
  }

  private AgentExecutionPolicyDTO normalizePolicy(AgentExecutionPolicyDTO policy) {
    AgentExecutionPolicyDTO result = policy == null ? new AgentExecutionPolicyDTO() : policy;
    validatePositive(result.getMaxTurns(), "executionPolicy.maxTurns");
    validatePositive(result.getMaxDepth(), "executionPolicy.maxDepth");
    validatePositive(result.getMaxDirectSubagents(), "executionPolicy.maxDirectSubagents");
    validatePositive(result.getMaxTotalSubagents(), "executionPolicy.maxTotalSubagents");
    return result;
  }

  private void validatePositive(Number value, String fieldName) {
    if (value != null && value.longValue() <= 0) {
      throw new IllegalArgumentException(fieldName + " must be positive");
    }
  }

  private List<String> normalizeStrings(List<String> values, String field) {
    if (values == null) {
      return List.of();
    }
    Set<String> result = new LinkedHashSet<>();
    for (String raw : values) {
      String value = editableSupport.trimToNull(raw);
      if (value == null) {
        continue;
      }
      if (!result.add(value)) {
        throw new IllegalArgumentException(
            "agent " + field + " must not contain duplicates: " + value);
      }
    }
    return List.copyOf(result);
  }

  record Mutation(
      String name, String description, String systemPrompt, String variant, String configJson) {}
}
