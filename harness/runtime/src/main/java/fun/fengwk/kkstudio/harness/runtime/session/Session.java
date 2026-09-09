package fun.fengwk.kkstudio.harness.runtime.session;

import fun.fengwk.kkstudio.harness.runtime.Names;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Session 聚合边界：组织一份 append-only Entry Tree，不拥有 Thread；只记录自身 id、显示名称与创建时间。
 *
 * <p>{@code id} 与 {@code createdAt} 创建后不可变；{@code name} 是唯一可变字段，经 {@link #rename} 规范化替换。
 */
public record Session(UUID id, String name, Instant createdAt) {

  public Session {
    Objects.requireNonNull(id, "id");
    name = Names.normalize(name);
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }

  /** 校验 {@code next} 是存储行 {@code stored} 的合法迁移：id / createdAt 不可变；name 可任意替换（构造器已保证规范化）。 */
  public static void validateTransition(Session stored, Session next) {
    Objects.requireNonNull(stored, "stored");
    Objects.requireNonNull(next, "next");
    if (stored.equals(next)) {
      return;
    }
    if (!stored.id().equals(next.id())) {
      throw new IllegalArgumentException("session id must not change");
    }
    if (!stored.createdAt().equals(next.createdAt())) {
      throw new IllegalArgumentException("session createdAt must not change");
    }
  }

  /** 返回名称替换为 {@code name}（经共享规范化）的新 Session；id / createdAt 保持不变。 */
  public Session rename(String name) {
    return new Session(id, name, createdAt);
  }
}
