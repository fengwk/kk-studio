package fun.fengwk.kkstudio.platform.notification;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.common.id.Uuids;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * PostgreSQL NOTIFY 提示型失效信号的进程内 fan-out：按 key 定点分发，全局订阅额外接收全部合法通知。
 *
 * <p>事实提交后由写入口投递 canonical UUID payload；本 Hub 不复制领域事实、不做持久游标，只把「需要回读」的信号转成子类给定的事件 类型。空 payload
 * 表示来源事实已不存在，与畸形 payload、LISTEN 重连一样退化为全量 resync。
 *
 * <p>本类不创建连接或线程：统一 LISTEN 循环把 payload 交给 {@link #onNotification(String)}，建连/重连调用 {@link
 * #broadcastResync()}。单个消费者抛出的 {@link RuntimeException} 只隔离该消费者。
 *
 * <p>使用方按组合持有一个实例（Harness 失效源与 web 的环境失效 hub 各自包装一个），从而把领域事件的构造与协议解析分开。
 */
@Slf4j
public final class NotificationInvalidationHub<E> {

  private final Function<UUID, E> changedEvent;
  private final Supplier<E> resyncEvent;
  private final SubscriptionFanOut<E> subscribers = new SubscriptionFanOut<>();

  /**
   * @param changedEvent 按 key 构造定点失效事件；{@code resyncEvent} 构造全量回读事件
   */
  public NotificationInvalidationHub(Function<UUID, E> changedEvent, Supplier<E> resyncEvent) {
    this.changedEvent = Objects.requireNonNull(changedEvent, "changedEvent");
    this.resyncEvent = Objects.requireNonNull(resyncEvent, "resyncEvent");
  }

  /**
   * 注册订阅并返回幂等注销动作。
   *
   * @param key 资源 key；{@code null} 表示全局订阅
   * @param consumer 接收失效事件；不得阻塞
   */
  public Runnable subscribe(UUID key, Consumer<E> consumer) {
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
    subscribers.publish(key, changedEvent.apply(key));
  }

  /** 建连/重连或来源事实缺失：要求全部订阅整体回读。 */
  public void broadcastResync() {
    subscribers.broadcast(resyncEvent.get());
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
