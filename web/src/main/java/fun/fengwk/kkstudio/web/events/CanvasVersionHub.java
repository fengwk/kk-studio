package fun.fengwk.kkstudio.web.events;

import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
 * <p>{@code canvas_document} 行与 version 是事实源；本 hub 只负责 LISTEN/NOTIFY 唤醒后重新读取当前 version 再通知
 * 订阅者（payload 里携带的 version 只作为可恢复性提示）。LISTEN 启动/重连成功时广播 resync，因为断连期间的通知 不可恢复。{@link #subscribe}
 * 先注册 consumer 再读当前 version 返回，保证返回的 cursor 之后的事件不因注册竞态丢失。
 */
@Slf4j
@Component
final class CanvasVersionHub implements SmartLifecycle, CanvasVersionEventSource {

  static final String CHANNEL = "canvas_version";

  private final DataSource dataSource;
  private final Map<UUID, Set<Consumer<Event>>> subscribers = new ConcurrentHashMap<>();
  private volatile boolean running;
  private volatile Thread listenerThread;

  CanvasVersionHub(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  @Override
  public SourceSubscribed subscribe(UUID canvasId, Consumer<Event> consumer) {
    Objects.requireNonNull(canvasId, "canvasId");
    Objects.requireNonNull(consumer, "consumer");
    Set<Consumer<Event>> canvasSubscribers =
        subscribers.computeIfAbsent(canvasId, ignored -> new CopyOnWriteArraySet<>());
    canvasSubscribers.add(consumer);
    try {
      long cursor = currentVersion(canvasId);
      return new SourceSubscribed(
          cursor,
          () -> {
            Set<Consumer<Event>> currentSubscribers = subscribers.get(canvasId);
            if (currentSubscribers != null) {
              currentSubscribers.remove(consumer);
              if (currentSubscribers.isEmpty()) {
                subscribers.remove(canvasId, currentSubscribers);
              }
            }
          });
    } catch (RuntimeException error) {
      canvasSubscribers.remove(consumer);
      if (canvasSubscribers.isEmpty()) {
        subscribers.remove(canvasId, canvasSubscribers);
      }
      throw error;
    }
  }

  @Override
  public void start() {
    if (running) {
      return;
    }
    running = true;
    Thread thread = new Thread(this::listen, "canvas-version-listen");
    thread.setDaemon(true);
    listenerThread = thread;
    thread.start();
  }

  @Override
  public void stop() {
    running = false;
    Thread thread = listenerThread;
    if (thread != null) {
      thread.interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return Integer.MIN_VALUE;
  }

  private void listen() {
    while (running) {
      try (Connection connection = dataSource.getConnection();
          Statement statement = connection.createStatement()) {
        PGConnection pgConnection = connection.unwrap(PGConnection.class);
        statement.execute("LISTEN " + CHANNEL);
        broadcastResync();
        while (running) {
          PGNotification[] notifications = pgConnection.getNotifications(5_000);
          if (notifications != null) {
            for (PGNotification notification : notifications) {
              publishCurrentVersion(connection, notification.getParameter());
            }
          }
        }
      } catch (SQLException | RuntimeException error) {
        if (running) {
          log.warn("canvas version LISTEN connection lost; reconnecting", error);
          sleep();
        }
      }
    }
  }

  private void publishCurrentVersion(Connection connection, String payload) {
    UUID canvasId = parseCanvasId(payload);
    if (canvasId == null) {
      return;
    }
    try (PreparedStatement statement =
        connection.prepareStatement("select version from canvas_document where id = ?")) {
      statement.setObject(1, canvasId);
      try (ResultSet result = statement.executeQuery()) {
        if (result.next()) {
          publish(canvasId, new Event(result.getLong(1), false));
        }
      }
    } catch (SQLException error) {
      log.warn("cannot read canvas version after notify canvasId={}", canvasId, error);
    }
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
      canvasSubscribers.forEach(consumer -> consumer.accept(event));
    }
  }

  static UUID parseCanvasId(String payload) {
    if (payload == null) {
      return null;
    }
    int colon = payload.indexOf(':');
    if (colon < 0) {
      return null;
    }
    try {
      return UUID.fromString(payload.substring(0, colon));
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  private static void sleep() {
    try {
      Thread.sleep(1_000);
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
  }
}
