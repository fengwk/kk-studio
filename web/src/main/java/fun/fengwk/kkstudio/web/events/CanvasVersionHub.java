package fun.fengwk.kkstudio.web.events;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * PostgreSQL {@code canvas_document.revision} 前进通知的进程内 fan-out。
 *
 * <p>{@code canvas_document} 行与 revision 是事实源；初始订阅 cursor 从持久表权威读取，notification payload 携带提交后的
 * {@code canvasId:revision}，合法 payload 只做轻量解析与 fan-out，畸形 payload 广播 resync。共享 LISTEN loop
 * 启动/重连成功时也调用 {@link #broadcastResync()}，覆盖断连期间不可恢复的通知。{@link #subscribe} 先注册 consumer 再读当前
 * revision 返回，保证返回的 cursor 之后的事件不因注册竞态丢失。
 *
 * <p>数据库侧由 {@code PostgresqlCanvasStore} 在写事务内沿 {@code canvas_revision} 通道发出提示；两者是同一坐标系： 通道名与列名都使用
 * revision，不保留 version 别名。
 */
@Slf4j
@Component
final class CanvasVersionHub implements CanvasVersionEventSource {

  static final String CHANNEL = "canvas_revision";

  private final DataSource dataSource;
  private final SubscriptionFanOut<Event> subscribers = new SubscriptionFanOut<>();

  CanvasVersionHub(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  @Override
  public SourceSubscribed subscribe(UUID canvasId, Consumer<Event> consumer) {
    Objects.requireNonNull(canvasId, "canvasId");
    // 先注册 consumer 再读当前 revision 返回，保证返回的 cursor 之后的事件不因注册竞态丢失。
    Runnable unsubscribe = subscribers.subscribe(canvasId, consumer);
    try {
      return new SourceSubscribed(currentRevision(canvasId), unsubscribe::run);
    } catch (RuntimeException | Error error) {
      // 权威查询失败必须释放刚建立的注册，并保留原始异常（Error 同样不得吞）。
      unsubscribe.run();
      throw error;
    }
  }

  void onNotification(String payload) {
    CanvasRevision notification = parseNotification(payload);
    if (notification == null) {
      log.warn("malformed canvas revision notification payload={}; broadcasting resync", payload);
      broadcastResync();
      return;
    }
    subscribers.publish(notification.canvasId(), new Event(notification.revision(), false));
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
    subscribers.broadcast(new Event(null, true));
  }

  static CanvasRevision parseNotification(String payload) {
    if (payload == null) {
      return null;
    }
    int colon = payload.indexOf(':');
    if (colon <= 0 || colon != payload.lastIndexOf(':')) {
      return null;
    }
    String rawCanvasId = payload.substring(0, colon);
    String rawRevision = payload.substring(colon + 1);
    if (!rawRevision.matches("0|[1-9]\\d*")) {
      return null;
    }
    try {
      UUID canvasId = UUID.fromString(rawCanvasId);
      if (!canvasId.toString().equals(rawCanvasId)) {
        return null;
      }
      long revision = Long.parseLong(rawRevision);
      return new CanvasRevision(canvasId, revision);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  record CanvasRevision(UUID canvasId, long revision) {}
}
