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
 * <p>Project 持久化仓库在事实事务内经统一通知总线发布 project id，提交后才投递。此 Hub 不复制领域事实；payload 只作为定向刷新提示， 畸形 payload 由总线
 * topic codec 拒绝并退化为全量重同步。
 */
@Slf4j
@Component
public class ProjectInvalidationHub {

  private final Set<Subscriber> subscribers = new CopyOnWriteArraySet<>();

  public AutoCloseable subscribe(Consumer<UUID> consumer, Runnable resync) {
    Subscriber subscriber =
        new Subscriber(
            Objects.requireNonNull(consumer, "consumer"), Objects.requireNonNull(resync, "resync"));
    subscribers.add(subscriber);
    return () -> subscribers.remove(subscriber);
  }

  /** 交付一条已由总线解码的失效提示；payload 只用于缩小浏览器快照刷新范围。 */
  public void onNotification(UUID projectId) {
    Objects.requireNonNull(projectId, "projectId");
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

  private record Subscriber(Consumer<UUID> consumer, Runnable resync) {}
}
