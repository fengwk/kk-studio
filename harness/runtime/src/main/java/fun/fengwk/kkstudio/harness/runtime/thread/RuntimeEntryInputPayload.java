package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.kernel.thread.ThreadInputPayload;
import fun.fengwk.kkstudio.harness.kernel.thread.ThreadInputType;
import fun.fengwk.kkstudio.harness.runtime.entry.CustomMessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.RuntimeEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;

import java.util.Objects;

/** 消息命令携带的最终 Entry payload；harvest 时可直接追加到 Entry Tree。 */
public record RuntimeEntryInputPayload(ThreadInputType type, RuntimeEntryPayload payload)
    implements ThreadInputPayload {

  public RuntimeEntryInputPayload {
    type = Objects.requireNonNull(type, "type");
    payload = Objects.requireNonNull(payload, "payload");
    if (type == ThreadInputType.USER_MESSAGE) {
      if (!(payload instanceof MessageEntryPayload message)
          || message.message().role() != AgentMessageRole.USER) {
        throw new IllegalArgumentException("USER_MESSAGE requires a user MessageEntryPayload");
      }
    } else if (type == ThreadInputType.CUSTOM_MESSAGE) {
      if (!(payload instanceof CustomMessageEntryPayload)) {
        throw new IllegalArgumentException("CUSTOM_MESSAGE requires a CustomMessageEntryPayload");
      }
    } else {
      throw new IllegalArgumentException("runtime entry payload requires a message input type");
    }
  }
}
