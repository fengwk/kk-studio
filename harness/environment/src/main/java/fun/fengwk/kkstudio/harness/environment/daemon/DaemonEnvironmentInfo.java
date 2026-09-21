package fun.fengwk.kkstudio.harness.environment.daemon;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Objects;

/**
 * READY 中冻结的类型化本地环境 metadata；只包含 prompt 所需的 OS、时区、真实进程用户、canonical HOME 与稳定说明。
 *
 * <p>{@code note} 会进入受信任的模型 SYSTEM Prompt，只能来自 Daemon 固定默认值或可信操作者配置，禁止包含凭证、秘密或不可信外部文本。格式校验与下游 XML
 * escape 不能替代这一信任边界。{@code userName} 与 {@code homeDirectory} 只用于 Card 和当前 Environment Prompt 展示，不构成
 * cwd、默认 workdir 或沙箱。
 */
public record DaemonEnvironmentInfo(
    DaemonOperatingSystem operatingSystem,
    String timeZone,
    String userName,
    String homeDirectory,
    String note) {

  public static final int MAX_NOTE_CHARS = 512;

  public DaemonEnvironmentInfo {
    operatingSystem = Objects.requireNonNull(operatingSystem, "operatingSystem");
    timeZone = validateTimeZone(timeZone);
    userName = validateUserName(userName);
    homeDirectory = validateHomeDirectory(homeDirectory, operatingSystem);
    note = validateNote(note);
  }

  /** 校验模型可见 note 的结构边界；调用方仍必须保证内容来自可信操作者且不含凭证、秘密或不可信文本。 */
  public static String validateNote(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("note must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException("note must not have surrounding whitespace");
    }
    if (value.length() > MAX_NOTE_CHARS) {
      throw new IllegalArgumentException("note exceeds " + MAX_NOTE_CHARS + " characters");
    }
    if (value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("note must not contain ISO control characters");
    }
    if (value
        .codePoints()
        .anyMatch(
            codePoint ->
                Character.getType(codePoint) == Character.LINE_SEPARATOR
                    || Character.getType(codePoint) == Character.PARAGRAPH_SEPARATOR)) {
      throw new IllegalArgumentException("note must be a single line");
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
    validateHomeDirectoryStructure(value);
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
    validateHomeDirectoryStructure(value);
    if (!DaemonWorkdirSyntax.isAbsolutePath(value, DaemonOperatingSystem.LINUX)
        && !DaemonWorkdirSyntax.isAbsolutePath(value, DaemonOperatingSystem.WINDOWS)) {
      throw new IllegalArgumentException("homeDirectory must be an absolute path");
    }
    return value;
  }

  private static void validateHomeDirectoryStructure(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("homeDirectory must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException("homeDirectory must not have surrounding whitespace");
    }
    if (value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("homeDirectory must not contain ISO control characters");
    }
    if (value
        .codePoints()
        .anyMatch(
            codePoint ->
                Character.getType(codePoint) == Character.LINE_SEPARATOR
                    || Character.getType(codePoint) == Character.PARAGRAPH_SEPARATOR)) {
      throw new IllegalArgumentException("homeDirectory must be a single line");
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
