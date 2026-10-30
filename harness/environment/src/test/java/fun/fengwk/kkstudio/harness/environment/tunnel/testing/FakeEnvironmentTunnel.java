package fun.fengwk.kkstudio.harness.environment.tunnel.testing;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.harness.environment.tunnel.EnvironmentTunnel;
import fun.fengwk.kkstudio.harness.environment.tunnel.TunnelExchange;
import fun.fengwk.kkstudio.harness.environment.tunnel.TunnelHttpRequest;
import fun.fengwk.kkstudio.harness.environment.tunnel.TunnelStream;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** 固定在一个连接代际上的进程内隧道。 */
public final class FakeEnvironmentTunnel implements EnvironmentTunnel {

  private final DaemonConnectionIdentity identity;
  private final CopyOnWriteArrayList<FakeTunnelStream> streams = new CopyOnWriteArrayList<>();
  private volatile boolean draining;
  private volatile boolean disconnected;

  FakeEnvironmentTunnel(DaemonConnectionIdentity identity) {
    this.identity = Objects.requireNonNull(identity, "identity");
  }

  @Override
  public DaemonConnectionIdentity identity() {
    return identity;
  }

  /** 会话是否已排空。排空后 {@code local} 不再返回它，已有流不受影响。 */
  public boolean draining() {
    return draining;
  }

  /** 拒绝新建并保留已打开的流。 */
  public void drain() {
    draining = true;
  }

  /**
   * 模拟会话断开：全部子流的未完成操作以 {@link IOException} 失败。
   *
   * <p>断开同时视为排空，后续新建被拒绝。
   */
  public void disconnect() {
    disconnected = true;
    draining = true;
    IOException failure = new IOException("environment tunnel disconnected");
    for (FakeTunnelStream stream : streams) {
      stream.fail(failure);
    }
  }

  @Override
  public TunnelExchange openHttp(TunnelHttpRequest request) {
    Objects.requireNonNull(request, "request");
    ensureOpen();
    FakeTunnelExchange exchange = new FakeTunnelExchange(newStream());
    streams.add(exchange.fakeStream());
    return exchange;
  }

  @Override
  public TunnelStream openRaw(String target) {
    requireRawTarget(target);
    ensureOpen();
    FakeTunnelStream stream = newStream();
    streams.add(stream);
    return stream;
  }

  /** 当前仍由本代际持有的流，包括已关闭的历史句柄。 */
  public List<TunnelStream> streams() {
    return List.copyOf(streams);
  }

  private FakeTunnelStream newStream() {
    return new FakeTunnelStream();
  }

  private void ensureOpen() {
    if (disconnected) {
      throw new IllegalStateException("environment tunnel is disconnected");
    }
    if (draining) {
      throw new IllegalStateException("environment tunnel is draining");
    }
  }

  static void requireRawTarget(String target) {
    Objects.requireNonNull(target, "target");
    String prefix = "ide/";
    if (!target.startsWith(prefix) || target.indexOf('/', prefix.length()) >= 0) {
      throw new IllegalArgumentException("raw target must be ide/<canonical UUID>");
    }
    String rawId = target.substring(prefix.length());
    try {
      EnvironmentId.parse(rawId);
    } catch (RuntimeException error) {
      throw new IllegalArgumentException("raw target must be ide/<canonical UUID>", error);
    }
  }
}
