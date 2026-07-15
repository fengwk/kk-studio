package fun.fengwk.kkstudio.harness.runtime.control;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryType;
import java.util.Objects;

/** 复用 SessionEntryJsonCodec 无损编码/解码完整 USER AgentMessage，拒绝非 USER 与损坏 payload。 */
public final class ControlMessageCodec {

  private final SessionEntryJsonCodec entryCodec = new SessionEntryJsonCodec();

  /** 把完整 USER {@link AgentMessage} 编码为稳定的 JSON 字符串。 */
  public String encode(AgentMessage message) {
    Objects.requireNonNull(message, "message");
    requireUser(message);
    return entryCodec.encode(new MessageEntryPayload(message));
  }

  /** 从 JSON 字符串解码 USER {@link AgentMessage}；任何损坏或非 USER 都失败。 */
  public AgentMessage decode(String json) {
    if (json == null) {
      throw new IllegalArgumentException("control message json must not be null");
    }
    MessageEntryPayload payload;
    try {
      payload = (MessageEntryPayload) entryCodec.decode(SessionEntryType.MESSAGE, json);
    } catch (ClassCastException exception) {
      throw new IllegalArgumentException("control message payload type mismatch", exception);
    }
    requireUser(payload.message());
    return payload.message();
  }

  private static void requireUser(AgentMessage message) {
    Objects.requireNonNull(message, "message");
    if (message.role() != AgentMessageRole.USER) {
      throw new IllegalArgumentException(
          "control message must have USER role but was " + message.role());
    }
  }
}