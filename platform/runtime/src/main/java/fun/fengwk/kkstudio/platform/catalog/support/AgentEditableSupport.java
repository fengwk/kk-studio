package fun.fengwk.kkstudio.platform.catalog.support;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.common.error.DomainException;

/**
 * AgentEditableSupport 负责标准化 agent 管理面的可编辑文本字段：去除环绕空白并拒绝超出列宽的值。
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
    String trimmed = value.strip();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /** 拒绝规范化后超出当前 PostgreSQL schema 列宽限制的值。 */
  public void validateMaxLength(String resource, String field, String value, int maxLength) {
    if (value != null && value.codePointCount(0, value.length()) > maxLength) {
      throw DomainException.validation(
          resource, field + " must not exceed " + maxLength + " characters");
    }
  }
}
