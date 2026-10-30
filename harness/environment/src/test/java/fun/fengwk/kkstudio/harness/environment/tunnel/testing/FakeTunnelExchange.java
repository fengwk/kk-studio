package fun.fengwk.kkstudio.harness.environment.tunnel.testing;

import fun.fengwk.kkstudio.harness.environment.tunnel.TunnelExchange;
import fun.fengwk.kkstudio.harness.environment.tunnel.TunnelHttpRequest;
import fun.fengwk.kkstudio.harness.environment.tunnel.TunnelStream;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 进程内 HTTP 交换；响应头和流由测试对端显式推进。 */
public final class FakeTunnelExchange implements TunnelExchange {

  private final TunnelHttpRequest request;
  private final FakeTunnelStream stream;
  private final CompletableFuture<Response> responseHeaders = new CompletableFuture<>();

  FakeTunnelExchange(TunnelHttpRequest request, FakeTunnelStream stream) {
    this.request = request;
    this.stream = stream;
  }

  /** 打开交换时捕获的请求。 */
  public TunnelHttpRequest request() {
    return request;
  }

  @Override
  public CompletionStage<Response> responseHeaders() {
    return responseHeaders;
  }

  CompletableFuture<Response> responseFuture() {
    return responseHeaders;
  }

  @Override
  public TunnelStream stream() {
    return stream;
  }

  /** 测试侧使用的同一条流。 */
  public FakeTunnelStream fakeStream() {
    return stream;
  }

  /**
   * 提交正常响应头，包括非 2xx。
   *
   * @param response 已校验的响应头
   * @throws IllegalStateException 响应头已经提交、交换已取消或会话已断开
   */
  public void respond(Response response) {
    if (response == null) {
      throw new IllegalArgumentException("response must not be null");
    }
    if (responseHeaders.isDone() || !responseHeaders.complete(response)) {
      throw new IllegalStateException("response headers are already settled");
    }
  }

  /** 响应提交前失败只终止交换，不补造 HTTP 状态。 */
  public void failHeaders(IOException failure) {
    if (failure == null) {
      throw new IllegalArgumentException("failure must not be null");
    }
    responseHeaders.completeExceptionally(failure);
    stream.fail(failure);
  }

  @Override
  public void close() {
    responseHeaders.completeExceptionally(new CancellationException("tunnel exchange closed"));
    stream.close();
  }
}
