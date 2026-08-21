package fun.fengwk.kkstudio.harness.runtime;

import java.util.Objects;
import java.util.UUID;

/**
 * 不可变的直接 YOLO 控制请求：在精确 {@code expectedVersion} 乐观 CAS 下更新 Thread 的 {@code yoloEnabled}。{@code
 * expectedVersion} 必须非负。
 */
public record SetThreadYoloCommand(UUID threadId, long expectedVersion, boolean enabled) {

  public SetThreadYoloCommand {
    Objects.requireNonNull(threadId, "threadId");
    if (expectedVersion < 0) {
      throw new IllegalArgumentException("expectedVersion must not be negative");
    }
  }
}
