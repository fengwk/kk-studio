package fun.fengwk.kkstudio.web.events;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * PostgreSQL NOTIFY 提示型失效信号的进程内 fan-out：按 key 定点分发，全局订阅额外接收全部合法通知。
 *
 * <p>事实提交后由数据库触发器（或租约到期扫描）投递 canonical UUID payload；本 Hub 不复制领域事实、不做持久游标，只把 「需要回读」的信号转成 {@link
 * InvalidationEventSource.Event}。空 payload 表示来源事实已不存在，与畸形 payload、LISTEN 重连一样退化为全量 resync。
 */
@Slf4j
class NotificationInvalidationHub implements InvalidationEventSource {

  private final Map<UUID, Set<Consumer<Event>>> keyedSubscribers = new ConcurrentHashMap<>();
  private final Set<Consumer<Event>> globalSubscribers = new CopyOnWriteArraySet<>();

  @Override
  public SourceSubscribed subscribe(UUID key, Consumer<Event> consumer) {
    Objects.requireNonNull(consumer, "consumer");
    if (key == null) {
      globalSubscribers.add(consumer);
      return new SourceSubscribed(0L, () -> globalSubscribers.remove(consumer));
    }
    // add 与集合创建在同一 compute 内原子完成；最后释放的 remove-if-empty 也原子完成，不留下 detached 订阅者。
    keyedSubscribers.compute(
        key,
        (id, existing) -> {
          Set<Consumer<Event>> subscribers =
              existing != null ? existing : new CopyOnWriteArraySet<>();
          subscribers.add(consumer);
          return subscribers;
        });
    return new SourceSubscribed(0L, () -> release(key, consumer));
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
    publish(Event.changed(key));
  }

  public void broadcastResync() {
    publish(Event.fullResync());
  }

  private void release(UUID key, Consumer<Event> consumer) {
    keyedSubscribers.computeIfPresent(
        key,
        (id, subscribers) -> {
          subscribers.remove(consumer);
          return subscribers.isEmpty() ? null : subscribers;
        });
  }

  private void publish(Event event) {
    // 单个消费者异常只隔离该消费者，不阻断同资源其他消费者，也不杀死 LISTEN 循环。
    for (Consumer<Event> consumer : globalSubscribers) {
      deliver(consumer, event);
    }
    if (event.resync()) {
      for (Set<Consumer<Event>> subscribers : keyedSubscribers.values()) {
        for (Consumer<Event> consumer : subscribers) {
          deliver(consumer, event);
        }
      }
      return;
    }
    Set<Consumer<Event>> subscribers = keyedSubscribers.get(event.key());
    if (subscribers != null) {
      for (Consumer<Event> consumer : subscribers) {
        deliver(consumer, event);
      }
    }
  }

  private static void deliver(Consumer<Event> consumer, Event event) {
    try {
      consumer.accept(event);
    } catch (RuntimeException error) {
      log.warn("invalidation subscriber callback failed; skipping", error);
    }
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
