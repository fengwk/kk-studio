package fun.fengwk.kkstudio.platform.notification;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 提示型失效通知的通用订阅契约：只提示「本地投影需要回读权威事实」，不复制领域事实，也没有持久游标。
 *
 * <p>{@code key} 为 {@code null} 表示全局订阅（接收全部合法通知），非 {@code null} 表示按资源 key 定点订阅。空/畸形 payload 与
 * LISTEN 建连/重连由实现退化为 {@link Event.Resync}，观察者必须整体回读。
 */
public interface NotificationInvalidationSource {

  /** 一次资源失效，或全量回读要求。 */
  sealed interface Event permits Event.Changed, Event.Resync {

    /** 指定 key 的权威事实已提交变化。 */
    record Changed(UUID key) implements Event {
      public Changed {
        Objects.requireNonNull(key, "key");
      }
    }

    /** 全量回读（LISTEN 重连、畸形通知或来源事实已不存在）。 */
    record Resync() implements Event {}
  }

  /**
   * 注册一个失效订阅并返回幂等注销动作。
   *
   * @param key 资源 key；{@code null} 表示全局订阅
   * @param consumer 接收失效事件；不得阻塞
   */
  Runnable subscribe(UUID key, Consumer<Event> consumer);
}
