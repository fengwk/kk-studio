package fun.fengwk.kkstudio.harness.infra.notification;

import fun.fengwk.kkstudio.harness.infra.realtime.RealtimeNotificationCodec;
import fun.fengwk.kkstudio.share.notification.EntityHint;
import fun.fengwk.kkstudio.share.notification.NotificationCodecs;
import fun.fengwk.kkstudio.share.notification.NotificationSignal;
import fun.fengwk.kkstudio.share.notification.NotificationTopic;
import fun.fengwk.kkstudio.share.notification.VersionHint;

/**
 * Harness 领域固定的通知 topic。写出方（事务内写入）与消费方（web 事件 hub / realtime source）共享这些常量。
 *
 * <p>Work、Thread version、执行树与待处理交互都是可合并的失效/唤醒提示：同事务内完全相同 payload 折叠为一次；权威事实始终回读数据库。 Realtime 是 live
 * overlay，不合并。
 */
public final class HarnessNotifications {

  /** 新的 Harness Work 就绪唤醒。 */
  public static final NotificationTopic<NotificationSignal> WORK_AVAILABLE =
      new NotificationTopic<>("harness.work.available", NotificationCodecs.SIGNAL, true);

  /** Thread version 前进：payload 为实体 id 与真实 version。 */
  public static final NotificationTopic<VersionHint> THREAD_VERSION =
      new NotificationTopic<>("harness.thread.version", NotificationCodecs.VERSION_HINT, true);

  /** 执行树失效：payload 为真实执行根 Thread id。 */
  public static final NotificationTopic<EntityHint> THREAD_TREE =
      new NotificationTopic<>("harness.thread.tree", NotificationCodecs.ENTITY_HINT, true);

  /** 待处理交互失效：payload 为真实执行根 Thread id；来源事实缺失时为空 payload，表示全局回读。 */
  public static final NotificationTopic<EntityHint> TOOL_INTERACTION =
      new NotificationTopic<>("harness.tool.interaction", NotificationCodecs.ENTITY_HINT, true);

  /** Realtime live overlay：事件与按 Thread 重同步。 */
  public static final NotificationTopic<RealtimeNotificationCodec.Envelope> REALTIME =
      new NotificationTopic<>("harness.realtime", new RealtimeNotificationCodec(), false);

  private HarnessNotifications() {}
}
