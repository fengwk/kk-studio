package fun.fengwk.kkstudio.harness.model.provider;

import java.util.Objects;

/** 已完成语义消息到特定 Provider 请求消息的转换结果。 */
public record ProviderMessage(ProviderMessageRole role, String content, String toolCallId) {

  public ProviderMessage {
    role = Objects.requireNonNull(role, "role");
    if (content == null) {
      throw new IllegalArgumentException("content must not be null");
    }
    if (role == ProviderMessageRole.TOOL && (toolCallId == null || toolCallId.isBlank())) {
      throw new IllegalArgumentException("toolCallId must not be blank for TOOL messages");
    }
    if (role != ProviderMessageRole.TOOL && toolCallId != null) {
      throw new IllegalArgumentException("toolCallId is only allowed for TOOL messages");
    }
  }
}
