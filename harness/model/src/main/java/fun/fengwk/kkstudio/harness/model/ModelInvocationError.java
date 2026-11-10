package fun.fengwk.kkstudio.harness.model;

import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;

import java.util.Objects;

/**
 * 持久化为 {@code builtin_model_invocation} 上 {@code error} JSONB 列的最小 terminal error snapshot，对应
 * {@code FAILED} / {@code UNKNOWN} 状态。
 *
 * <p>snapshot 刻意保持最小：非空的 {@link ProviderErrorKind} 加上一条非空的可读 {@code message}，外加可选的 {@code
 * httpStatus} 与可选的 Provider 原始 {@code code}。terminal-state adapter 从原始 {@link
 * fun.fengwk.kkstudio.harness.model.provider.ProviderException} 分类构造它。它只为 terminal {@code
 * FAILED}/{@code UNKNOWN} 持久化；{@code RETRY_WAIT} 不携带 terminal payload。
 *
 * <p>{@code httpStatus} 只承载结构化 HTTP error 状态（100–599，含非预期 3xx）：普通非 HTTP 失败为 null，绝不从 message
 * 文本推断状态。{@code providerErrorCode} 是上游错误 payload 的原始 code/type 字符串（如 {@code
 * context_length_exceeded}），按原样保留用于诊断，绝不参与分类；null 表示上游没有提供结构化 code。 {@link #retryCandidate()}
 * 给出统一的「是否可能被重试」判定，实际是否重试由模型 HTTP 状态策略或共享预算唯一决策。
 *
 * <p>wire 形态就是本 record，持久化与 HTTP 边界经 {@code StrictJson} 严格绑定。
 */
public record ModelInvocationError(
    ProviderErrorKind kind, String message, Integer httpStatus, String providerErrorCode) {

  /** 上游原始 code 的最大保留长度；超长截断只发生在保留诊断字段时。 */
  public static final int MAX_PROVIDER_CODE_LENGTH = 128;

  public ModelInvocationError {
    kind = Objects.requireNonNull(kind, "kind");
    message = requireNonBlank(message, "message");
    if (httpStatus != null && (httpStatus < 100 || httpStatus > 599)) {
      throw new IllegalArgumentException("httpStatus must be an HTTP status between 100 and 599");
    }
    providerErrorCode = normalizeProviderCode(providerErrorCode);
  }

  /** 明确 non-HTTP 语义的便利构造：不携带 HTTP 状态与原始 code。 */
  public ModelInvocationError(ProviderErrorKind kind, String message) {
    this(kind, message, null, null);
  }

  /** 携带 HTTP 状态、不含原始 code 的便利构造。 */
  public ModelInvocationError(ProviderErrorKind kind, String message, Integer httpStatus) {
    this(kind, message, httpStatus, null);
  }

  /**
   * 该错误是否可能被重试的统一候选判定：{@link ProviderErrorKind#CONTEXT_LIMIT}、{@link
   * ProviderErrorKind#REQUEST_BODY_LIMIT}、{@link ProviderErrorKind#MEDIA_LIMIT} 与 {@link
   * ProviderErrorKind#CANCELLED} 永不普通重试；其余带 HTTP 状态、或 TRANSIENT / INVALID_RESPONSE 的失败是候选。
   *
   * <p>候选只表示「允许进入重试判定」，实际是否重试由模型 HTTP 状态策略（带 {@code httpStatus} 时）或调用方共享的 {@code
   * InvocationRetryPolicy} 预算唯一决策。非 HTTP 的 AUTHENTICATION / BILLING 保持不可重试。
   */
  public boolean retryCandidate() {
    if (kind == ProviderErrorKind.CONTEXT_LIMIT
        || kind == ProviderErrorKind.REQUEST_BODY_LIMIT
        || kind == ProviderErrorKind.MEDIA_LIMIT
        || kind == ProviderErrorKind.CANCELLED) {
      return false;
    }
    return httpStatus != null
        || kind == ProviderErrorKind.TRANSIENT
        || kind == ProviderErrorKind.INVALID_RESPONSE;
  }

  private static String normalizeProviderCode(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.length() > MAX_PROVIDER_CODE_LENGTH
        ? trimmed.substring(0, MAX_PROVIDER_CODE_LENGTH)
        : trimmed;
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
