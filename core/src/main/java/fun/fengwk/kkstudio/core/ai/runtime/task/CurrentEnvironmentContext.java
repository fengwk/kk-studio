package fun.fengwk.kkstudio.core.ai.runtime.task;

import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonOperatingSystem;

import java.time.LocalDate;
import java.util.Objects;

/** 单次普通模型调用冻结的当前 Environment prompt 上下文。 */
public record CurrentEnvironmentContext(
    EnvironmentName name,
    DaemonOperatingSystem operatingSystem,
    LocalDate currentDate,
    String note) {

  public CurrentEnvironmentContext {
    currentDate = Objects.requireNonNull(currentDate, "currentDate");
    note = note == null ? null : DaemonEnvironmentInfo.validateNote(note);
    if (name == null && (operatingSystem != null || note != null)) {
      throw new IllegalArgumentException("unselected environment context must not carry metadata");
    }
    if ((operatingSystem == null) != (note == null)) {
      throw new IllegalArgumentException("operatingSystem and note must be present together");
    }
  }
}
