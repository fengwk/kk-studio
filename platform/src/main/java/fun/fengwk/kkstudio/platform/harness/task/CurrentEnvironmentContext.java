package fun.fengwk.kkstudio.platform.harness.task;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 单次普通模型调用冻结的当前 Environment prompt 上下文：Environment 路由身份 + 来自 live daemon 或持久化 inventory 回退的
 * metadata。
 *
 * <p>当环境 daemon 在线且具备 ready lease 时优先使用 live daemon metadata；在无活跃连接或离线规划时回退使用持久化的 durable inventory
 * metadata（operatingSystem, timeZone, note）。
 */
public record CurrentEnvironmentContext(
    EnvironmentId environmentId,
    DaemonOperatingSystem operatingSystem,
    LocalDate currentDate,
    String note) {

  public CurrentEnvironmentContext {
    currentDate = Objects.requireNonNull(currentDate, "currentDate");
    note = note == null ? null : DaemonEnvironmentInfo.validateNote(note);
    if (environmentId == null && (operatingSystem != null || note != null)) {
      throw new IllegalArgumentException("unselected environment context must not carry metadata");
    }
  }
}
