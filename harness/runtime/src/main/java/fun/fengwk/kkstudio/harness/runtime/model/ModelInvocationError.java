package fun.fengwk.kkstudio.harness.runtime.model;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;

import java.util.Objects;

/**
 * 持久化为 {@code harness_model_invocation} 上 {@code error} JSONB 列的最小 terminal error snapshot，对应
 * {@code FAILED} / {@code UNKNOWN} 状态。
 *
 * <p>snapshot 刻意保持最小：非空的 {@link ProviderErrorKind} 加上一条非空的可读 {@code message}，外加可选的 {@code
 * httpStatus}。terminal-state adapter 从原始 {@link
 * fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException} 分类构造它。它只为 terminal {@code
 * FAILED}/{@code UNKNOWN} 持久化；{@code RETRY_WAIT} 不携带 terminal payload。
 *
 * <p>{@code httpStatus} 只承载结构化 HTTP error 状态（100–599，含非预期 3xx）：普通非 HTTP 失败为 null，绝不从 message
 * 文本推断状态。 {@link #retryCandidate()} 给出统一的「是否可能被重试」判定，实际是否重试由模型 HTTP 状态策略或共享预算唯一决策。
 *
 * <p>严格 JSON 编码/解码位于 {@link ModelInvocationErrorJsonCodec}。
 */
public record ModelInvocationError(ProviderErrorKind kind, String message, Integer httpStatus) {

  public ModelInvocationError {
    kind = Objects.requireNonNull(kind, "kind");
    message = requireNonBlank(message, "message");
    if (httpStatus != null && (httpStatus < 100 || httpStatus > 599)) {
      throw new IllegalArgumentException("httpStatus must be an HTTP status between 100 and 599");
    }
  }

  /** 明确 non-HTTP 语义的便利构造：不携带 HTTP 状态。 */
  public ModelInvocationError(ProviderErrorKind kind, String message) {
    this(kind, message, null);
  }

  /**
   * 该错误是否可能被重试的统一候选判定：{@link ProviderErrorKind#OVERFLOW} 与 {@link ProviderErrorKind#CANCELLED}
   * 永不普通重试；其余带 HTTP 状态、或 TRANSIENT / INVALID_RESPONSE 的失败是候选。
   *
   * <p>候选只表示「允许进入重试判定」，实际是否重试由模型 HTTP 状态策略（带 {@code httpStatus} 时）或共享 {@link
   * fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy} 预算唯一决策。非 HTTP 的 AUTHENTICATION
   * / BILLING 保持不可重试。
   */
  public boolean retryCandidate() {
    if (kind == ProviderErrorKind.OVERFLOW || kind == ProviderErrorKind.CANCELLED) {
      return false;
    }
    return httpStatus != null
        || kind == ProviderErrorKind.TRANSIENT
        || kind == ProviderErrorKind.INVALID_RESPONSE;
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
