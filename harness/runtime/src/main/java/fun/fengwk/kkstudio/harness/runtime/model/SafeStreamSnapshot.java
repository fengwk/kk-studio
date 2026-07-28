package fun.fengwk.kkstudio.harness.runtime.model;

import java.util.Objects;

/**
 * 跨节点持久化的安全流快照：仅包含 text 与 thinking 累加值；tool-call fragment 永远不进入此类型。
 *
 * <p>快照在每个 text/thinking delta 通过 {@code ModelInvocationTransactions.recordSafeStreamSnapshot} 在
 * SSE 公开前 fenced 写入。新 retry attempt 必须显式重置快照，避免旧 attempt 的 partial 跨 attempt 泄漏到新 attempt 的
 * Provider 上下文。
 *
 * <p>终态行（SUCCEEDED/FAILED/CANCELLED/UNKNOWN）保留该字段以做审计；终态快照不是 realtime overlay 来源，但若 {@code
 * applied_at is null} 仍可被 {@code ThreadCommandTransactions.stop} 在 head-scoped 查询中读取，并 转为 {@code
 * ASSISTANT_ABORTED} Entry 的 text/thinking content，从而让已写入的部分输出在用户主动 stop 后仍可见。
 */
public record SafeStreamSnapshot(String text, String thinking) {

  public SafeStreamSnapshot {
    text = Objects.requireNonNull(text, "text");
    thinking = Objects.requireNonNull(thinking, "thinking");
  }

  public static final SafeStreamSnapshot EMPTY = new SafeStreamSnapshot("", "");

  /** 是否含有任何可发布的内容。 */
  public boolean hasContent() {
    return !text.isEmpty() || !thinking.isEmpty();
  }
}
