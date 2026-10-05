package fun.fengwk.kkstudio.harness.runtime.thread.command;

import fun.fengwk.kkstudio.harness.runtime.history.NotificationKind;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;

import java.util.Objects;
import java.util.UUID;

/**
 * 系统通知 command payload：{@link ThreadCommandType#NOTIFICATION}。
 *
 * <p>四个字段全部非空：稳定通知身份、通知类型、稳定来源 Thread 与 USER 角色的内容。command JSON 形态为 {@code
 * {notificationId,kind,sourceThreadId,message}}，与历史 payload 同形。普通 HTTP 客户端不允许提交该类型。
 */
public record NotificationCommandPayload(
    UUID notificationId, NotificationKind kind, UUID sourceThreadId, AgentMessage message)
    implements ThreadCommandPayload {

  public NotificationCommandPayload {
    notificationId = Objects.requireNonNull(notificationId, "notificationId");
    kind = Objects.requireNonNull(kind, "kind");
    sourceThreadId = Objects.requireNonNull(sourceThreadId, "sourceThreadId");
    message = Objects.requireNonNull(message, "message");
    if (message.role() != AgentMessageRole.USER) {
      throw new IllegalArgumentException("NOTIFICATION requires a USER AgentMessage");
    }
  }

  @Override
  public ThreadCommandType type() {
    return ThreadCommandType.NOTIFICATION;
  }
}
