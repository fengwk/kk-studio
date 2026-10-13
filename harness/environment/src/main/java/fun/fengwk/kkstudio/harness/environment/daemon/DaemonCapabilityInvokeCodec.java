package fun.fengwk.kkstudio.harness.environment.daemon;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityId;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Daemon capability INVOKE payload 的严格 codec。
 *
 * <p>wire 字段及顺序固定为 {@code capabilityId}、{@code capabilityVersion}、{@code arguments}、{@code
 * timeoutMillis}。所有字段都必填。INVOKE 外壳不携带 workdir：目录只存在于具体 arguments 中，Daemon 由自己的 {@code Path} 校验其结果。
 *
 * <p>解析器启用 duplicate/trailing 拒绝和有界 Jackson read constraints。Capability ID 使用 {@link
 * EnvironmentCapabilityId} 的 canonical 语法；arguments 必须是 JSON object，timeout 必须是非负、无小数的 64 位毫秒整数。
 */
public final class DaemonCapabilityInvokeCodec {

  public record InvokeRequest(
      EnvironmentCapabilityId capabilityId,
      String capabilityVersion,
      String argumentsJson,
      Duration timeout) {

    public InvokeRequest {
      capabilityId = Objects.requireNonNull(capabilityId, "capabilityId");
      capabilityVersion = requireNonBlank(capabilityVersion, "capabilityVersion");
      argumentsJson = canonicalArguments(argumentsJson);
      timeout = requireTimeout(timeout);
    }
  }

  private static final int MAX_DOCUMENT_LENGTH = 16 * 1024 * 1024;
  private static final ObjectMapper MAPPER =
      JsonMapper.builder(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxStringLength(MAX_DOCUMENT_LENGTH)
                          .maxNestingDepth(100)
                          .maxNumberLength(1000)
                          .maxDocumentLength(MAX_DOCUMENT_LENGTH)
                          .build())
                  .build())
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private static final Set<String> FIELDS =
      Set.of("capabilityId", "capabilityVersion", "arguments", "timeoutMillis");

  /** 把 request 编码为字段顺序固定且不省略任何字段的 canonical JSON。 */
  public String encode(InvokeRequest request) {
    Objects.requireNonNull(request, "request");
    ObjectNode root = MAPPER.createObjectNode();
    root.put("capabilityId", request.capabilityId().value());
    root.put("capabilityVersion", request.capabilityVersion());
    root.set("arguments", readArguments(request.argumentsJson()));
    root.put("timeoutMillis", request.timeout().toMillis());
    return write(root);
  }

  /** 解码严格的 capability INVOKE payload。 */
  public InvokeRequest decode(String json) {
    ObjectNode root = requiredObject(json);
    rejectUnknownFields(root);
    JsonNode arguments = requiredObjectField(root, "arguments");
    try {
      return new InvokeRequest(
          new EnvironmentCapabilityId(requiredText(root, "capabilityId")),
          requiredText(root, "capabilityVersion"),
          write(arguments),
          Duration.ofMillis(requiredNonNegativeLong(root, "timeoutMillis")));
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          "capability INVOKE payload validation failed: " + error.getMessage(), error);
    }
  }

  private static String canonicalArguments(String json) {
    if (json == null || json.isBlank()) {
      throw new IllegalArgumentException("arguments must be a non-blank JSON object");
    }
    JsonNode value;
    try {
      value = MAPPER.readTree(json);
    } catch (JacksonException error) {
      throw new IllegalArgumentException("arguments must be valid JSON", error);
    }
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException("arguments must be a JSON object");
    }
    return write(value);
  }

  private static ObjectNode requiredObject(String json) {
    if (json == null) {
      throw new DaemonProtocolException("capability INVOKE payload must not be null");
    }
    JsonNode value;
    try {
      value = MAPPER.readTree(json);
    } catch (JacksonException error) {
      throw new DaemonProtocolException("capability INVOKE payload must be valid JSON", error);
    }
    if (!(value instanceof ObjectNode object)) {
      throw new DaemonProtocolException("capability INVOKE payload must be a JSON object");
    }
    return object;
  }

  private static void rejectUnknownFields(ObjectNode root) {
    for (String field : root.propertyNames()) {
      if (!FIELDS.contains(field)) {
        throw new DaemonProtocolException("capability INVOKE payload has unknown field: " + field);
      }
    }
  }

  private static String requiredText(ObjectNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || !value.isString() || value.stringValue().isBlank()) {
      throw new DaemonProtocolException(
          "capability INVOKE payload." + field + " must be a non-blank string");
    }
    return value.stringValue();
  }

  private static ObjectNode requiredObjectField(ObjectNode root, String field) {
    JsonNode value = root.get(field);
    if (!(value instanceof ObjectNode object)) {
      throw new DaemonProtocolException(
          "capability INVOKE payload." + field + " must be a JSON object");
    }
    return object;
  }

  private static long requiredNonNegativeLong(ObjectNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.longValue() < 0) {
      throw new DaemonProtocolException(
          "capability INVOKE payload." + field + " must be a non-negative long integer");
    }
    return value.longValue();
  }

  private static String requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }

  private static Duration requireTimeout(Duration value) {
    value = Objects.requireNonNull(value, "timeout");
    if (value.isNegative()) {
      throw new IllegalArgumentException("timeout must not be negative");
    }
    if (value.getNano() % 1_000_000 != 0) {
      throw new IllegalArgumentException("timeout must be an exact number of milliseconds");
    }
    try {
      value.toMillis();
    } catch (ArithmeticException error) {
      throw new IllegalArgumentException("timeout milliseconds overflow long", error);
    }
    return value;
  }

  private static ObjectNode readArguments(String json) {
    try {
      JsonNode value = MAPPER.readTree(json);
      if (!(value instanceof ObjectNode object)) {
        throw new IllegalStateException("canonical arguments JSON is not an object");
      }
      return object;
    } catch (JacksonException error) {
      throw new IllegalStateException("canonical arguments JSON cannot be read", error);
    }
  }

  private static String write(JsonNode value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JacksonException error) {
      throw new IllegalStateException("cannot encode capability INVOKE JSON", error);
    }
  }
}
