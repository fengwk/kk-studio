package fun.fengwk.kkstudio.platform.daemon.server;

import fun.fengwk.kkstudio.platform.daemon.tunnel.EnvironmentTunnel;

/** Daemon 会话生命周期管理服务：负责准入裁决、会话开启与放弃。 */
public interface DaemonSessions {

  /**
   * 裁决 Daemon 握手请求。
   *
   * @param handshake 握手信息
   * @return 准入结果
   */
  DaemonAdmission admit(DaemonHandshake handshake);

  /**
   * 准入后未能建立控制流时释放相关分配；该方法必须是幂等的。
   *
   * @param admitted 已准入凭据
   */
  void abandon(DaemonAdmission.Admitted admitted);

  /**
   * 控制流建立后调用以开启会话；核心随即发送 SETTINGS。
   *
   * @param admitted 已准入凭据
   * @param control 出站控制流通道
   * @param tunnel 节点隧道实例
   * @return 已开启的环境会话
   */
  DaemonSession open(
      DaemonAdmission.Admitted admitted, DaemonControlChannel control, EnvironmentTunnel tunnel);
}
