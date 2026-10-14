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
 * PostgreSQL Thread version 通知的进程内 fan-out。
 *
 * <p>初始订阅 cursor 从持久表权威读取；notification payload 携带提交后的 {@code threadId:version}，合法 payload 只做轻量解析与
 * fan-out，畸形 payload 广播 resync。共享 LISTEN loop 启动/重连成功时也调用 {@link
 * #broadcastResync()}，覆盖断连期间不可恢复的通知。{@link #subscribe} 先注册 consumer 再读当前 version 返回，保证返回的 cursor
 * 之后的事件不因注册竞态丢失。
 */
@Slf4j
@Component
final class ThreadVersionHub implements ThreadVersionEventSource {

  static final String CHANNEL = "harness_thread_version";

  private final DataSource dataSource;
  private final SubscriptionFanOut<Event> subscribers = new SubscriptionFanOut<>();

  ThreadVersionHub(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  @Override
  public SourceSubscribed subscribe(UUID threadId, Consumer<Event> consumer) {
    Objects.requireNonNull(threadId, "threadId");
    // 先注册 consumer 再读当前 version 返回，保证返回的 cursor 之后的事件不因注册竞态丢失。
    Runnable unsubscribe = subscribers.subscribe(threadId, consumer);
    try {
      return new SourceSubscribed(currentVersion(threadId), unsubscribe::run);
    } catch (RuntimeException | Error error) {
      // 权威查询失败必须释放刚建立的注册，并保留原始异常（Error 同样不得吞）。
      unsubscribe.run();
      throw error;
    }
  }

  void onNotification(String payload) {
    ThreadVersion notification = parseNotification(payload);
    if (notification == null) {
      log.warn("malformed thread version notification payload={}; broadcasting resync", payload);
      broadcastResync();
      return;
    }
    subscribers.publish(notification.threadId(), new Event(notification.version(), false));
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
    subscribers.broadcast(new Event(null, true));
  }

  static ThreadVersion parseNotification(String payload) {
    if (payload == null) {
      return null;
    }
    int colon = payload.indexOf(':');
    if (colon <= 0 || colon != payload.lastIndexOf(':')) {
      return null;
    }
    String rawThreadId = payload.substring(0, colon);
    String version = payload.substring(colon + 1);
    if (!version.matches("0|[1-9]\\d*")) {
      return null;
    }
    try {
      UUID threadId = UUID.fromString(rawThreadId);
      if (!threadId.toString().equals(rawThreadId)) {
        return null;
      }
      Long.parseLong(version);
      return new ThreadVersion(threadId, version);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  record ThreadVersion(UUID threadId, String version) {}
}
