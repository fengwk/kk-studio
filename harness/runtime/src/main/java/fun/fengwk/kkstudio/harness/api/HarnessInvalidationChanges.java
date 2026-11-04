package fun.fengwk.kkstudio.harness.api;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 提示型资源的公开失效订阅：只提示「本地投影需要回读权威事实」，不复制领域事实，也没有持久游标。
 *
 * <p>{@code key} 为 {@code null} 表示全局订阅（接收全部合法通知），非 {@code null} 表示按 key 定点订阅（例如真实执行根 Thread id）。空
 * payload、畸形 payload 与 LISTEN 重连都退化为 {@link Event.Resync}，由观察者整体回读。
 */
public interface HarnessInvalidationChanges {

  /** 一次资源失效，或全量回读要求。 */
  sealed interface Event permits Event.Changed, Event.Resync {

    /** 指定 key 的权威事实已提交变化；{@code key} 是通知定位的资源。 */
    record Changed(UUID key) implements Event {
      public Changed {
        Objects.requireNonNull(key, "key");
      }
    }

    /** 全量回读（LISTEN 重连、畸形通知或来源事实已不存在）。 */
    record Resync() implements Event {}
  }

  /**
   * 订阅提示型失效信号。
   *
   * @param key 资源 key；{@code null} 表示全局订阅
   * @param consumer 接收失效信号；可能在任何线程回调，不得阻塞
   * @return 恒为 {@code 0} 的 cursor 与幂等释放句柄
   */
  HarnessChangeSubscription subscribe(UUID key, Consumer<Event> consumer);
}
