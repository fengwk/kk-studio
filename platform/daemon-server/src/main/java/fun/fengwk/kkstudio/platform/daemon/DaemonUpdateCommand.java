package fun.fengwk.kkstudio.platform.daemon;

import fun.fengwk.kkstudio.common.id.Uuids;

import java.util.Objects;

/**
 * 一条受管更新命令：Platform 在专用管理通道上向 Daemon 下发唯一更新目标。
 *
 * <p>{@code operationId} 关联更新回执，{@code targetVersion} 指定更新目标。
 */
public record DaemonUpdateCommand(String operationId, String targetVersion) {

  public DaemonUpdateCommand {
    operationId = validateOperationId(operationId);
    targetVersion = DaemonCapabilities.validateDaemonVersion(targetVersion);
  }

  /**
   * 校验 operationId 为规范小写 UUID。
   *
   * <p>它会被用作磁盘暂存子目录名，因此必须是固定、无路径语义的规范 UUID；任何 {@code ../}、绝对路径、大小写或形态偏差都拒绝， 避免命令把更新写进数据目录之外。
   */
  public static String validateOperationId(String value) {
    Objects.requireNonNull(value, "operationId");
    try {
      Uuids.parseCanonical(value, "operationId");
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("operationId must be a canonical lowercase UUID", error);
    }
    return value;
  }
}
