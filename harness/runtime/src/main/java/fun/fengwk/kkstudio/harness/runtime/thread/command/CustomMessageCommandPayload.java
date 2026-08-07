package fun.fengwk.kkstudio.harness.runtime.thread.command;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;

import java.util.Objects;

/** 限定为 SYSTEM 或 USER 消息的 typed CUSTOM_MESSAGE payload。 */
public record CustomMessageCommandPayload(AgentMessage message) implements ThreadCommandPayload {

  public CustomMessageCommandPayload {
    message = Objects.requireNonNull(message, "message");
    if (message.role() != AgentMessageRole.SYSTEM && message.role() != AgentMessageRole.USER) {
      throw new IllegalArgumentException("CUSTOM_MESSAGE requires a SYSTEM or USER AgentMessage");
    }
  }

  @Override
  public ThreadCommandType type() {
    return ThreadCommandType.CUSTOM_MESSAGE;
  }
}
