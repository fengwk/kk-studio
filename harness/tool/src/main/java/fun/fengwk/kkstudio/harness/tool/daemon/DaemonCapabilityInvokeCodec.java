package fun.fengwk.kkstudio.harness.tool.daemon;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityId;

import java.time.Duration;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

/**
 * Daemon capability INVOKE payload 的严格 codec。
 *
 * <p>wire 字段及顺序固定为 {@code capabilityId}、{@code capabilityVersion}、{@code workspacePath}、{@code
 * arguments}、{@code timeoutMillis}。所有字段都必填；workspace path 只在此边界要求 non-blank，canonical path 和
 * Environment Root 内解析由后续 Daemon 执行边界负责。
 *
 * <p>解析器启用 duplicate/trailing 拒绝和有界 Jackson read constraints。Capability ID 使用 {@link
 * EnvironmentCapabilityId} 的 canonical 语法；arguments 必须是 JSON object，timeout 必须是非负、无小数的 64 位毫秒整数。
 */
public final class DaemonCapabilityInvokeCodec {

  public record InvokeRequest(
      EnvironmentCapabilityId capabilityId,
      String capabilityVersion,
      String workspacePath,
      String argumentsJson,
      Duration timeout) {

    public InvokeRequest {
      capabilityId = Objects.requireNonNull(capabilityId, "capabilityId");
      capabilityVersion = requireNonBlank(capabilityVersion, "capabilityVersion");
      workspacePath = requireNonBlank(workspacePath, "workspacePath");
      argumentsJson = canonicalArguments(argumentsJson);
      timeout = requireTimeout(timeout);
    }
  }

  private static final int MAX_DOCUMENT_LENGTH = 16 * 1024 * 1024;
  private static final ObjectMapper MAPPER =
      new ObjectMapper(
          JsonFactory.builder()
              .streamReadConstraints(
                  StreamReadConstraints.builder()
                      .maxStringLength(MAX_DOCUMENT_LENGTH)
                      .maxNestingDepth(100)
                      .maxNumberLength(1000)
                      .maxDocumentLength(MAX_DOCUMENT_LENGTH)
                      .build())
              .build());

  private static final Set<String> FIELDS =
      Set.of("capabilityId", "capabilityVersion", "workspacePath", "arguments", "timeoutMillis");

  static {
    MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  /** 把 request 编码为字段顺序固定且不省略任何字段的 canonical JSON。 */
  public String encode(InvokeRequest request) {
    Objects.requireNonNull(request, "request");
    ObjectNode root = MAPPER.createObjectNode();
    root.put("capabilityId", request.capabilityId().value());
    root.put("capabilityVersion", request.capabilityVersion());
    root.put("workspacePath", request.workspacePath());
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
          requiredText(root, "workspacePath"),
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
    } catch (JsonProcessingException error) {
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
    } catch (JsonProcessingException error) {
      throw new DaemonProtocolException("capability INVOKE payload must be valid JSON", error);
    }
    if (!(value instanceof ObjectNode object)) {
      throw new DaemonProtocolException("capability INVOKE payload must be a JSON object");
    }
    return object;
  }

  private static void rejectUnknownFields(ObjectNode root) {
    Iterator<String> fields = root.fieldNames();
    while (fields.hasNext()) {
      String field = fields.next();
      if (!FIELDS.contains(field)) {
        throw new DaemonProtocolException("capability INVOKE payload has unknown field: " + field);
      }
    }
  }

  private static String requiredText(ObjectNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new DaemonProtocolException(
          "capability INVOKE payload." + field + " must be a non-blank string");
    }
    return value.textValue();
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
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("canonical arguments JSON cannot be read", error);
    }
  }

  private static String write(JsonNode value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode capability INVOKE JSON", error);
    }
  }
}
