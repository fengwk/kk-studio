package fun.fengwk.kkstudio.core.agent.definition.service.impl;

import static fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator.nextAgentId;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.share.model.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.model.AgentExecutionPolicyDTO;

import java.util.List;

/**
 * Normalizes the persisted agent definition and serializes its structured execution configuration.
 *
 * @author fengwk
 */
@Component
final class AgentDefinitionMutationFactory {

  private static final String DEFAULT_VARIANT = "default";

  private final AgentEditableSupport editableSupport;
  private final ObjectMapper objectMapper;

  AgentDefinitionMutationFactory(AgentEditableSupport editableSupport, ObjectMapper objectMapper) {
    this.editableSupport = editableSupport;
    this.objectMapper = objectMapper;
  }

  AgentDefinition newAgent(long modelId, AgentDefinitionEditablePropertiesDTO properties) {
    if (modelId <= 0) {
      throw new IllegalArgumentException("modelId must be positive");
    }
    Mutation mutation = newMutation(properties, null, null, true);
    AgentDefinition definition = new AgentDefinition();
    definition.setId(nextAgentId());
    definition.setModelId(modelId);
    apply(definition, mutation);
    return definition;
  }

  void update(AgentDefinition definition, AgentDefinitionEditablePropertiesDTO properties) {
    apply(
        definition,
        newMutation(properties, definition.getName(), definition.getConfigJson(), false));
  }

  private void apply(AgentDefinition definition, Mutation mutation) {
    definition.setName(mutation.name());
    definition.setDescription(mutation.description());
    definition.setSystemPrompt(mutation.systemPrompt());
    definition.setVariant(mutation.variant());
    definition.setConfigJson(mutation.configJson());
  }

  private Mutation newMutation(
      AgentDefinitionEditablePropertiesDTO properties,
      String fallbackName,
      String existingConfigJson,
      boolean creating) {
    if (properties == null) {
      throw new IllegalArgumentException("agent definition body must not be null");
    }
    String name = editableSupport.firstNonBlank(properties.getName(), fallbackName);
    if (name == null) {
      throw new IllegalArgumentException("agent name must not be blank");
    }
    AgentDefinitionConfigDTO config = properties.getConfig();
    String configJson =
        config == null && !creating ? existingConfigJson : writeConfig(normalizeConfig(config));
    return new Mutation(
        name,
        editableSupport.trimToNull(properties.getDescription()),
        editableSupport.trimToNull(properties.getSystemPrompt()),
        editableSupport.firstNonBlank(properties.getVariant(), DEFAULT_VARIANT),
        configJson);
  }

  private AgentDefinitionConfigDTO normalizeConfig(AgentDefinitionConfigDTO config) {
    AgentDefinitionConfigDTO result = config == null ? new AgentDefinitionConfigDTO() : config;
    result.setTools(normalizeStrings(result.getTools()));
    result.setSkills(normalizeStrings(result.getSkills()));
    result.setAllowedSubagents(normalizeStrings(result.getAllowedSubagents()));
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

  private List<String> normalizeStrings(List<String> values) {
    if (values == null) {
      return List.of();
    }
    return values.stream()
        .map(editableSupport::trimToNull)
        .filter(value -> value != null)
        .distinct()
        .toList();
  }

  private String writeConfig(AgentDefinitionConfigDTO config) {
    try {
      return objectMapper.writeValueAsString(config);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("agent definition config cannot be serialized", e);
    }
  }

  record Mutation(
      String name, String description, String systemPrompt, String variant, String configJson) {}
}
