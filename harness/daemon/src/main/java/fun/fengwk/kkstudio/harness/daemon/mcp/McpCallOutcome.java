package fun.fengwk.kkstudio.harness.daemon.mcp;

import java.util.Objects;

/**
 * MCP 工具调用的模型可见结果。
 *
 * <p>{@code isError} 保留上游 MCP 结果语义；{@code text} 为文本内容或结构化 JSON 的原始文本（不会泄漏 secrets）。
 */
public record McpCallOutcome(boolean isError, String text) {

  public McpCallOutcome {
    text = Objects.requireNonNull(text, "text");
  }
}
