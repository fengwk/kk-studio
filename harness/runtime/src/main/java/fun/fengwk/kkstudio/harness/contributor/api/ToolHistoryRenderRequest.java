package fun.fengwk.kkstudio.harness.contributor.api;

import fun.fengwk.kkstudio.harness.tool.ToolCall;

import java.util.Objects;

/**
 * 一次历史 Tool 调用的语义渲染输入：已按 schema 归一化的 {@link ToolCall} 与调用冻结时的 Environment 名。
 *
 * <p>该输入刻意最小：不含 durable Entry、callIndex 或 Provider 协议结构。{@code environmentName} 为 null 表示该调用不绑定
 * Environment（工具不要求环境，或冻结时 branch 尚未选择 Environment）。
 */
public record ToolHistoryRenderRequest(ToolCall call, String environmentName) {

  public ToolHistoryRenderRequest {
    call = Objects.requireNonNull(call, "call");
    if (environmentName != null && environmentName.isBlank()) {
      throw new IllegalArgumentException("environmentName must be null or non-blank");
    }
  }
}
