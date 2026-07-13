package fun.fengwk.kkstudio.harness.runtime.session;

import java.util.Objects;

/** 扩展显式要求进入模型上下文的消息。 */
public record CustomMessageEntryPayload(AgentMessage message) implements SessionEntryPayload {
  public CustomMessageEntryPayload {
    message = Objects.requireNonNull(message, "message");
  }

  @Override
  public SessionEntryType type() {
    return SessionEntryType.CUSTOM_MESSAGE;
  }
}
