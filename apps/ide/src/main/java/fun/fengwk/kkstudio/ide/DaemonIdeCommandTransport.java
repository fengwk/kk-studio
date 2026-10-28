package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonIdeLease;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.harness.environment.server.DaemonIdeCall;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.ide.protocol.DaemonIdeCodec;
import fun.fengwk.kkstudio.ide.protocol.IdeCommand;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link IdeCommandTransport} 的 Daemon 实现：经内核的 IDE 通道收发命令，IDE 载荷在此编解码。
 *
 * <p>内核只负责准入、在途登记与回执匹配；载荷不合法时只让这一条 IDE 调用失败，不影响 Daemon 连接上的 Agent 能力调用。
 */
public class DaemonIdeCommandTransport implements IdeCommandTransport {

  private final EnvironmentDaemonServer server;
  private final DaemonIdeCodec codec = new DaemonIdeCodec();

  public DaemonIdeCommandTransport(EnvironmentDaemonServer server) {
    this.server = Objects.requireNonNull(server, "server");
  }

  @Override
  public IdeRequest requestIde(EnvironmentId environmentId, IdeCommand command) {
    DaemonIdeCall call = server.requestIde(environmentId, codec.encodeCommand(command));
    return new IdeRequest(
        call.identity(), call.invocationId(), call.reply().thenApply(codec::decodeReply));
  }

  @Override
  public void abandonIde(UUID invocationId) {
    server.abandonIde(invocationId);
  }

  @Override
  public Optional<DaemonConnectionIdentity> connectionIdentity(EnvironmentId environmentId) {
    return server.connectionIdentity(environmentId);
  }

  @Override
  public void sendIdeLease(EnvironmentId environmentId, DaemonIdeLease lease) {
    server.sendIdeLease(environmentId, lease);
  }
}
