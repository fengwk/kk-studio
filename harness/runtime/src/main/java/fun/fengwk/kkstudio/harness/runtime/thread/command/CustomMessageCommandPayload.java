package fun.fengwk.kkstudio.harness.runtime.thread.command;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;

import java.util.Objects;

/** Typed CUSTOM_MESSAGE payload restricted to SYSTEM or USER messages. */
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
