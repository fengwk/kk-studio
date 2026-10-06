package fun.fengwk.kkstudio.platform.harness.model;

import java.util.Objects;

/**
 * 请求预览不可用的确定性拒绝（HTTP 语义 {@code 409 Conflict}）。
 *
 * <p>与请求形状/归属校验错误不同，本异常表示请求本身合法但当前事实不允许精确预览：Thread 快照已漂移、Thread 正在活动或仍有 queued 命令、 下一步必定是压缩、附件上传尚未
 * READY、压缩 Turn 的模型调用无法按 live 路径重建、或当前 Provider adapter 不提供请求体预览能力。消息只携带 稳定、安全的描述，绝不回显
 * credential、Authorization header、URL 或上传/对象存储内部标识。
 */
public class ProviderRequestPreviewUnavailableException extends RuntimeException {

  /** 稳定 HTTP wire 原因；调用方不得根据描述文本推断类别。 */
  public enum Reason {
    PREVIEW_STALE_CURSOR,
    PREVIEW_QUEUED_COMMANDS,
    PREVIEW_THREAD_BUSY,
    PREVIEW_COMPACTION_REQUIRED,
    PREVIEW_ATTACHMENT_NOT_READY,
    PREVIEW_PLANNING_FAILED,
    PREVIEW_PROVIDER_UNAVAILABLE,
    /** 当前入口无法按 live 路径精确重建该调用（adapter 无预览能力，或该调用属于压缩专用路径）。 */
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
