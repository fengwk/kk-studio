package fun.fengwk.kkstudio.platform.harness.task;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.DaemonHostInfo;
import fun.fengwk.kkstudio.platform.daemon.DaemonOperatingSystem;

import java.util.Objects;

/**
 * 单次普通模型调用冻结的当前 Environment prompt 上下文：Environment 路由身份与 name，加上卡片备注与最近一次被接受的 READY 宿主 payload 的
 * 展示事实。
 *
 * <p>name、route identity 与 {@code note} 只来自 Environment 稳定记录；{@code operatingSystem} / {@code userName} / {@code
 * homeDirectory} / {@code tempDirectory} 只来自连接行保留的最近一次 READY payload，从未 READY 时全部为
 * null，绝不从 Platform 主机或任何路径默认值回退。未选择 Environment 时上下文只保留空身份。
 */
public record CurrentEnvironmentContext(
    EnvironmentId environmentId,
    String environmentName,
    DaemonOperatingSystem operatingSystem,
    String userName,
    String homeDirectory,
    String tempDirectory,
    String note) {

  public CurrentEnvironmentContext {
    environmentName = environmentName == null ? null : environmentName.strip();
    note = validateNote(note);
    userName = userName == null ? null : DaemonHostInfo.validateUserName(userName);
    homeDirectory =
        homeDirectory == null
            ? null
            : operatingSystem == null
                ? DaemonHostInfo.validateHomeDirectory(homeDirectory)
                : DaemonHostInfo.validateHomeDirectory(homeDirectory, operatingSystem);
    tempDirectory =
        tempDirectory == null ? null : DaemonHostInfo.validateTempDirectory(tempDirectory);
    if (environmentId == null) {
      if (environmentName != null
          || operatingSystem != null
          || userName != null
          || homeDirectory != null
          || tempDirectory != null
          || note != null) {
        throw new IllegalArgumentException("unselected environment context must not carry facts");
      }
    } else if (environmentName == null || environmentName.isEmpty()) {
      throw new IllegalArgumentException("selected environment context must carry a name");
    }
  }

  /** 未选择 Environment：不携带任何宿主事实。 */
  public static CurrentEnvironmentContext none() {
    return new CurrentEnvironmentContext(null, null, null, null, null, null, null);
  }

  /** 已选择的 Environment：name 与 note 来自 Environment 自身，宿主事实只来自连接行保留的最近一次 READY payload。 */
  public static CurrentEnvironmentContext selected(
      EnvironmentId environmentId,
      String environmentName,
      String note,
      DaemonHostInfo hostFacts) {
    Objects.requireNonNull(environmentId, "environmentId");
    return new CurrentEnvironmentContext(
        environmentId,
        environmentName,
        hostFacts == null ? null : hostFacts.operatingSystem(),
        hostFacts == null ? null : hostFacts.userName(),
        hostFacts == null ? null : hostFacts.homeDirectory(),
        hostFacts == null ? null : hostFacts.tempDirectory(),
        note);
  }

  private static String validateNote(String value) {
    if (value == null) {
      return null;
    }
    String note = value.strip();
    if (note.isEmpty()
        || note.codePoints().anyMatch(Character::isISOControl)
        || note.indexOf('\u2028') >= 0
        || note.indexOf('\u2029') >= 0
        || note.length() > 512) {
      throw new IllegalArgumentException("environment note is invalid");
    }
    return note;
  }
}
