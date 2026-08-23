package fun.fengwk.kkstudio.web.events;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
 * PostgreSQL {@code canvas_document.version} 前进通知的进程内 fan-out。
 *
 * <p>{@code canvas_document} 行与 version 是事实源；初始订阅 cursor 从持久表权威读取，notification payload 携带提交后的
 * {@code canvasId:version}，只做轻量解析与 fan-out。共享 LISTEN loop 启动/重连成功时调用 {@link
 * #broadcastResync()}，覆盖断连期间不可恢复的通知。{@link #subscribe} 先注册 consumer 再读当前 version 返回，保证返回的 cursor
 * 之后的事件不因注册竞态丢失。
 */
@Slf4j
@Component
final class CanvasVersionHub implements CanvasVersionEventSource {

  static final String CHANNEL = "canvas_version";

  private final DataSource dataSource;
  private final Map<UUID, Set<Consumer<Event>>> subscribers = new ConcurrentHashMap<>();

  CanvasVersionHub(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  @Override
  public SourceSubscribed subscribe(UUID canvasId, Consumer<Event> consumer) {
    Objects.requireNonNull(canvasId, "canvasId");
    Objects.requireNonNull(consumer, "consumer");
    // add 与 map 条目创建在同一 compute 内原子完成；最后释放的 remove-if-empty 在 computeIfPresent 内原子完成，
    // 杜绝「新订阅者加入已移除集合」的 detached subscriber 竞态。
    subscribers.compute(
        canvasId,
        (id, existing) -> {
          Set<Consumer<Event>> canvasSubscribers =
              existing != null ? existing : new CopyOnWriteArraySet<>();
          canvasSubscribers.add(consumer);
          return canvasSubscribers;
        });
    try {
      long cursor = currentVersion(canvasId);
      return new SourceSubscribed(cursor, () -> release(canvasId, consumer));
    } catch (RuntimeException error) {
      release(canvasId, consumer);
      throw error;
    }
  }

  private void release(UUID canvasId, Consumer<Event> consumer) {
    subscribers.computeIfPresent(
        canvasId,
        (id, canvasSubscribers) -> {
          canvasSubscribers.remove(consumer);
          return canvasSubscribers.isEmpty() ? null : canvasSubscribers;
        });
  }

  void onNotification(String payload) {
    CanvasVersion notification = parseNotification(payload);
    if (notification == null) {
      return;
    }
    publish(notification.canvasId(), new Event(notification.version(), false));
  }

  private long currentVersion(UUID canvasId) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("select version from canvas_document where id = ?")) {
      statement.setObject(1, canvasId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          throw new IllegalArgumentException("unknown canvas: " + canvasId);
        }
        return result.getLong(1);
      }
    } catch (SQLException error) {
      throw new IllegalStateException("cannot read current canvas version", error);
    }
  }

  void broadcastResync() {
    subscribers.forEach((canvasId, ignored) -> publish(canvasId, new Event(null, true)));
  }

  private void publish(UUID canvasId, Event event) {
    Set<Consumer<Event>> canvasSubscribers = subscribers.get(canvasId);
    if (canvasSubscribers != null) {
      // 单个消费者回调异常只隔离该消费者，不阻断同资源其他消费者，也不杀死 LISTEN 循环。
      for (Consumer<Event> consumer : canvasSubscribers) {
        try {
          consumer.accept(event);
        } catch (RuntimeException error) {
          log.warn(
              "canvas version subscriber callback failed canvasId={}; skipping", canvasId, error);
        }
      }
    }
  }

  static CanvasVersion parseNotification(String payload) {
    if (payload == null) {
      return null;
    }
    int colon = payload.indexOf(':');
    if (colon <= 0 || colon != payload.lastIndexOf(':')) {
      return null;
    }
    String rawCanvasId = payload.substring(0, colon);
    String rawVersion = payload.substring(colon + 1);
    if (!rawVersion.matches("0|[1-9]\\d*")) {
      return null;
    }
    try {
      UUID canvasId = UUID.fromString(rawCanvasId);
      if (!canvasId.toString().equals(rawCanvasId)) {
        return null;
      }
      long version = Long.parseLong(rawVersion);
      return new CanvasVersion(canvasId, version);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  record CanvasVersion(UUID canvasId, long version) {}
}
