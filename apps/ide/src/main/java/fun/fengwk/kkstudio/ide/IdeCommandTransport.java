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
 * IDE 元数据命令经 Daemon 的 IDE 通道下发，在 lease/READY 围栏下收发命令。
 *
 * <p>只暴露请求与放弃两条语义，使上层不直接接触会话核心的连接目录、锁与租约实现。
 */
public class IdeCommandTransport implements IdeCurrentGeneration {

  private final EnvironmentDaemonServer server;

  public IdeCommandTransport(EnvironmentDaemonServer server) {
    this.server = Objects.requireNonNull(server, "server");
  }

  /** 下发一条 IDE 命令并返回等待 {@code IDE_RESULT} 的句柄；未进入传输时抛类型化失败，绝不重放。 */
  public IdeRequest requestIde(EnvironmentId environmentId, IdeCommand command) {
    DaemonIdeCall call = server.requestIde(environmentId, command);
    return new IdeRequest(call.identity(), call.invocationId(), call.reply());
  }

  /** 放弃一条在途命令（超时/取消）；此后迟到的回执被安静忽略。 */
  public void abandonIde(UUID invocationId) {
    server.abandonIde(invocationId);
  }

  /**
   * 该 Environment 当前 READY 连接背后的 Daemon 实例代际；无本地连接/未 READY 时返回 empty。
   *
   * <p>IDE 入口与反向流据此把 cookie/流绑定到具体 Daemon 实例，并用 {@link IdeGenerationState} 裁决既有凭据。
   */
  public Optional<DaemonConnectionIdentity> connectionIdentity(EnvironmentId environmentId) {
    return server.connectionIdentity(environmentId);
  }

  /** 供授权/路由登记在登记锁内复核身份使用，与方法 {@link #connectionIdentity} 同源。 */
  @Override
  public Optional<DaemonConnectionIdentity> current(EnvironmentId environmentId) {
    return connectionIdentity(environmentId);
  }

  /** 向指定 READY Daemon 代际批量下发一轮使用证明；代际/租约已变化时抛不可用且不重放。 */
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
