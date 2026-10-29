package fun.fengwk.kkstudio.harness.runtime.driver;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Stop 时 Driver 的收敛事实。
 *
 * @param barrierEntryId Driver 已追加的取消/终止 Barrier；无需追加时为 {@code null}
 * @param executionIds 需要在提交后 best-effort 取消的本地执行身份
 */
public record DriverStopResult(UUID barrierEntryId, List<UUID> executionIds) {

  public DriverStopResult {
    executionIds = List.copyOf(Objects.requireNonNull(executionIds, "executionIds"));
  }
}
