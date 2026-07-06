package fun.fengwk.kkstudio.core.agent.runtime.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.agent.AgentInfo;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.provider.ProviderInfo;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.agent.AgentInfo;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.provider.ProviderInfo;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * EmbeddedAgentRuntimeMetadataFactory 负责 runtime 所需 provider/model/agent 元数据的组装。
 *
 * @author fengwk
 */
@Component
final class EmbeddedAgentRuntimeMetadataFactory {

  private static final String DEFAULT_VARIANT = "default";
  private static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE =
      new TypeReference<>() {};

  private final ObjectMapper objectMapper;

  EmbeddedAgentRuntimeMetadataFactory(ObjectMapper objectMapper) {
    if (objectMapper == null) {
      throw new IllegalArgumentException("objectMapper must not be null");
    }
    this.objectMapper = objectMapper;
  }

  ProviderInfo toProviderInfo(AgentProvider agentProvider) {
    requireNonNull(agentProvider, "agentProvider");
    return ProviderInfo.builder()
        .providerType(agentProvider.getProviderType())
        .baseUrl(agentProvider.getBaseUrl())
        .apiKey(agentProvider.getApiKey())
        .timeout(agentProvider.getTimeout())
        .streamIdleTimeout(agentProvider.getStreamIdleTimeout())
        .build();
  }

  AgentInfo toAgentInfo(AgentDefinition agentDefinition, String providerName, String modelName) {
    requireNonNull(agentDefinition, "agentDefinition");
    return AgentInfo.builder()
        .name(agentDefinition.getName())
        .systemPrompt(agentDefinition.getSystemPrompt())
        .defaultProvider(providerName)
        .defaultModel(modelName)
        .defaultVariant(firstNonBlank(agentDefinition.getDefaultVariant(), DEFAULT_VARIANT))
        .tools(parseStringList(agentDefinition.getToolsJson()))
        .subagents(parseStringList(agentDefinition.getSubagentsJson()))
        .skills(parseStringList(agentDefinition.getSkillsJson()))
        .build();
  }

  ModelInfo toModelInfo(AgentModel agentModel, String providerName) {
    requireNonNull(agentModel, "agentModel");
    return ModelInfo.builder()
        .provider(providerName)
        .name(agentModel.getName())
        .displayName(agentModel.getName())
        .defaultVariant(firstNonBlank(agentModel.getDefaultVariant(), DEFAULT_VARIANT))
        .variants(parseVariants(agentModel.getVariantsJson()))
        .build();
  }

  private List<Variant> parseVariants(String variantsJson) {
    if (variantsJson == null || variantsJson.isBlank()) {
      return List.of(Variant.builder().name(DEFAULT_VARIANT).build());
    }
    try {
      JsonNode root = objectMapper.readTree(variantsJson);
      if (!root.isArray()) {
        throw new IllegalArgumentException("variantsJson must be a JSON array");
      }
      List<Variant> variants = new ArrayList<>();
      for (JsonNode node : root) {
        variants.add(toVariant(node));
      }
      return variants.isEmpty()
          ? List.of(Variant.builder().name(DEFAULT_VARIANT).build())
          : List.copyOf(variants);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("parse model variantsJson failed", e);
    }
  }

  private Variant toVariant(JsonNode node) {
    Variant.VariantBuilder builder =
        Variant.builder().name(firstNonBlank(textValue(node, "name"), DEFAULT_VARIANT));
    if (node.hasNonNull("maxOutputTokens")) {
      builder.maxOutputTokens(node.get("maxOutputTokens").asInt());
    }
    if (node.hasNonNull("temperature")) {
      builder.temperature(node.get("temperature").asDouble());
    }
    if (node.hasNonNull("topP")) {
      builder.topP(node.get("topP").asDouble());
    }
    if (node.hasNonNull("topK")) {
      builder.topK(node.get("topK").asInt());
    }
    if (node.hasNonNull("frequencyPenalty")) {
      builder.frequencyPenalty(node.get("frequencyPenalty").asDouble());
    }
    if (node.hasNonNull("presencePenalty")) {
      builder.presencePenalty(node.get("presencePenalty").asDouble());
    }
    if (node.hasNonNull("seed")) {
      builder.seed(node.get("seed").asInt());
    }
    if (node.hasNonNull("stopSequences") && node.get("stopSequences").isArray()) {
      List<String> stopSequences = new ArrayList<>();
      for (JsonNode sequence : node.get("stopSequences")) {
        if (sequence.isTextual()) {
          stopSequences.add(sequence.asText());
        }
      }
      builder.stopSequences(List.copyOf(stopSequences));
    }
    if (node.hasNonNull("providerOptions") && node.get("providerOptions").isObject()) {
      try {
        Map<String, Object> providerOptions =
            objectMapper.convertValue(node.get("providerOptions"), MAP_TYPE_REFERENCE);
        builder.providerOptions(providerOptions);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("parse variant providerOptions failed", e);
      }
    }
    return builder.build();
  }

  private List<String> parseStringList(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      JsonNode root = objectMapper.readTree(json);
      if (!root.isArray()) {
        return List.of();
      }
      List<String> result = new ArrayList<>();
      for (JsonNode node : root) {
        if (node.isTextual() && !node.asText().isBlank()) {
          result.add(node.asText());
        }
      }
      return List.copyOf(result);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("parse agent string list failed", e);
    }
  }

  private String textValue(JsonNode node, String fieldName) {
    if (node == null || !node.hasNonNull(fieldName) || !node.get(fieldName).isTextual()) {
      return null;
    }
    return node.get(fieldName).asText();
  }

  private String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private static <T> T requireNonNull(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    return value;
  }
}
