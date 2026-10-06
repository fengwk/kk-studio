package fun.fengwk.kkstudio.web.events;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 提示型资源失效源：只提示「本地投影需要回读权威事实」，绝不复制领域事实，也不携带持久游标（ack 恒为 0）。
 *
 * <p>{@code key} 为 null 表示全局资源订阅（接收全部合法通知），非 null 表示按 key 定点订阅（例如执行根）。 通知只能来自 PostgreSQL {@code
 * LISTEN}；畸形 payload 与 LISTEN 重连由实现退化为全量 resync。
 */
public interface InvalidationEventSource {

  /** 一次失效信号：{@code key} 是通知定位的资源 key（全局资源可能为 null），{@code resync} 要求全量回读。 */
  record Event(UUID key, boolean resync) {
    public Event {
      if (resync && key != null) {
        throw new IllegalArgumentException("resync event must not carry a key");
      }
    }

    public static Event changed(UUID key) {
      return new Event(Objects.requireNonNull(key, "key"), false);
    }

    /** 全量回读（LISTEN 重连、畸形 payload 或来源事实已不存在）。 */
    public static Event fullResync() {
      return new Event(null, true);
    }
  }

  SourceSubscribed subscribe(UUID key, Consumer<Event> consumer);
}
