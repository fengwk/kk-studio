package fun.fengwk.kkstudio.harness.spi.execution;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次进程排空的结果。
 *
 * @param unfinishedExecutionIds deadline 前仍未结束的本地执行，按执行标识排序
 * @param retainsJournalLock 日志目录锁是否必须保留到 JVM 退出：仍有物理 body、资源未释放或退出证明未落账
 */
public record LocalExecutionDrain(List<UUID> unfinishedExecutionIds, boolean retainsJournalLock) {

  public LocalExecutionDrain {
    unfinishedExecutionIds =
        List.copyOf(Objects.requireNonNull(unfinishedExecutionIds, "unfinishedExecutionIds"));
  }

  /** 全部物理执行都已结束且退出证明已落账，可以释放日志目录锁。 */
  public boolean settled() {
    return unfinishedExecutionIds.isEmpty() && !retainsJournalLock;
  }
}
