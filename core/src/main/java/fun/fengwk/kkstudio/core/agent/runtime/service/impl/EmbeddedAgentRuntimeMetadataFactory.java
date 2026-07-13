package fun.fengwk.kkstudio.core.agent.runtime.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.agent.AgentInfo;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.provider.ProviderInfo;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Builds legacy runtime metadata from the workspace-scoped configuration model. */
@Component
final class EmbeddedAgentRuntimeMetadataFactory {

  private static final String DEFAULT_VARIANT = "default";
  private static final long DEFAULT_TIMEOUT_MILLIS = 60_000L;

  private final ObjectMapper objectMapper;

  EmbeddedAgentRuntimeMetadataFactory(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  ProviderInfo toProviderInfo(AgentProvider provider) {
    return ProviderInfo.builder()
        .providerType(provider.getProviderType())
        .baseUrl(provider.getBaseUrl())
        .apiKey(provider.getCredential())
        .timeout(Duration.ofMillis(timeoutMillis(provider.getConfigJson())))
        .build();
  }

  AgentInfo toAgentInfo(AgentDefinition definition, String providerName, String modelName) {
    return AgentInfo.builder()
        .name(definition.getName())
        .systemPrompt(definition.getSystemPrompt())
        .defaultProvider(providerName)
        .defaultModel(modelName)
        .defaultVariant(firstNonBlank(definition.getVariant(), DEFAULT_VARIANT))
        .tools(parseStringListField(definition.getConfigJson(), "tools"))
        .build();
  }

  ModelInfo toModelInfo(AgentModel model, String providerName) {
    return ModelInfo.builder()
        .provider(providerName)
        .name(model.getName())
        .defaultVariant(DEFAULT_VARIANT)
        .variants(parseVariants(model.getConfigJson()))
        .build();
  }

  private long timeoutMillis(String configJson) {
    try {
      JsonNode root = objectMapper.readTree(configJson);
      return root.path("timeoutMillis").canConvertToLong()
          ? root.path("timeoutMillis").asLong(DEFAULT_TIMEOUT_MILLIS)
          : DEFAULT_TIMEOUT_MILLIS;
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("stored provider config is invalid", e);
    }
  }

  private List<Variant> parseVariants(String configJson) {
    try {
      JsonNode variants = objectMapper.readTree(configJson).path("variants");
      if (!variants.isArray() || variants.isEmpty()) {
        return List.of(Variant.builder().name(DEFAULT_VARIANT).build());
      }
      List<Variant> result = new ArrayList<>();
      for (JsonNode node : variants) {
        result.add(toVariant(node));
      }
      return List.copyOf(result);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("stored model config is invalid", e);
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
    return builder.build();
  }

  private List<String> parseStringListField(String json, String field) {
    try {
      JsonNode root = objectMapper.readTree(json).path(field);
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
      throw new IllegalStateException("stored agent definition config is invalid", e);
    }
  }

  private String textValue(JsonNode node, String fieldName) {
    return node.hasNonNull(fieldName) && node.get(fieldName).isTextual()
        ? node.get(fieldName).asText()
        : null;
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }
}
