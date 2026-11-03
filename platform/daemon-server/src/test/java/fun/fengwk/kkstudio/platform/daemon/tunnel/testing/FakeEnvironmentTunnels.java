package fun.fengwk.kkstudio.platform.daemon.tunnel.testing;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.platform.daemon.tunnel.EnvironmentTunnel;
import fun.fengwk.kkstudio.platform.daemon.tunnel.EnvironmentTunnels;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 只保存本节点显式登记会话的测试目录。
 *
 * <p>不执行远端路由。登记、排空和断开会话都由测试对端显式驱动。
 */
public final class FakeEnvironmentTunnels implements EnvironmentTunnels {

  private final ConcurrentHashMap<EnvironmentId, FakeEnvironmentTunnel> tunnels =
      new ConcurrentHashMap<>();

  /**
   * 登记一个本节点 READY 会话。
   *
   * @param identity 该连接代际，不能为 {@code null}
   * @return 新会话
   * @throws IllegalStateException 同一 Environment 已有未替换的登记
   */
  public FakeEnvironmentTunnel register(DaemonConnectionIdentity identity) {
    if (identity == null) {
      throw new IllegalArgumentException("identity must not be null");
    }
    FakeEnvironmentTunnel tunnel = new FakeEnvironmentTunnel(identity);
    if (tunnels.putIfAbsent(identity.environmentId(), tunnel) != null) {
      throw new IllegalStateException("environment already has a local tunnel");
    }
    return tunnel;
  }

  /**
   * 用新代际替换本节点登记。
   *
   * <p>旧句柄及其已打开的流保持原代际，不迁移。
   *
   * @param identity 新连接代际
   * @return 新会话
   */
  public FakeEnvironmentTunnel replace(DaemonConnectionIdentity identity) {
    if (identity == null) {
      throw new IllegalArgumentException("identity must not be null");
    }
    FakeEnvironmentTunnel tunnel = new FakeEnvironmentTunnel(identity);
    FakeEnvironmentTunnel previous = tunnels.put(identity.environmentId(), tunnel);
    if (previous != null) {
      previous.drain();
    }
    return tunnel;
  }

  /** 移除本节点登记；已打开的旧句柄保持可用，直到测试对端显式断开。 */
  public void remove(EnvironmentId environmentId) {
    if (environmentId == null) {
      throw new IllegalArgumentException("environmentId must not be null");
    }
    tunnels.remove(environmentId);
  }

  @Override
  public Optional<EnvironmentTunnel> local(EnvironmentId environmentId) {
    if (environmentId == null) {
      throw new IllegalArgumentException("environmentId must not be null");
    }
    FakeEnvironmentTunnel tunnel = tunnels.get(environmentId);
    if (tunnel == null || tunnel.draining()) {
      return Optional.empty();
    }
    return Optional.of(tunnel);
  }
}
