package fun.fengwk.kkstudio.harness.runtime;

import java.util.Objects;
import java.util.UUID;

/**
 * 不可变的直接 YOLO 控制请求：只切换执行根自身的 {@code ENABLE}/{@code DISABLE}。它是单字段幂等策略，不携带完整 Thread version 的 CAS
 * cursor；对子代理调用会被拒绝。
 */
public record SetThreadYoloCommand(UUID threadId, boolean enabled) {

  public SetThreadYoloCommand {
    Objects.requireNonNull(threadId, "threadId");
  }
}
