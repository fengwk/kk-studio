package fun.fengwk.kkstudio.platform.daemon.tunnel;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * 一次 HTTP 交换。
 *
 * <p>{@link #stream()} 的写端是请求体，读端是响应体。响应头可以早于请求体写完到达；非 2xx 是正常响应，不是传输异常。响应提交后的失败只终止流，不能伪造新的 HTTP
 * 成功或状态。
 */
public interface TunnelExchange extends AutoCloseable {

  /** 异步响应头；非 2xx 仍正常完成。 */
  CompletionStage<Response> responseHeaders();

  /** 请求体写入、响应体读取共用的同一字节流。 */
  TunnelStream stream();

  /**
   * 取消整个交换并释放资源。
   *
   * <p>关闭幂等。尚未完成的响应头和流操作以 {@link java.util.concurrent.CancellationException} 结束。
   */
  @Override
  void close();

  /**
   * 已提交且深度不可变的 HTTP 响应头。
   *
   * @param status 闭区间 {@code [100, 599]} 内的状态码；非 2xx 仍是正常响应
   * @param headers 以小写头名索引的多值响应头
   */
  record Response(int status, Map<String, List<String>> headers) {

    /**
     * 校验并冻结响应头。
     *
     * @throws IllegalArgumentException 状态码或响应头不合法
     */
    public Response {
      if (status < 100 || status > 599) {
        throw new IllegalArgumentException("HTTP status must be within [100, 599]");
      }
      headers = TunnelHttp.freezeHeaders(headers);
    }
  }
}
