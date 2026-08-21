package fun.fengwk.kkstudio.harness.runtime;

import java.util.Objects;
import java.util.UUID;

/**
 * 不可变的 Stop 请求。
 *
 * <p>{@code stopRequestId} 是客户端生成的幂等键。Runtime 把它原样写入 STOPPED TURN_END 的 closeRequestId，并按被该
 * TURN_END 引用的 TURN_START 的 {@code ownerThreadId} 界定作用域（Session 级查找）：同一 raw id 可在不同 Thread 上
 * 独立使用，绝不在 Thread 之间产生别名冲突。
 */
public record StopCommand(UUID threadId, UUID stopRequestId, long expectedVersion) {

  public StopCommand {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(stopRequestId, "stopRequestId");
    if (expectedVersion < 0) {
      throw new IllegalArgumentException("expectedVersion must not be negative");
    }
  }
}
