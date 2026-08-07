package fun.fengwk.kkstudio.harness.runtime.model;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;

import java.util.Objects;

/**
 * 持久化为 {@code harness_model_invocation} 上 {@code error} JSONB 列的最小 terminal error snapshot，对应
 * {@code FAILED} / {@code UNKNOWN} 状态。
 *
 * <p>snapshot 刻意保持最小：非空的 {@link ProviderErrorKind} 加上一条非空的可读 {@code message}。terminal-state adapter
 * 从原始 {@link fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException} 分类构造它。它只为
 * terminal {@code FAILED}/{@code UNKNOWN} 持久化；{@code RETRY_WAIT} 不携带 terminal payload。
 *
 * <p>严格 JSON 编码/解码位于 {@link ModelInvocationErrorJsonCodec}。
 */
public record ModelInvocationError(ProviderErrorKind kind, String message) {

  public ModelInvocationError {
    kind = Objects.requireNonNull(kind, "kind");
    message = requireNonBlank(message, "message");
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
