package fun.fengwk.kkstudio.harness.runtime.configuration;

import java.util.Objects;

/**
 * 冻结的 Agent 身份与 system prompt 快照。
 *
 * <p>{@code definitionId} 为正整数；{@code name} 非空白；{@code systemPrompt} 允许 null，统一规范化为空字符串以便 后续系统拼接。
 */
public record AgentSnapshot(long definitionId, String name, String systemPrompt) {

  public AgentSnapshot {
    if (definitionId <= 0) {
      throw new IllegalArgumentException("definitionId must be positive");
    }
    name = requireNonBlank(name, "name");
    systemPrompt = systemPrompt == null ? "" : systemPrompt;
  }

  private static String requireNonBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
