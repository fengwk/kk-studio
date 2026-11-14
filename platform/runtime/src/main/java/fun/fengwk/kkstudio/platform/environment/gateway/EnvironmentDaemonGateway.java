package fun.fengwk.kkstudio.platform.environment.gateway;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServer;

import java.util.Objects;

/** Environment daemon 的 Platform 产品适配器：将环境管理桥接到纯 Java 会话核心 {@link EnvironmentDaemonServer}。 */
@Service
public class EnvironmentDaemonGateway {

  private final EnvironmentDaemonServer server;

  public EnvironmentDaemonGateway(EnvironmentDaemonServer server) {
    this.server = Objects.requireNonNull(server, "server");
  }

  public EnvironmentDaemonServer server() {
    return server;
  }
}
