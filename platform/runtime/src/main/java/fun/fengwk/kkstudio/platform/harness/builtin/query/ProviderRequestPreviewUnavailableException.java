package fun.fengwk.kkstudio.platform.harness.builtin.query;

import java.util.Objects;

/**
 * 请求预览不可用的确定性拒绝（HTTP 语义 {@code 409 Conflict}）。
 *
 * <p>与请求形状/归属校验错误不同，本异常表示请求本身合法但当前事实不允许精确重建：历史压缩事实缺少冻结预算、历史前缀无法规划，或当前 Provider adapter
 * 不提供请求体预览能力。消息只携带稳定、安全的描述，绝不回显 credential、Authorization header、URL 或上传/对象存储内部标识。
 */
public class ProviderRequestPreviewUnavailableException extends RuntimeException {

  /** 稳定 HTTP wire 原因；调用方不得根据描述文本推断类别。 */
  public enum Reason {
    PREVIEW_PLANNING_FAILED,
    PREVIEW_PROVIDER_UNAVAILABLE,
    /** 当前事实不完整或 adapter 无预览能力，无法按历史路径精确重建该调用。 */
    PREVIEW_UNSUPPORTED,
    PREVIEW_ENCODING_FAILED
  }

  private final Reason reason;

  public ProviderRequestPreviewUnavailableException(Reason reason, String message) {
    this(reason, message, null);
  }

  public ProviderRequestPreviewUnavailableException(
      Reason reason, String message, Throwable cause) {
    super(Objects.requireNonNull(message, "message"), cause);
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  public Reason reason() {
    return reason;
  }
}
