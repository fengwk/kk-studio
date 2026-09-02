package fun.fengwk.kkstudio.share.ai.runtime;

/** 表示 Harness HTTP 请求体反序列化过程中的格式校验失败异常。 该异常携带的 message 是预定义且安全的，可公开给客户端作为 detail 诊断信息。 */
public final class HarnessRequestFormatException extends IllegalArgumentException {

  public HarnessRequestFormatException(String message) {
    super(message);
  }

  public HarnessRequestFormatException(String message, Throwable cause) {
    super(message, cause);
  }
}
