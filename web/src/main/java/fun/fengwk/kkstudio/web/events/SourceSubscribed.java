package fun.fengwk.kkstudio.web.events;

import java.util.Objects;

/** 资源 source 原子订阅结果：{@code cursor} 是 subscribed ack 游标，{@code handle} 释放订阅。 */
record SourceSubscribed(long cursor, AutoCloseable handle) {
  SourceSubscribed {
    if (cursor < 0L) {
      throw new IllegalArgumentException("cursor must be non-negative");
    }
    Objects.requireNonNull(handle, "handle");
  }
}
