package fun.fengwk.kkstudio.platform.harness.task;

import fun.fengwk.kkstudio.harness.environment.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;

import java.time.LocalDate;
import java.util.Objects;

/** 单次普通模型调用冻结的当前 Environment prompt 上下文：完整 binding + 来自 live daemon 的 metadata。 */
public record CurrentEnvironmentContext(
    EnvironmentBinding binding,
    DaemonOperatingSystem operatingSystem,
    LocalDate currentDate,
    String note) {

  public CurrentEnvironmentContext {
    currentDate = Objects.requireNonNull(currentDate, "currentDate");
    note = note == null ? null : DaemonEnvironmentInfo.validateNote(note);
    if (binding == null && (operatingSystem != null || note != null)) {
      throw new IllegalArgumentException("unselected environment context must not carry metadata");
    }
    if ((operatingSystem == null) != (note == null)) {
      throw new IllegalArgumentException("operatingSystem and note must be present together");
    }
  }
}
