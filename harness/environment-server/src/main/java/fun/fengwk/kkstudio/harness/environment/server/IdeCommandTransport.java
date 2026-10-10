package fun.fengwk.kkstudio.harness.environment.server;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeCommand;

import java.util.UUID;

/**
 * IDE 元数据命令的窄传输端口：由 {@link EnvironmentDaemonServer} 实现，供上层服务在 lease/READY 围栏下下发 IDE 命令。
 *
 * <p>只暴露请求与放弃两条语义，使上层不直接接触会话核心的连接目录、锁与租约实现。
 */
public interface IdeCommandTransport {

  /** 下发一条 IDE 命令并返回等待 {@code IDE_RESULT} 的句柄；未进入传输时抛类型化失败，绝不重放。 */
  IdeRequest requestIde(EnvironmentId environmentId, IdeCommand command);

  /** 放弃一条在途命令（超时/取消）；此后迟到的回执被安静忽略。 */
  void abandonIde(UUID invocationId);
}
