package fun.fengwk.kkstudio.platform.harness.model;

/**
 * 请求预览不可用的确定性拒绝（HTTP 语义 {@code 409 Conflict}）。
 *
 * <p>与请求形状/归属校验错误不同，本异常表示请求本身合法但当前事实不允许精确预览：Thread 快照已漂移、Thread 正在活动或仍有 queued 命令、下一步必定是压缩、附件上传尚未
 * READY、或当前 Provider adapter 不提供请求体预览能力。消息只携带稳定、安全的描述， 绝不回显 credential、Authorization header、URL
 * 或上传/对象存储内部标识。
 */
public class ProviderRequestPreviewUnavailableException extends RuntimeException {

  public ProviderRequestPreviewUnavailableException(String message) {
    super(message);
  }

  public ProviderRequestPreviewUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
