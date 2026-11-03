package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.DaemonIdeLease;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeCommand;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeLease;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeLeaseBinding;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonIdeCall;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServer;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link IdeCommandTransport} 的 Daemon 实现：经内核的 IDE 通道收发命令。
 *
 * <p>内核只负责准入、在途登记与回执匹配；载荷不合法时只让这一条 IDE 调用失败，不影响 Daemon 连接上的 Agent 能力调用。
 */
public class DaemonIdeCommandTransport implements IdeCommandTransport {

  private final EnvironmentDaemonServer server;

  public DaemonIdeCommandTransport(EnvironmentDaemonServer server) {
    this.server = Objects.requireNonNull(server, "server");
  }

  @Override
  public IdeRequest requestIde(EnvironmentId environmentId, IdeCommand command) {
    DaemonIdeCall call = server.requestIde(environmentId, command);
    return new IdeRequest(call.identity(), call.invocationId(), call.reply());
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
  public void sendIdeLease(
      EnvironmentId environmentId,
      DaemonConnectionIdentity expectedIdentity,
      DaemonIdeLease lease) {
    IdeLease.Builder builder = IdeLease.newBuilder().setRound(lease.round());
    for (DaemonIdeLease.Binding binding : lease.bindings()) {
      builder.addBindings(
          IdeLeaseBinding.newBuilder()
              .setViewId(binding.viewId().toString())
              .setDocumentId(binding.documentId().toString())
              .setBindingEpoch(binding.bindingEpoch()));
    }
    server.sendIdeLease(environmentId, expectedIdentity, builder.build());
  }
}
