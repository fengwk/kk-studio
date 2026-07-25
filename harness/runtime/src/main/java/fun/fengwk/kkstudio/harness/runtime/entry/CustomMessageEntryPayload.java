package fun.fengwk.kkstudio.harness.runtime.entry;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;

import java.util.Objects;

/** 扩展显式要求进入模型上下文的消息 Entry。 */
public record CustomMessageEntryPayload(AgentMessage message) implements RuntimeEntryPayload {

  public CustomMessageEntryPayload {
    Objects.requireNonNull(message, "message");
  }

  @Override
  public EntryType type() {
    return EntryType.CUSTOM_MESSAGE;
  }
}
