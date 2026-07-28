package fun.fengwk.kkstudio.core.harness.model.worker;

import fun.fengwk.kkstudio.harness.runtime.model.SafeStreamSnapshot;

/**
 * 在 Thread -> Invocation 行锁内部判定两次 {@link SafeStreamSnapshot} 的单调合并关系。
 *
 * <p>每个 text/thinking 字段独立判定：{@code incoming} 是 {@code durable} 的延长前缀时输出 {@code incoming} ；{@code
 * incoming} 是 {@code durable} 的旧前缀或与 durable 内容相同时输出 {@code durable}（保留较长 durable，避免
 * 重放覆盖）；两侧内容在同一字段上互不为前缀时视为非法 invocation state 并显式拒绝。
 *
 * <p>该判定必须在 Thread + Invocation 锁内完成，避免在并发 worker 重放或 SSE replay 中悄悄写入较短 durable 快照。
 */
final class SafeStreamSnapshotMonotonicity {

  private SafeStreamSnapshotMonotonicity() {}

  /** 若 {@code durable} 与 {@code incoming} 字段互不为前缀抛出 {@link IllegalSnapshotForkException}。 */
  static SafeStreamSnapshot merge(SafeStreamSnapshot durable, SafeStreamSnapshot incoming) {
    String text = mergeField("text", durable.text(), incoming.text());
    String thinking = mergeField("thinking", durable.thinking(), incoming.thinking());
    if (text.equals(durable.text()) && thinking.equals(durable.thinking())) {
      return durable;
    }
    return new SafeStreamSnapshot(text, thinking);
  }

  private static String mergeField(String name, String durable, String incoming) {
    if (durable == null) {
      durable = "";
    }
    if (incoming == null) {
      incoming = "";
    }
    if (durable.isEmpty()) {
      return incoming;
    }
    if (incoming.isEmpty()) {
      return durable;
    }
    if (incoming.startsWith(durable)) {
      return incoming;
    }
    if (durable.startsWith(incoming)) {
      return durable;
    }
    throw new IllegalSnapshotForkException(name + " safe stream snapshot is divergent");
  }

  /** 两条 SSE 流在同一字段上互不为前缀，视为非法 invocation state。 */
  static final class IllegalSnapshotForkException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    IllegalSnapshotForkException(String message) {
      super(message);
    }
  }
}
