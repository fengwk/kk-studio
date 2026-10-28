package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonIdeLease;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.ide.protocol.IdeCommand;

import java.util.Optional;
import java.util.UUID;

/**
 * IDE 元数据命令的窄传输端口：由 {@link DaemonIdeCommandTransport} 经内核 IDE 通道实现，供 IDE 服务在 lease/READY 围栏下下发命令。
 *
 * <p>只暴露请求与放弃两条语义，使上层不直接接触会话核心的连接目录、锁与租约实现。
 */
public interface IdeCommandTransport {

  /** 下发一条 IDE 命令并返回等待 {@code IDE_RESULT} 的句柄；未进入传输时抛类型化失败，绝不重放。 */
  IdeRequest requestIde(EnvironmentId environmentId, IdeCommand command);

  /** 放弃一条在途命令（超时/取消）；此后迟到的回执被安静忽略。 */
  void abandonIde(UUID invocationId);

  /**
   * 该 Environment 当前 READY 连接背后的 Daemon 实例代际；无本地连接/未 READY 时返回 empty。
   *
   * <p>IDE 入口与反向流据此把 cookie/流绑定到具体 Daemon 实例，并用 {@link IdeGenerationState} 裁决既有凭据。
   */
  Optional<DaemonConnectionIdentity> connectionIdentity(EnvironmentId environmentId);

  /** 向指定 READY Daemon 代际批量下发一轮使用证明；代际/租约已变化时抛不可用且不重放。 */
  void sendIdeLease(
      EnvironmentId environmentId, DaemonConnectionIdentity expectedIdentity, DaemonIdeLease lease);
}
