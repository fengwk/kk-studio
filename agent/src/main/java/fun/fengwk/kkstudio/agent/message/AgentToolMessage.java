package fun.fengwk.kkstudio.agent.message;

import fun.fengwk.kkstudio.agent.session.payload.ToolContent;

import java.util.List;

/**
 * 工具执行结果消息。
 *
 * @author fengwk
 */
public record AgentToolMessage(
    String id, String toolName, List<ToolContent> contents, boolean error) implements AgentMessage {

  public AgentToolMessage {
    contents = contents == null ? List.of() : List.copyOf(contents);
  }
}
