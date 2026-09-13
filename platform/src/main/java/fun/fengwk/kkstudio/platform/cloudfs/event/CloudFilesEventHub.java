package fun.fengwk.kkstudio.platform.cloudfs.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Cloud File System 变更失效信号分发器。
 *
 * <p>V6 数据库 trigger 在 {@code cloud_node} 事务提交后通过 PostgreSQL {@code LISTEN/NOTIFY} 发送 node id。此 Hub
 * 不复制文件系统事实，也不依赖 payload 完整性；每个通知和 LISTEN 重连都只触发订阅者回读权威快照。
 */
@Slf4j
@Component
public class CloudFilesEventHub implements CloudFilesEventSource {

  public static final String CHANNEL = "cloud_files_changed";

  private final Set<Runnable> subscribers = new CopyOnWriteArraySet<>();

  @Override
  public AutoCloseable subscribe(Runnable consumer) {
    Objects.requireNonNull(consumer, "consumer");
    subscribers.add(consumer);
    return () -> subscribers.remove(consumer);
  }

  /** PostgreSQL LISTEN 通知入口。payload 仅是有损提示，订阅者必须回读权威快照。 */
  public void onNotification(String payload) {
    fanout();
  }

  /** 广播全局重同步提示。 */
  public void broadcastResync() {
    fanout();
  }

  private void fanout() {
    for (Runnable consumer : subscribers) {
      try {
        consumer.run();
      } catch (RuntimeException error) {
        log.warn("Failed to notify cloud files subscriber", error);
      }
    }
  }
}
