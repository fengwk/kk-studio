package fun.fengwk.kkstudio.harness.runtime;

import java.util.Objects;
import java.util.UUID;

/**
 * 不可变的 Session 重命名请求：把 {@code sessionId} 的显示名称规范化为 {@code name}（非空、单行、至多 256 个 Unicode 码点）。
 *
 * <p>不携带 version 期望：Session 行锁内「同名即 no-op」，否则直接替换 name；并发重命名为 last-commit-wins，绝不触碰 id / createdAt。
 */
public record RenameSessionCommand(UUID sessionId, String name) {

  public RenameSessionCommand {
    Objects.requireNonNull(sessionId, "sessionId");
    name = Names.normalize(name);
  }
}
