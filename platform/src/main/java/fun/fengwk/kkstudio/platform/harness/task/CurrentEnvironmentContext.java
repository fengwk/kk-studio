package fun.fengwk.kkstudio.platform.harness.task;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 单次普通模型调用冻结的当前 Environment prompt 上下文：Environment 路由身份与 name，加上来自最近一次被接受的 READY 宿主 payload 的 展示事实。
 *
 * <p>name 与路由身份只来自 Branch 选中的 Environment 自身；{@code operatingSystem} / {@code userName} / {@code
 * homeDirectory} / {@code note} 只来自连接行保留的最近一次 READY payload，从未 READY 时全部为 null，绝不从 Platform
 * 主机或任何路径默认值回退。未选择 Environment 时上下文只保留 Platform 当前日期。
 */
public record CurrentEnvironmentContext(
    EnvironmentId environmentId,
    String environmentName,
    DaemonOperatingSystem operatingSystem,
    String userName,
    String homeDirectory,
    LocalDate currentDate,
    String note) {

  public CurrentEnvironmentContext {
    currentDate = Objects.requireNonNull(currentDate, "currentDate");
    environmentName = environmentName == null ? null : environmentName.strip();
    note = note == null ? null : DaemonEnvironmentInfo.validateNote(note);
    userName = userName == null ? null : DaemonEnvironmentInfo.validateUserName(userName);
    homeDirectory =
        homeDirectory == null
            ? null
            : operatingSystem == null
                ? DaemonEnvironmentInfo.validateHomeDirectory(homeDirectory)
                : DaemonEnvironmentInfo.validateHomeDirectory(homeDirectory, operatingSystem);
    if (environmentId == null) {
      if (environmentName != null
          || operatingSystem != null
          || userName != null
          || homeDirectory != null
          || note != null) {
        throw new IllegalArgumentException("unselected environment context must not carry facts");
      }
    } else if (environmentName == null || environmentName.isEmpty()) {
      throw new IllegalArgumentException("selected environment context must carry a name");
    }
  }

  /** 未选择 Environment：只有 Platform 当前日期，宿主事实全部省略。 */
  public static CurrentEnvironmentContext none(Instant now, ZoneId zone) {
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(zone, "zone");
    return new CurrentEnvironmentContext(
        null, null, null, null, null, now.atZone(zone).toLocalDate(), null);
  }

  /**
   * 已选择的 Environment：name 来自 Environment 自身，宿主事实只来自连接行保留的最近一次 READY payload。
   *
   * <p>日期用宿主报告的时区换算；未报告或不可解析时回退 Platform 时钟时区，绝不伪造宿主事实。
   */
  public static CurrentEnvironmentContext selected(
      EnvironmentId environmentId,
      String environmentName,
      DaemonEnvironmentInfo hostFacts,
      Instant now,
      ZoneId fallbackZone) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(fallbackZone, "fallbackZone");
    ZoneId zone = hostFacts == null ? fallbackZone : zoneOf(hostFacts.timeZone(), fallbackZone);
    return new CurrentEnvironmentContext(
        environmentId,
        environmentName,
        hostFacts == null ? null : hostFacts.operatingSystem(),
        hostFacts == null ? null : hostFacts.userName(),
        hostFacts == null ? null : hostFacts.homeDirectory(),
        now.atZone(zone).toLocalDate(),
        hostFacts == null ? null : hostFacts.note());
  }

  /** 宿主未报告时区或时区不可解析时确定性回退 Platform 时钟时区，绝不抛出；包内可见以便直接验证两条回退分支。 */
  static ZoneId zoneOf(String timeZone, ZoneId fallback) {
    if (timeZone == null || timeZone.isBlank()) {
      return fallback;
    }
    try {
      return ZoneId.of(timeZone);
    } catch (DateTimeException error) {
      return fallback;
    }
  }
}
