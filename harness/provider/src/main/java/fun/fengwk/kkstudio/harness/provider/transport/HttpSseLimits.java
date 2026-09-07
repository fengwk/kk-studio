package fun.fengwk.kkstudio.harness.provider.transport;

/**
 * HTTP/SSE 流传输的有界限制配置。
 *
 * <p>所有限制均以字节（byte）而不是字符数计算，防止畸形或超长输入引发内存耗尽。
 */
public record HttpSseLimits(
    int maxLineBytes, int maxEventBytes, long maxSuccessBodyBytes, int maxErrorBodyBytes) {

  /** 默认限制：单行 64KB、单事件 1MB、成功响应流总计 128MB、错误响应体 64KB。 */
  public static final HttpSseLimits DEFAULT =
      new HttpSseLimits(64 * 1024, 1024 * 1024, 128L * 1024 * 1024, 64 * 1024);

  public HttpSseLimits {
    if (maxLineBytes <= 0) {
      throw new IllegalArgumentException("maxLineBytes must be positive");
    }
    if (maxEventBytes <= 0) {
      throw new IllegalArgumentException("maxEventBytes must be positive");
    }
    if (maxSuccessBodyBytes <= 0) {
      throw new IllegalArgumentException("maxSuccessBodyBytes must be positive");
    }
    if (maxErrorBodyBytes <= 0) {
      throw new IllegalArgumentException("maxErrorBodyBytes must be positive");
    }
  }
}
