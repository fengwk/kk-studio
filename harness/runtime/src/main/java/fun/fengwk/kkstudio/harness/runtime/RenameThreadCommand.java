package fun.fengwk.kkstudio.harness.runtime;

import java.util.Objects;
import java.util.UUID;

/**
 * 不可变的 Thread 重命名请求：把 {@code threadId} 的显示名称规范化为 {@code name}（非空、单行、至多 256 个 Unicode 码点）。
 *
 * <p>不携带 version 期望：锁内「同名即 no-op」，否则 Thread version 精确 +1；并发重命名为 last-commit-wins，不产生任何 Command /
 * Entry / Work 副作用。
 */
public record RenameThreadCommand(UUID threadId, String name) {

  public RenameThreadCommand {
    Objects.requireNonNull(threadId, "threadId");
    name = Names.normalize(name);
  }
}
