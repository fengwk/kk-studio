package fun.fengwk.kkstudio.harness.runtime.model.provider;

import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;

import java.util.Objects;

/**
 * Context-pressure 判定所需的不可变事实快照。
 *
 * <p>由纯函数 {@link ContextPressureDetector} 消费；有两条构造路径：Provider 错误（{@code httpStatus}、{@code
 * providerErrorCodeOrType}、{@code errorMessage}，由 core 的 extractor 从异常链抽取）与生成响应（{@code stopReason}、
 * {@code usage}、{@code contextWindow}）。除 {@code providerType} 外所有字段可为空；{@code contextWindow} 非空时
 * 必须为正整数。该快照不持久化，也不携带 {@link ProviderErrorKind} 等 durable 状态。
 */
public record ContextPressureFacts(
    ProviderType providerType,
    Integer httpStatus,
    String providerErrorCodeOrType,
    String errorMessage,
    GenerationStopReason stopReason,
    ModelUsage usage,
    Long contextWindow) {

  public ContextPressureFacts {
    providerType = Objects.requireNonNull(providerType, "providerType");
    if (httpStatus != null && httpStatus < 100) {
      throw new IllegalArgumentException("httpStatus must be a valid HTTP status when present");
    }
    providerErrorCodeOrType = blankToNull(providerErrorCodeOrType);
    errorMessage = blankToNull(errorMessage);
    if (contextWindow != null && contextWindow <= 0) {
      throw new IllegalArgumentException("contextWindow must be positive when present");
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
