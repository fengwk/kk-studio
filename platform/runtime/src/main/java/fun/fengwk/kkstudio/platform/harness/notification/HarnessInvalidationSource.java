package fun.fengwk.kkstudio.platform.harness.notification;

import fun.fengwk.kkstudio.harness.api.HarnessChangeSubscription;
import fun.fengwk.kkstudio.harness.api.HarnessInvalidationChanges;
import fun.fengwk.kkstudio.platform.notification.NotificationInvalidationHub;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Harness 提示型失效的公开订阅源：执行树与待处理交互共用同一 PostgreSQL 通知协议与分发，只有 channel 绑定不同。
 *
 * <p>本类不创建连接或线程：统一 LISTEN 循环把 payload 交给 {@link #onNotification(String)}，建连/重连调用 {@link
 * #broadcastResync()}。空/畸形 payload 退化为全量 resync；提示是有损信号，权威事实始终回读数据库。
 */
public final class HarnessInvalidationSource implements HarnessInvalidationChanges {

  private final NotificationInvalidationHub<Event> hub =
      new NotificationInvalidationHub<>(Event.Changed::new, Event.Resync::new);

  @Override
  public HarnessChangeSubscription subscribe(UUID key, Consumer<Event> consumer) {
    Objects.requireNonNull(consumer, "consumer");
    return new HarnessChangeSubscription(0L, hub.subscribe(key, consumer));
  }

  /** 由统一 PostgreSQL listener 交付一条 harness 失效通知。 */
  public void onNotification(String payload) {
    hub.onNotification(payload);
  }

  /** 建连/重连或来源事实缺失：要求全部订阅整体回读。 */
  public void broadcastResync() {
    hub.broadcastResync();
  }
}
