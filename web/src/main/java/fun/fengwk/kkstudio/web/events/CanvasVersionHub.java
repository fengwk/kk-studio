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
 * Canvas {@code canvas_document.revision} 前进通知的进程内 fan-out。
 *
 * <p>{@code canvas_document} 行与 revision 是事实源：初始订阅 cursor 从持久表权威读取，通知 payload 携带实体 id 与提交后的真实
 * revision，只做 fan-out。畸形 payload 由总线 topic codec 拒绝并退化为本 hub 的 resync；总线建连/重连成功时同样触发 {@link
 * #broadcastResync()}，覆盖断连期间不可恢复的通知。{@link #subscribe} 先注册 consumer 再读当前 revision 返回，保证返回的 cursor
 * 之后的事件不因注册竞态丢失。
 *
 * <p>数据库侧由 {@code CanvasChangeNotifier} 在写事务内经 {@code CanvasNotifications.REVISION} 发布提示；两者是同一坐标系：
 * 通道 topic 名与列名都使用 revision，不保留 version 别名。
 */
@Slf4j
@Component
final class CanvasVersionHub implements CanvasVersionEventSource {

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
      long cursor = currentRevision(canvasId);
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

  /** 交付一条已由总线解码的 revision hint；payload 不承载权威 revision，仅提示该 Canvas 需要回读。 */
  void onNotification(VersionHint hint) {
    Objects.requireNonNull(hint, "hint");
    publish(hint.entityId(), new Event(hint.version(), false));
  }

  private long currentRevision(UUID canvasId) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("select revision from canvas_document where id = ?")) {
      statement.setObject(1, canvasId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          throw new IllegalArgumentException("unknown canvas: " + canvasId);
        }
        return result.getLong(1);
      }
    } catch (SQLException error) {
      throw new IllegalStateException("cannot read current canvas revision", error);
    }
  }

  void broadcastResync() {
    subscribers.forEach((canvasId, ignored) -> publish(canvasId, new Event(null, true)));
  }

  private void publish(UUID canvasId, Event event) {
    Set<Consumer<Event>> canvasSubscribers = subscribers.get(canvasId);
    if (canvasSubscribers != null) {
      // 单个消费者回调异常只隔离该消费者，不阻断同资源其他消费者，也不中断后续通知投递。
      for (Consumer<Event> consumer : canvasSubscribers) {
        try {
          consumer.accept(event);
        } catch (RuntimeException error) {
          log.warn(
              "canvas revision subscriber callback failed canvasId={}; skipping", canvasId, error);
        }
      }
    }
  }
}
