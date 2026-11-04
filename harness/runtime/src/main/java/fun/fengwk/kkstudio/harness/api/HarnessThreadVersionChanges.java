package fun.fengwk.kkstudio.harness.api;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Thread durable version 变化的公开订阅。
 *
 * <p>观察者总是「先订阅、再读权威快照」：{@link #subscribe} 在返回前完成注册并给出建立瞬间的 version，因此返回的 {@link
 * HarnessChangeSubscription#cursor()} 之后的变化保证送达，两者之间不存在丢失窗口。version 是 Thread 结构与控制状态的 invalidation
 * cursor，不是快照内容版本。
 */
public interface HarnessThreadVersionChanges {

  /** 一次 version 前进，或监听器恢复要求整体回读。 */
  sealed interface Event permits Event.Version, Event.Resync {

    /** 提交后的权威 version（非负，与 {@code harness_thread.version} 同一坐标）。 */
    record Version(long version) implements Event {
      public Version {
        if (version < 0L) {
          throw new IllegalArgumentException("version must not be negative");
        }
      }
    }

    /** 监听器重连或通知无法解析：观察者必须整体回读权威快照。 */
    record Resync() implements Event {}
  }

  /**
   * 原子订阅一个 Thread 的 version 变化：先注册 {@code consumer}，再读取当前 version 返回。
   *
   * @param threadId 目标 Thread
   * @param consumer 接收事件；可能在任何线程回调，不得阻塞
   * @return 建立瞬间 version 与幂等释放句柄
   * @throws IllegalArgumentException Thread 不存在；注册已释放，不遗留订阅
   */
  HarnessChangeSubscription subscribe(UUID threadId, Consumer<Event> consumer);
}
