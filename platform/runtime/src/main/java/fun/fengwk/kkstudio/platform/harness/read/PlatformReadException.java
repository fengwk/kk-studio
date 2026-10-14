package fun.fengwk.kkstudio.platform.harness.read;

/**
 * 平台侧读取操作确定性失败异常。
 *
 * <p>该异常表示读取请求遇到确定性失败（如资源未找到、越权、参数不合法、内容超限或看似二进制等）， 最终会被统一映射为模型可见的工具错误结果。
 */
public class PlatformReadException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public PlatformReadException(String message) {
    super(message);
  }

  public PlatformReadException(String message, Throwable cause) {
    super(message, cause);
  }
}
