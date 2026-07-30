package fun.fengwk.kkstudio.core.ai.catalog.provider.configuration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;

import java.time.Duration;
import java.util.Objects;

/** 读写 Provider 内部配置 JSON 中的模型调用超时策略。 */
@Component
public final class AgentProviderConfigurationCodec {

  public static final String MODEL_CALL_TIMEOUT_MILLIS = "modelCallTimeoutMillis";
  public static final String MODEL_CALL_IDLE_TIMEOUT_MILLIS = "modelCallIdleTimeoutMillis";

  private final ObjectMapper objectMapper;

  public AgentProviderConfigurationCodec(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  /** 读取策略；未配置的字段使用产品默认值。 */
  public ModelCallTimeoutPolicy readTimeoutPolicy(String configJson) {
    ObjectNode config = readObject(configJson);
    return new ModelCallTimeoutPolicy(
        Duration.ofMillis(
            positiveMillisOrDefault(
                config,
                MODEL_CALL_TIMEOUT_MILLIS,
                ModelCallTimeoutPolicy.DEFAULT.modelCallTimeout().toMillis())),
        Duration.ofMillis(
            positiveMillisOrDefault(
                config,
                MODEL_CALL_IDLE_TIMEOUT_MILLIS,
                ModelCallTimeoutPolicy.DEFAULT.modelCallIdleTimeout().toMillis())));
  }

  /** 覆盖已知超时字段，同时保留 Provider 扩展可能写入的其他内部配置。 */
  public String mergeTimeoutPolicy(
      String configJson, Long modelCallTimeoutMillis, Long modelCallIdleTimeoutMillis) {
    ObjectNode config = readObject(configJson);
    ModelCallTimeoutPolicy current = readTimeoutPolicy(config.toString());
    long totalMillis =
        modelCallTimeoutMillis == null
            ? current.modelCallTimeout().toMillis()
            : requirePositiveMillis(MODEL_CALL_TIMEOUT_MILLIS, modelCallTimeoutMillis);
    long idleMillis =
        modelCallIdleTimeoutMillis == null
            ? current.modelCallIdleTimeout().toMillis()
            : requirePositiveMillis(MODEL_CALL_IDLE_TIMEOUT_MILLIS, modelCallIdleTimeoutMillis);
    config.put(MODEL_CALL_TIMEOUT_MILLIS, totalMillis);
    config.put(MODEL_CALL_IDLE_TIMEOUT_MILLIS, idleMillis);
    try {
      return objectMapper.writeValueAsString(config);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode provider configuration", error);
    }
  }

  private ObjectNode readObject(String configJson) {
    if (configJson == null || configJson.isBlank()) {
      return objectMapper.createObjectNode();
    }
    try {
      JsonNode config = objectMapper.readTree(configJson);
      if (config == null || !config.isObject()) {
        throw new IllegalArgumentException("persisted provider configJson must be an object");
      }
      return (ObjectNode) config;
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("persisted provider configJson must be valid JSON", error);
    }
  }

  private static long positiveMillisOrDefault(ObjectNode config, String field, long fallback) {
    JsonNode value = config.get(field);
    if (value == null || value.isNull()) {
      return fallback;
    }
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException(
          "persisted provider configJson." + field + " must be an integer");
    }
    return requirePositiveMillis(field, value.longValue());
  }

  private static long requirePositiveMillis(String field, long value) {
    if (value <= 0) {
      throw new IllegalArgumentException("provider " + field + " must be positive");
    }
    return value;
  }
}
