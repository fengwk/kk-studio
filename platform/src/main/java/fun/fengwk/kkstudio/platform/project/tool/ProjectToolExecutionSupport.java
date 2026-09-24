package fun.fengwk.kkstudio.platform.project.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;

import java.util.UUID;

/** Project 工具参数解析、投影序列化与安全脱敏支撑组件。 */
final class ProjectToolExecutionSupport {

  private static final String INCONSISTENT_OWNERSHIP = "Project thread ownership is inconsistent";
  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .configure(SerializationFeature.INDENT_OUTPUT, true)
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
          .setSerializationInclusion(JsonInclude.Include.NON_NULL);

  private ProjectToolExecutionSupport() {}

  static JsonNode parseJson(String json) {
    try {
      return OBJECT_MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid JSON arguments");
    }
  }

  static String toJson(Object object) {
    try {
      return OBJECT_MAPPER.writeValueAsString(object);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize result to JSON");
    }
  }

  static UUID parseUuid(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equalsIgnoreCase(value)) {
        throw new IllegalArgumentException("Invalid " + fieldName + " format");
      }
      return parsed;
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid " + fieldName + " format");
    }
  }

  static long requireNonNegativeLong(JsonNode node, String fieldName) {
    if (node == null
        || !node.has(fieldName)
        || !node.get(fieldName).isIntegralNumber()
        || !node.get(fieldName).canConvertToLong()) {
      throw new IllegalArgumentException(fieldName + " is required and must be an integer");
    }
    long val = node.get(fieldName).asLong();
    if (val < 0) {
      throw new IllegalArgumentException(fieldName + " must not be negative");
    }
    return val;
  }

  static String requireNonBlankString(JsonNode node, String fieldName) {
    if (node == null || !node.has(fieldName) || !node.get(fieldName).isTextual()) {
      throw new IllegalArgumentException(fieldName + " is required and must be a string");
    }
    String text = node.get(fieldName).asText();
    if (text.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return text.trim();
  }

  /** 读取可选文本字段：缺失或 null 返回 {@code null}。 */
  static String optionalText(JsonNode node, String fieldName) {
    if (node == null || !node.has(fieldName) || node.get(fieldName).isNull()) {
      return null;
    }
    JsonNode value = node.get(fieldName);
    if (!value.isTextual()) {
      throw new IllegalArgumentException(fieldName + " must be a string");
    }
    return value.asText();
  }

  /** 读取可选 UUID 字段：缺失或 null 返回 {@code null}，格式非法明确拒绝。 */
  static UUID optionalUuid(JsonNode node, String fieldName) {
    String text = optionalText(node, fieldName);
    return text == null ? null : parseUuid(text, fieldName);
  }

  /** 读取可选长整数字段：缺失或 null 返回 {@code null}，负数明确拒绝。 */
  static Long optionalLong(JsonNode node, String fieldName) {
    if (node == null || !node.has(fieldName) || node.get(fieldName).isNull()) {
      return null;
    }
    return requireNonNegativeLong(node, fieldName);
  }

  /** 读取可选整数字段：缺失或 null 返回 {@code null}，非 int 范围明确拒绝。 */
  static Integer optionalInt(JsonNode node, String fieldName) {
    Long value = optionalLong(node, fieldName);
    if (value == null) {
      return null;
    }
    if (value > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(fieldName + " exceeds the supported range");
    }
    return value.intValue();
  }

  static String sanitizeErrorMessage(Throwable t) {
    if (t instanceof AiResourceNotFoundException nf) {
      return nf.resource() + " not found";
    }
    if (t instanceof AiVersionConflictException vc) {
      return vc.resource()
          + " version conflict: expected="
          + vc.expectedVersion()
          + " actual="
          + vc.actualVersion();
    }
    if (t instanceof AiValidationException) {
      String msg = t.getMessage();
      return (msg == null || msg.isBlank()) ? "Validation failed" : msg;
    }
    if (t instanceof IllegalStateException) {
      return INCONSISTENT_OWNERSHIP.equals(t.getMessage())
          ? INCONSISTENT_OWNERSHIP
          : "Project role tool execution failed";
    }
    if (t instanceof IllegalArgumentException) {
      String msg = t.getMessage();
      return (msg == null || msg.isBlank()) ? "Invalid request" : msg;
    }
    return "Project role tool execution failed";
  }
}
