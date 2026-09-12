package fun.fengwk.kkstudio.platform.environment.gateway;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;

import java.util.Objects;

/** Environment daemon 的 Platform 产品适配器：将环境管理桥接到纯 Java 会话核心 {@link EnvironmentDaemonServer}。 */
@Service
public class EnvironmentDaemonGateway {

  private final EnvironmentDaemonServer server;
  private final EnvironmentRegistry environmentRegistry;

  public EnvironmentDaemonGateway(
      EnvironmentDaemonServer server, EnvironmentRegistry environmentRegistry) {
    this.server = Objects.requireNonNull(server, "server");
    this.environmentRegistry = Objects.requireNonNull(environmentRegistry, "environmentRegistry");
  }

  public EnvironmentDaemonServer server() {
    return server;
  }

  public EnvironmentRegistry environmentRegistry() {
    return environmentRegistry;
  }
}
