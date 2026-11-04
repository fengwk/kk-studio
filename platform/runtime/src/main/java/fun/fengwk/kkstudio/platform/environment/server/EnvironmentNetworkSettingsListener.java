package fun.fengwk.kkstudio.platform.environment.server;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;

/** 设置权威快照刷新后唤醒本节点的 Daemon 连接。 */
@Component
public class EnvironmentNetworkSettingsListener {
  private final EnvironmentDaemonServer server;

  public EnvironmentNetworkSettingsListener(EnvironmentDaemonServer server) {
    this.server = server;
  }

  @EventListener
  public void onNetworkSettings(SystemSettings.Network network) {
    server.reconcileNetworkSettings();
  }
}
