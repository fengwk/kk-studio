package fun.fengwk.kkstudio.platform.catalog.provider;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.api.retry.ModelHttpErrorPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 读写 Provider 内部配置 JSON 中的模型调用超时策略与 Provider HTTP 重试白名单覆盖。持久配置中的未知字段视为损坏，不能被读取或写回。 */
@Component
public final class AgentProviderConfigurationCodec {

  private static final String MODEL_CALL_TIMEOUT_MILLIS = "modelCallTimeoutMillis";
  private static final String MODEL_CALL_IDLE_TIMEOUT_MILLIS = "modelCallIdleTimeoutMillis";
  private static final String MODEL_HTTP_RETRY_STATUS_CODES = "modelHttpRetryStatusCodes";
  private static final Set<String> FIELDS =
      Set.of(
          MODEL_CALL_TIMEOUT_MILLIS, MODEL_CALL_IDLE_TIMEOUT_MILLIS, MODEL_HTTP_RETRY_STATUS_CODES);

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

  /** 覆盖超时字段，同时保留已配置的 HTTP 重试覆盖。 */
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
    return writeObject(config);
  }

  /**
   * 读取 Provider 的 HTTP 重试白名单覆盖。
   *
   * @return null 表示未覆盖（继承系统名单）；空列表表示该 Provider 不自动重试任何 HTTP 错误
   */
  public List<Integer> readHttpRetryStatusCodes(String configJson) {
    JsonNode value = readObject(configJson).get(MODEL_HTTP_RETRY_STATUS_CODES);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isArray()) {
      throw new IllegalArgumentException(
          "persisted provider configJson." + MODEL_HTTP_RETRY_STATUS_CODES + " must be an array");
    }
    List<Integer> codes = new ArrayList<>(value.size());
    for (JsonNode item : value) {
      if (!item.isIntegralNumber() || !item.canConvertToInt()) {
        throw new IllegalArgumentException(
            "persisted provider configJson."
                + MODEL_HTTP_RETRY_STATUS_CODES
                + " must contain only integers");
      }
      codes.add(item.intValue());
    }
    // 范围/重复/不可变副本统一由 ModelHttpErrorPolicy 决定，避免第二份规则。
    return new ModelHttpErrorPolicy(codes).retryStatusCodes();
  }

  /**
   * 合并 HTTP 重试白名单覆盖：{@code provided=false} 保留原值；显式 null 清除覆盖；数组完全替代系统名单。
   *
   * <p>调用前 {@code configJson} 已由 {@link #mergeTimeoutPolicy} 规范化为非空对象。
   */
  public String mergeHttpRetryStatusCodes(
      String configJson, boolean provided, List<Integer> statusCodes) {
    ObjectNode config = readObject(configJson);
    if (!provided) {
      return writeObject(config);
    }
    if (statusCodes == null) {
      config.remove(MODEL_HTTP_RETRY_STATUS_CODES);
    } else {
      // 范围/重复统一由 ModelHttpErrorPolicy 决定，避免第二份规则。
      List<Integer> validated = new ModelHttpErrorPolicy(statusCodes).retryStatusCodes();
      ArrayNode array = config.putArray(MODEL_HTTP_RETRY_STATUS_CODES);
      for (Integer code : validated) {
        array.add(code);
      }
    }
    return writeObject(config);
  }

  private String writeObject(ObjectNode config) {
    try {
      return objectMapper.writeValueAsString(config);
    } catch (JacksonException error) {
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
      for (String field : config.propertyNames()) {
        if (!FIELDS.contains(field)) {
          throw new IllegalArgumentException("persisted provider configJson has unknown field");
        }
      }
      return (ObjectNode) config;
    } catch (JacksonException error) {
      throw new IllegalArgumentException("persisted provider configJson must be valid JSON");
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
