package fun.fengwk.kkstudio.core.harness.model.worker;

import fun.fengwk.kkstudio.harness.runtime.model.SafeStreamSnapshot;

/**
 * 在 Thread -> Invocation 行锁内部判定两次 {@link SafeStreamSnapshot} 的单调合并关系。
 *
 * <p>sequence 较旧的重放直接保留 durable；sequence 相同则内容必须完全一致；sequence 更新时，每个 text/thinking 字段都必须延长 durable
 * 前缀。tool-call delta 可以只推进 sequence 而不改变文本。
 *
 * <p>该判定必须在 Thread + Invocation 锁内完成，避免在并发 worker 重放或 SSE replay 中悄悄写入较短 durable 快照。
 */
final class SafeStreamSnapshotMonotonicity {

  private SafeStreamSnapshotMonotonicity() {}

  /** 若 {@code durable} 与 {@code incoming} 字段互不为前缀抛出 {@link IllegalSnapshotForkException}。 */
  static SafeStreamSnapshot merge(SafeStreamSnapshot durable, SafeStreamSnapshot incoming) {
    if (incoming.sequence() < durable.sequence()) {
      return durable;
    }
    if (incoming.sequence() == durable.sequence()) {
      if (!incoming.equals(durable)) {
        throw new IllegalSnapshotForkException(
            "safe stream snapshot content changed without advancing sequence");
      }
      return durable;
    }
    String text = requireExtension("text", durable.text(), incoming.text());
    String thinking = requireExtension("thinking", durable.thinking(), incoming.thinking());
    return new SafeStreamSnapshot(text, thinking, incoming.sequence());
  }

  private static String requireExtension(String name, String durable, String incoming) {
    if (incoming.startsWith(durable)) {
      return incoming;
    }
    throw new IllegalSnapshotForkException(
        name + " safe stream snapshot must not shrink or diverge at a newer sequence");
  }

  /** 两条 SSE 流在同一字段上互不为前缀，视为非法 invocation state。 */
  static final class IllegalSnapshotForkException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    IllegalSnapshotForkException(String message) {
      super(message);
    }
  }
}
