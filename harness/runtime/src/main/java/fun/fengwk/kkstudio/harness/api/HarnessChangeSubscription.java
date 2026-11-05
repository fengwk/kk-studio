package fun.fengwk.kkstudio.harness.api;

import java.util.Objects;

/**
 * 一次变更订阅的不可变释放句柄。
 *
 * <p>{@link #cursor()} 是订阅建立瞬间的 durable cursor：Thread version 源返回当时的权威 version，提示型失效源没有持久游标，恒为
 * {@code 0}。{@code cursor} 之后的变化保证经订阅回调送达，之前的由观察者随后读取的权威快照覆盖。
 *
 * <p>{@code release} 必须是幂等的注销动作；{@link #close()} 无 checked exception，重复关闭不产生任何副作用。
 */
public record HarnessChangeSubscription(long cursor, Runnable release) implements AutoCloseable {

  public HarnessChangeSubscription {
    if (cursor < 0L) {
      throw new IllegalArgumentException("cursor must be non-negative");
    }
    Objects.requireNonNull(release, "release");
  }

  @Override
  public void close() {
    release.run();
  }
}
