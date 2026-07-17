package fun.fengwk.kkstudio.harness.agent;

import fun.fengwk.kkstudio.harness.tool.ToolCall;

import java.util.List;

/** 一个已完成的 Assistant 语义消息。 */
public record AgentAssistantMessage(String text, String thinking, List<ToolCall> toolCalls) {

  public AgentAssistantMessage {
    text = text == null ? "" : text;
    thinking = thinking == null ? "" : thinking;
    toolCalls = List.copyOf(toolCalls == null ? List.of() : toolCalls);
  }
}
