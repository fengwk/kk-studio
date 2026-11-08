package fun.fengwk.kkstudio.platform.daemon;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Objects;

/**
 * READY 中冻结的类型化宿主事实：OS、CPU 架构、时区、真实进程用户、canonical HOME 与操作系统临时目录。
 *
 * <p>{@code architecture}、{@code userName} 与 {@code homeDirectory} 只用于 Environment 和当前 Environment
 * Prompt 展示；{@code tempDirectory} 只用于当前 Environment Prompt 展示与工具指引，不构成 cwd、默认 workdir 或沙箱。
 */
public record DaemonHostInfo(
    DaemonOperatingSystem operatingSystem,
    String timeZone,
    String userName,
    String homeDirectory,
    String tempDirectory,
    String architecture) {

  /** 宿主架构标识上界；真实 {@code os.arch} 取值远小于该值。 */
  public static final int MAX_ARCHITECTURE_CHARS = 32;

  public DaemonHostInfo {
    operatingSystem = Objects.requireNonNull(operatingSystem, "operatingSystem");
    timeZone = validateTimeZone(timeZone);
    userName = validateUserName(userName);
    homeDirectory = validateHomeDirectory(homeDirectory, operatingSystem);
    tempDirectory = validateTempDirectory(tempDirectory);
    architecture = validateArchitecture(architecture);
  }

  /**
   * 校验 architecture 的展示结构边界：{@code null} 表示该宿主事实未知；否则必须是有界、无周边空白、单行且无控制字符的标识。
   *
   * <p>该值来自 Daemon 真实采集的宿主事实（{@code os.arch}）；未知事实用 {@code null} 表达，不填造取值。
   */
  private static String validateArchitecture(String value) {
    if (value == null) {
      return null;
    }
    if (value.isBlank()) {
      throw new IllegalArgumentException("architecture must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException("architecture must not have surrounding whitespace");
    }
    if (value.length() > MAX_ARCHITECTURE_CHARS) {
      throw new IllegalArgumentException(
          "architecture exceeds " + MAX_ARCHITECTURE_CHARS + " characters");
    }
    if (value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("architecture must not contain ISO control characters");
    }
    if (value
        .codePoints()
        .anyMatch(
            codePoint ->
                Character.getType(codePoint) == Character.LINE_SEPARATOR
                    || Character.getType(codePoint) == Character.PARAGRAPH_SEPARATOR)) {
      throw new IllegalArgumentException("architecture must be a single line");
    }
    return value;
  }

  /** 校验 userName 的展示结构边界：非空白、无周边空白、单行、无控制字符。 */
  public static String validateUserName(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("userName must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException("userName must not have surrounding whitespace");
    }
    if (value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("userName must not contain ISO control characters");
    }
    if (value
        .codePoints()
        .anyMatch(
            codePoint ->
                Character.getType(codePoint) == Character.LINE_SEPARATOR
                    || Character.getType(codePoint) == Character.PARAGRAPH_SEPARATOR)) {
      throw new IllegalArgumentException("userName must be a single line");
    }
    return value;
  }

  /**
   * 校验 homeDirectory 的展示结构边界，并按报告它的目标 OS 校验绝对路径形状。
   *
   * <p>禁止使用 Platform 本机 {@code Path} 解释远端路径；例如 Windows 的 {@code C:\Users\dev} 在 Linux Platform
   * 上仍是合法宿主事实。
   */
  public static String validateHomeDirectory(String value, DaemonOperatingSystem operatingSystem) {
    validateHomeDirectoryStructure(value, "homeDirectory");
    if (!DaemonWorkdirSyntax.isAbsolutePath(
        value, Objects.requireNonNull(operatingSystem, "operatingSystem"))) {
      throw new IllegalArgumentException("homeDirectory must be an absolute path");
    }
    return value;
  }

  /**
   * 校验 OS 暂不可用时的 homeDirectory 展示结构，接受任一支持 OS 的绝对路径形状。
   *
   * <p>仅用于可选宿主事实投影；READY wire 必须使用带明确目标 OS 的重载。
   */
  public static String validateHomeDirectory(String value) {
    validateHomeDirectoryStructure(value, "homeDirectory");
    if (!DaemonWorkdirSyntax.isAbsolutePath(value, DaemonOperatingSystem.LINUX)
        && !DaemonWorkdirSyntax.isAbsolutePath(value, DaemonOperatingSystem.WINDOWS)) {
      throw new IllegalArgumentException("homeDirectory must be an absolute path");
    }
    return value;
  }

  /**
   * 校验操作系统临时目录的展示结构：非空白、无周边空白、单行、无控制字符，且是任一支持 OS 的绝对路径。
   *
   * <p>tempDirectory 是 Daemon 上报的宿主事实，只用于模型可见的当前 Environment 展示与工具指引；它不构成沙箱或默认 workdir。
   */
  public static String validateTempDirectory(String value) {
    validateHomeDirectoryStructure(value, "tempDirectory");
    if (!DaemonWorkdirSyntax.isAbsolutePath(value, DaemonOperatingSystem.LINUX)
        && !DaemonWorkdirSyntax.isAbsolutePath(value, DaemonOperatingSystem.WINDOWS)) {
      throw new IllegalArgumentException("tempDirectory must be an absolute path");
    }
    return value;
  }

  private static void validateHomeDirectoryStructure(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must not have surrounding whitespace");
    }
    if (value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(field + " must not contain ISO control characters");
    }
    if (value
        .codePoints()
        .anyMatch(
            codePoint ->
                Character.getType(codePoint) == Character.LINE_SEPARATOR
                    || Character.getType(codePoint) == Character.PARAGRAPH_SEPARATOR)) {
      throw new IllegalArgumentException(field + " must be a single line");
    }
  }

  private static String validateTimeZone(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("timeZone must not be blank");
    }
    try {
      ZoneId.of(value);
      return value;
    } catch (DateTimeException error) {
      throw new IllegalArgumentException("timeZone must be a valid ZoneId ID", error);
    }
  }
}
