package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;

import java.util.Objects;
import java.util.UUID;

/**
 * 物化到历史的系统通知 Entry payload：{@link EntryType#NOTIFICATION}。
 *
 * <p>四个字段全部非空：稳定通知身份、通知类型、稳定来源 Thread 与 USER 角色的内容。历史 JSON 形态为 {@code
 * {notificationId,kind,sourceThreadId,message}}，与 command JSON 同形。
 */
public record NotificationPayload(
    UUID notificationId,
    NotificationKind kind,
    UUID sourceThreadId,
    AgentMessage message)
    implements EntryPayload {

  public NotificationPayload {
    notificationId = Objects.requireNonNull(notificationId, "notificationId");
    kind = Objects.requireNonNull(kind, "kind");
    sourceThreadId = Objects.requireNonNull(sourceThreadId, "sourceThreadId");
    message = Objects.requireNonNull(message, "message");
    if (message.role() != AgentMessageRole.USER) {
      throw new IllegalArgumentException("NOTIFICATION requires a USER AgentMessage");
    }
  }

  @Override
  public EntryType type() {
    return EntryType.NOTIFICATION;
  }
}
