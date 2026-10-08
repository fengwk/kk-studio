package fun.fengwk.kkstudio.web.events;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.share.notification.VersionHint;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * Thread durable version 通知的进程内 fan-out。
 *
 * <p>{@code harness_thread.version} 是事实源：初始订阅 cursor 从持久表权威读取，通知 payload 携带实体 id 与提交后的真实 version，
 * 只做 fan-out。畸形 payload 由总线 topic codec 拒绝并退化为本 hub 的 resync；总线建连/重连成功时同样触发 {@link
 * #broadcastResync()}，覆盖断连期间不可恢复的通知。{@link #subscribe} 先注册 consumer 再读当前 version 返回，保证返回的 cursor
 * 之后的事件不因注册竞态丢失。
 */
@Slf4j
@Component
final class ThreadVersionHub implements ThreadVersionEventSource {

  private final DataSource dataSource;
  private final Map<UUID, Set<Consumer<Event>>> subscribers = new ConcurrentHashMap<>();

  ThreadVersionHub(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  @Override
  public SourceSubscribed subscribe(UUID threadId, Consumer<Event> consumer) {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(consumer, "consumer");
    // add 与 map 条目创建在同一 compute 内原子完成；最后释放的 remove-if-empty 在 computeIfPresent 内原子完成，
    // 杜绝「新订阅者加入已移除集合」的 detached subscriber 竞态。
    subscribers.compute(
        threadId,
        (id, existing) -> {
          Set<Consumer<Event>> threadSubscribers =
              existing != null ? existing : new CopyOnWriteArraySet<>();
          threadSubscribers.add(consumer);
          return threadSubscribers;
        });
    try {
      long cursor = currentVersion(threadId);
      return new SourceSubscribed(cursor, () -> release(threadId, consumer));
    } catch (RuntimeException error) {
      release(threadId, consumer);
      throw error;
    }
  }

  private void release(UUID threadId, Consumer<Event> consumer) {
    subscribers.computeIfPresent(
        threadId,
        (id, threadSubscribers) -> {
          threadSubscribers.remove(consumer);
          return threadSubscribers.isEmpty() ? null : threadSubscribers;
        });
  }

  /** 交付一条已由总线解码的 version hint；payload 不承载权威版本，仅提示该 Thread 需要回读。 */
  void onNotification(VersionHint hint) {
    Objects.requireNonNull(hint, "hint");
    publish(hint.entityId(), new Event(Long.toString(hint.version()), false));
  }

  private long currentVersion(UUID threadId) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("select version from harness_thread where id = ?")) {
      statement.setObject(1, threadId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          throw new IllegalArgumentException("unknown thread: " + threadId);
        }
        return result.getLong(1);
      }
    } catch (SQLException error) {
      throw new IllegalStateException("cannot read current thread version", error);
    }
  }

  void broadcastResync() {
    subscribers.forEach((threadId, ignored) -> publish(threadId, new Event(null, true)));
  }

  private void publish(UUID threadId, Event event) {
    Set<Consumer<Event>> threadSubscribers = subscribers.get(threadId);
    if (threadSubscribers != null) {
      // 单个消费者回调异常只隔离该消费者，不阻断同资源其他消费者，也不中断后续通知投递。
      for (Consumer<Event> consumer : threadSubscribers) {
        try {
          consumer.accept(event);
        } catch (RuntimeException error) {
          log.warn(
              "thread version subscriber callback failed threadId={}; skipping", threadId, error);
        }
      }
    }
  }
}
