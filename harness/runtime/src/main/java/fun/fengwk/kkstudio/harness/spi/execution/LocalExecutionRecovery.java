package fun.fengwk.kkstudio.harness.spi.execution;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 上一进程日志的首轮恢复结果。
 *
 * @param ownerExited 已确认 {@code OWNER_EXITED} 的本地执行
 * @param cleaned 数据库已有结论或从未登记，因而清理掉 PREPARED 的执行
 * @param retained 本轮无法安全处理的记录（身份冲突、损坏文件或数据库瞬态失败），原样保留并等待重试或人工处理
 */
public record LocalExecutionRecovery(
    List<UUID> ownerExited, List<UUID> cleaned, List<UUID> retained) {

  public LocalExecutionRecovery {
    ownerExited = List.copyOf(Objects.requireNonNull(ownerExited, "ownerExited"));
    cleaned = List.copyOf(Objects.requireNonNull(cleaned, "cleaned"));
    retained = List.copyOf(Objects.requireNonNull(retained, "retained"));
  }
}
