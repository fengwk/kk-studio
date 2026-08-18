package fun.fengwk.kkstudio.harness.runtime;

import java.util.Objects;
import java.util.UUID;

/**
 * 不可变的直接 YOLO 控制请求：在精确 {@code expectedRevision} 乐观 CAS 下更新 Thread 的 {@code yoloEnabled}。{@code
 * expectedRevision} 必须非负。
 */
public record SetThreadYoloCommand(UUID threadId, long expectedRevision, boolean enabled) {

  public SetThreadYoloCommand {
    Objects.requireNonNull(threadId, "threadId");
    if (expectedRevision < 0) {
      throw new IllegalArgumentException("expectedRevision must not be negative");
    }
  }
}
