package fun.fengwk.kkstudio.platform.harness.oneshot;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** 一次 root join 的 durable 身份；await 只观察该 invocation 的固定匹配结果。 */
public record OneShotTicket(UUID threadId, UUID invocationId) {
  public OneShotTicket {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(invocationId, "invocationId");
  }

  /** invocationId 确定性派生；重启后只需持久化 threadId 即能恢复同一 ticket。 */
  public static OneShotTicket forThread(UUID threadId) {
    Objects.requireNonNull(threadId, "threadId");
    return new OneShotTicket(
        threadId,
        UUID.nameUUIDFromBytes(
            ("kk-studio/harness/oneshot/" + threadId).getBytes(StandardCharsets.UTF_8)));
  }
}
