package fun.fengwk.kkstudio.platform.notification;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.common.id.Uuids;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * PostgreSQL NOTIFY 提示型失效信号的进程内 fan-out：按 key 定点分发，全局订阅额外接收全部合法通知。
 *
 * <p>事实提交后由生产方投递 canonical UUID payload；本 Hub 不复制领域事实、不做持久游标，只把「需要回读」的信号转成通用失效事件。空 payload
 * 表示来源事实已不存在，与畸形 payload、LISTEN 重连一样退化为全量 resync。
 *
 * <p>本类不创建连接或线程：统一 LISTEN 循环把 payload 交给 {@link #onNotification(String)}，建连/重连调用 {@link
 * #broadcastResync()}。单个消费者抛出的 {@link RuntimeException} 只隔离该消费者。
 *
 * <p>生产方按组合持有实例并同时定义自己的 channel 与 payload 来源；本类不携带任一领域的事实或 channel 名。
 */
@Slf4j
public final class NotificationInvalidationHub implements NotificationInvalidationSource {

  private final SubscriptionFanOut<Event> subscribers = new SubscriptionFanOut<>();

  @Override
  public Runnable subscribe(UUID key, Consumer<Event> consumer) {
    Objects.requireNonNull(consumer, "consumer");
    return subscribers.subscribe(key, consumer);
  }

  /** PostgreSQL LISTEN 通知入口。 */
  public void onNotification(String payload) {
    UUID key = parseCanonicalUuid(payload);
    if (key == null) {
      if (payload != null && !payload.isEmpty()) {
        log.warn("malformed invalidation notification payload={}; broadcasting resync", payload);
      }
      broadcastResync();
      return;
    }
    subscribers.publish(key, new Event.Changed(key));
  }

  /** 建连/重连或来源事实缺失：要求全部订阅整体回读。 */
  public void broadcastResync() {
    subscribers.broadcast(new Event.Resync());
  }

  private static UUID parseCanonicalUuid(String value) {
    if (value == null) {
      return null;
    }
    try {
      return Uuids.parseCanonical(value, "payload");
    } catch (IllegalArgumentException error) {
      return null;
    }
  }
}
