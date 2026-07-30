package fun.fengwk.kkstudio.core.ai.catalog.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.error.AiValidationException;

import java.io.IOException;

/**
 * AgentEditableSupport 负责标准化 agent 管理面的字符串与 JSON 可编辑字段。
 *
 * @author fengwk
 */
@Component
public final class AgentEditableSupport {

  private final ObjectMapper objectMapper;

  public AgentEditableSupport(ObjectMapper objectMapper) {
    if (objectMapper == null) {
      throw new IllegalArgumentException("objectMapper must not be null");
    }
    this.objectMapper = objectMapper;
  }

  public String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  public String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      String trimmed = trimToNull(value);
      if (trimmed != null) {
        return trimmed;
      }
    }
    return null;
  }

  /** Rejects values that exceed the current PostgreSQL schema column limit after normalization. */
  public void validateMaxLength(String resource, String field, String value, int maxLength) {
    if (value != null && value.codePointCount(0, value.length()) > maxLength) {
      throw new AiValidationException(
          resource, field + " must not exceed " + maxLength + " characters");
    }
  }

  public void validateJsonArray(String json, String fieldName) {
    try {
      if (!objectMapper.readTree(json).isArray()) {
        throw new IllegalArgumentException(fieldName + " must be a JSON array");
      }
    } catch (IOException e) {
      throw new IllegalArgumentException(fieldName + " must be valid JSON", e);
    }
  }

  public void validateJsonObject(String json, String fieldName) {
    if (trimToNull(json) == null) {
      throw new IllegalArgumentException(fieldName + " must be a JSON object");
    }
    validateJsonObjectOrNull(json, fieldName);
  }

  public void validateJsonObjectOrNull(String json, String fieldName) {
    String trimmed = trimToNull(json);
    if (trimmed == null) {
      return;
    }
    try {
      if (!objectMapper.readTree(trimmed).isObject()) {
        throw new IllegalArgumentException(fieldName + " must be a JSON object");
      }
    } catch (IOException e) {
      throw new IllegalArgumentException(fieldName + " must be valid JSON", e);
    }
  }
}
