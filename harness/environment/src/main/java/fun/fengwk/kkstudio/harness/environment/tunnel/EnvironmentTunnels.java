package fun.fengwk.kkstudio.harness.environment.tunnel;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.util.Optional;

/**
 * 本节点 Environment 隧道目录。
 *
 * <p>只返回当前节点上 READY 且尚未排空的会话，不执行远端路由，也不代替调用方选择传输引擎。
 */
public interface EnvironmentTunnels {

  /**
   * 查找本节点可新建流的会话。
   *
   * @param environmentId 持久化 Environment 身份，不能为 {@code null}
   * @return 本节点 READY 且未排空的隧道；不存在、未 READY 或已排空时为空
   */
  Optional<EnvironmentTunnel> local(EnvironmentId environmentId);
}
