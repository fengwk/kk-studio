package fun.fengwk.kkstudio.harness.environment.daemon;

import java.util.Objects;

/**
 * HEARTBEAT payload：完整 runtimeStatus 与可空的 IDE 使用租约轮次。
 *
 * <p>{@code ideLeaseRound} 是 Daemon 当前轮次的规范正十进制字符串；没有视图时为 null。该轮次只用于下一批 IDE_LEASE 业务， 本层只做严格 wire
 * 校验与透传。
 */
public record DaemonHeartbeat(DaemonRuntimeSnapshot runtimeStatus, Long ideLeaseRound) {

  public DaemonHeartbeat {
    runtimeStatus = Objects.requireNonNull(runtimeStatus, "runtimeStatus");
    if (ideLeaseRound != null && ideLeaseRound <= 0) {
      throw new IllegalArgumentException("ideLeaseRound must be a positive long");
    }
  }
}
