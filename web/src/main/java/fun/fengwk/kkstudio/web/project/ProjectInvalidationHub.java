package fun.fengwk.kkstudio.web.project;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * Project 变更失效信号分发器。
 *
 * <p>数据库 trigger 在 Project/Issue 事实提交后通过 PostgreSQL {@code LISTEN/NOTIFY} 发送 Project id。此 Hub
 * 不复制领域事实；合法 payload 只作为定向刷新提示，非法 payload 与 LISTEN 重连都退化为全量重同步。
 */
@Slf4j
@Component
public class ProjectInvalidationHub {

  public static final String CHANNEL = "project_issue_changed";

  private final Set<Subscriber> subscribers = new CopyOnWriteArraySet<>();

  public AutoCloseable subscribe(Consumer<UUID> consumer, Runnable resync) {
    Subscriber subscriber =
        new Subscriber(
            Objects.requireNonNull(consumer, "consumer"), Objects.requireNonNull(resync, "resync"));
    subscribers.add(subscriber);
    return () -> subscribers.remove(subscriber);
  }

  /** PostgreSQL LISTEN 通知入口。payload 只用于缩小浏览器快照刷新范围。 */
  public void onNotification(String payload) {
    UUID projectId = parseCanonicalUuid(payload);
    if (projectId == null) {
      broadcastResync();
      return;
    }
    for (Subscriber subscriber : subscribers) {
      try {
        subscriber.consumer().accept(projectId);
      } catch (RuntimeException error) {
        log.warn("Failed to notify project subscriber", error);
      }
    }
  }

  /** 广播全局重同步提示。 */
  public void broadcastResync() {
    for (Subscriber subscriber : subscribers) {
      try {
        subscriber.resync().run();
      } catch (RuntimeException error) {
        log.warn("Failed to resync project subscriber", error);
      }
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

  private record Subscriber(Consumer<UUID> consumer, Runnable resync) {}
}
