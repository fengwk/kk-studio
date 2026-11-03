package fun.fengwk.kkstudio.platform.daemon.tunnel.testing;

import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.platform.daemon.tunnel.EnvironmentTunnel;
import fun.fengwk.kkstudio.platform.daemon.tunnel.TunnelExchange;
import fun.fengwk.kkstudio.platform.daemon.tunnel.TunnelHttpRequest;
import fun.fengwk.kkstudio.platform.daemon.tunnel.TunnelStream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 固定在一个连接代际上的进程内隧道。 */
public final class FakeEnvironmentTunnel implements EnvironmentTunnel {

  private final Object lock = new Object();
  private final DaemonConnectionIdentity identity;
  private final List<FakeTunnelStream> streams = new ArrayList<>();
  private final List<FakeTunnelExchange> exchanges = new ArrayList<>();
  private boolean draining;
  private boolean disconnected;

  public FakeEnvironmentTunnel(DaemonConnectionIdentity identity) {
    if (identity == null) {
      throw new IllegalArgumentException("identity must not be null");
    }
    this.identity = identity;
  }

  @Override
  public DaemonConnectionIdentity identity() {
    return identity;
  }

  /** 会话是否已排空。排空后 {@code local} 不再返回它，已有流不受影响。 */
  public boolean draining() {
    synchronized (lock) {
      return draining;
    }
  }

  /** 拒绝新建并保留已打开的流。 */
  public void drain() {
    synchronized (lock) {
      draining = true;
    }
  }

  /**
   * 模拟会话断开：全部子流的未完成操作以 {@link IOException} 失败。
   *
   * <p>断开同时视为排空，后续新建被拒绝。
   */
  public void disconnect() {
    List<FakeTunnelExchange> pendingExchanges;
    List<FakeTunnelStream> pendingStreams;
    synchronized (lock) {
      disconnected = true;
      draining = true;
      pendingExchanges = List.copyOf(exchanges);
      pendingStreams = List.copyOf(streams);
    }
    IOException failure = new IOException("environment tunnel disconnected");
    List<CompletableFuture<?>> headers = new ArrayList<>();
    for (FakeTunnelExchange exchange : pendingExchanges) {
      CompletableFuture<TunnelExchange.Response> response = exchange.responseFuture();
      if (!response.isDone()) {
        headers.add(response);
      }
    }
    for (CompletableFuture<?> header : headers) {
      header.completeExceptionally(new IOException(failure));
    }
    for (FakeTunnelStream stream : pendingStreams) {
      stream.fail(failure);
    }
  }

  @Override
  public TunnelExchange openHttp(TunnelHttpRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }
    FakeTunnelExchange exchange = new FakeTunnelExchange(request, new FakeTunnelStream());
    synchronized (lock) {
      ensureOpen();
      exchanges.add(exchange);
      streams.add(exchange.fakeStream());
    }
    return exchange;
  }

  @Override
  public TunnelExchange openWebSocket(TunnelHttpRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }
    if (!"GET".equals(request.method())) {
      throw new IllegalArgumentException("webSocket request method must be GET");
    }
    FakeTunnelExchange exchange = new FakeTunnelExchange(request, new FakeTunnelStream());
    synchronized (lock) {
      ensureOpen();
      exchanges.add(exchange);
      streams.add(exchange.fakeStream());
    }
    return exchange;
  }

  /** 本连接句柄上按打开顺序捕获的 HTTP 与 WebSocket 交换。 */
  public List<FakeTunnelExchange> exchanges() {
    synchronized (lock) {
      return List.copyOf(exchanges);
    }
  }

  /** 当前仍由本连接句柄持有的流，包括已关闭的历史句柄。 */
  public List<TunnelStream> streams() {
    synchronized (lock) {
      return List.copyOf(streams);
    }
  }

  private void ensureOpen() {
    if (disconnected) {
      throw new IllegalStateException("environment tunnel is disconnected");
    }
    if (draining) {
      throw new IllegalStateException("environment tunnel is draining");
    }
  }
}
