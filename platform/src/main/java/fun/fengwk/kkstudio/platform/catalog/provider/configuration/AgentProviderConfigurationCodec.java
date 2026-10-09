package fun.fengwk.kkstudio.platform.catalog.provider.configuration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 读写 Provider 内部配置 JSON 中的模型调用超时策略与 Provider HTTP 重试白名单覆盖。 */
@Component
public final class AgentProviderConfigurationCodec {

  public static final String MODEL_CALL_TIMEOUT_MILLIS = "modelCallTimeoutMillis";
  public static final String MODEL_CALL_IDLE_TIMEOUT_MILLIS = "modelCallIdleTimeoutMillis";
  public static final String MODEL_HTTP_RETRY_STATUS_CODES = "modelHttpRetryStatusCodes";

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

  /** 比较两份配置在剔除超时字段与 HTTP 重试覆盖后是否在结构上完全等价（用于判断是否需要轮换 connection_generation_id）。 */
  public boolean isProtocolConfigEqual(String configJson1, String configJson2) {
    ObjectNode node1 = readObject(configJson1).deepCopy();
    ObjectNode node2 = readObject(configJson2).deepCopy();
    node1.remove(MODEL_CALL_TIMEOUT_MILLIS);
    node1.remove(MODEL_CALL_IDLE_TIMEOUT_MILLIS);
    node1.remove(MODEL_HTTP_RETRY_STATUS_CODES);
    node2.remove(MODEL_CALL_TIMEOUT_MILLIS);
    node2.remove(MODEL_CALL_IDLE_TIMEOUT_MILLIS);
    node2.remove(MODEL_HTTP_RETRY_STATUS_CODES);
    return node1.equals(node2);
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
      int code = item.intValue();
      if (code < 400 || code > 599) {
        throw new IllegalArgumentException(
            "persisted provider configJson."
                + MODEL_HTTP_RETRY_STATUS_CODES
                + " must contain only HTTP error statuses 400-599");
      }
      if (codes.contains(code)) {
        throw new IllegalArgumentException(
            "persisted provider configJson."
                + MODEL_HTTP_RETRY_STATUS_CODES
                + " must not contain duplicate statuses");
      }
      codes.add(code);
    }
    return List.copyOf(codes);
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
      for (Integer code : statusCodes) {
        if (code == null || code < 400 || code > 599) {
          throw new IllegalArgumentException(
              "provider "
                  + MODEL_HTTP_RETRY_STATUS_CODES
                  + " must contain only HTTP error statuses"
                  + " 400-599");
        }
      }
      if (new HashSet<>(statusCodes).size() != statusCodes.size()) {
        throw new IllegalArgumentException(
            "provider " + MODEL_HTTP_RETRY_STATUS_CODES + " must not contain duplicate statuses");
      }
      ArrayNode array = config.putArray(MODEL_HTTP_RETRY_STATUS_CODES);
      for (Integer code : statusCodes) {
        array.add(code);
      }
    }
    return writeObject(config);
  }

  private String writeObject(ObjectNode config) {
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
