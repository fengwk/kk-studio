package fun.fengwk.kkstudio.harness.runtime.thread.command;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;

import java.util.Objects;

/** 携带一条标准 USER AgentMessage 的 typed USER_MESSAGE payload。 */
public record UserMessageCommandPayload(AgentMessage message) implements ThreadCommandPayload {

  public UserMessageCommandPayload {
    message = Objects.requireNonNull(message, "message");
    if (message.role() != AgentMessageRole.USER) {
      throw new IllegalArgumentException("USER_MESSAGE requires a USER AgentMessage");
    }
  }

  @Override
  public ThreadCommandType type() {
    return ThreadCommandType.USER_MESSAGE;
  }
}
