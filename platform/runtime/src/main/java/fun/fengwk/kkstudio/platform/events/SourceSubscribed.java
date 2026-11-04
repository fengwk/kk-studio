package fun.fengwk.kkstudio.platform.events;

import java.util.Objects;

/**
 * 资源 source 原子订阅结果：{@code cursor} 是 subscribed ack 游标（订阅建立瞬间的 durable cursor），{@code handle} 释放订阅。
 *
 * <p>订阅方先注册 consumer 再读取 cursor，因此 cursor 之后的变化保证送达；LISTEN 断连期间不可恢复的通知由 resync 事件覆盖。
 */
public record SourceSubscribed(long cursor, AutoCloseable handle) {

  public SourceSubscribed {
    if (cursor < 0L) {
      throw new IllegalArgumentException("cursor must be non-negative");
    }
    Objects.requireNonNull(handle, "handle");
  }
}
