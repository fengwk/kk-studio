package fun.fengwk.kkstudio.web.events;

import lombok.extern.slf4j.Slf4j;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * PostgreSQL NOTIFY 提示型失效信号的进程内 fan-out：按 key 定点分发，全局订阅额外接收全部合法通知。
 *
 * <p>事实提交后由数据库触发器投递 canonical UUID payload；本 Hub 不复制领域事实、不做持久游标，只把「需要回读」的信号转成 {@link
 * InvalidationEventSource.Event}。空 payload 表示来源事实已不存在，与畸形 payload、LISTEN 重连一样退化为全量 resync。
 */
@Slf4j
class NotificationInvalidationHub implements InvalidationEventSource {

  private final SubscriptionFanOut<Event> subscribers = new SubscriptionFanOut<>();

  @Override
  public SourceSubscribed subscribe(UUID key, Consumer<Event> consumer) {
    Runnable unsubscribe = subscribers.subscribe(key, consumer);
    return new SourceSubscribed(0L, unsubscribe::run);
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
    subscribers.publish(key, Event.changed(key));
  }

  public void broadcastResync() {
    subscribers.broadcast(Event.fullResync());
  }

  private static UUID parseCanonicalUuid(String value) {
    if (value == null) {
      return null;
    }
    try {
      UUID parsed = UUID.fromString(value);
      return parsed.toString().equals(value) ? parsed : null;
    } catch (IllegalArgumentException error) {
      return null;
    }
  }
}
