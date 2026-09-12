package fun.fengwk.kkstudio.harness.environment.daemon;

import java.util.Objects;

/**
 * 目标 Daemon 操作系统上的 workdir 纯词法校验器。
 *
 * <p>workdir 是具体工具 arguments 中的必填字段，表示目标 Daemon 文件系统上的绝对目录。本类只按冻结的 {@link DaemonOperatingSystem}
 * 判断文本形状，不解析 Backend 本机路径（远端路径与 Backend 文件系统无关），也不做任何 {@code ~}、 {@code $VAR}/{@code ${VAR}}/{@code
 * %VAR%} 展开：未展开的占位符一律拒绝，避免把字面量当作目录使用。真实存在性、目录类型与可读性由 Daemon 用自己的 {@code Path} 校验。
 *
 * <p>Unix 形态（Linux/macOS/WSL）接受任何以 {@code /} 开头的绝对路径。Windows 形态接受带根目录的 drive path（{@code C:\dir}、
 * {@code C:/dir}、{@code C:\}、{@code C:/}）或 UNC（{@code \\server\share}、{@code //server/share}），拒绝
 * drive-relative（{@code C:dir}）与 root-relative（{@code \dir}、{@code /dir}）路径。
 */
public final class DaemonWorkdirSyntax {

  /** workdir 的 UTF-16 字符数上限，与持久化列和 wire 契约共用。 */
  public static final int MAX_LENGTH = 2048;

  private DaemonWorkdirSyntax() {}

  /**
   * 校验 {@code workdir} 是目标 OS 上的显式绝对目录文本，并返回按 {@code '/'} 统一分隔符后的规范文本。
   *
   * @param workdir 具体工具 arguments 中的 workdir 原始文本
   * @param operatingSystem 该连接 READY 中冻结的目标 Daemon 操作系统
   * @return 校验通过并统一分隔符后的路径文本
   * @throws IllegalArgumentException workdir 缺失、空白或形状与目标 OS 不符时抛出
   */
  public static String requireAbsolute(String workdir, DaemonOperatingSystem operatingSystem) {
    Objects.requireNonNull(operatingSystem, "operatingSystem");
    if (workdir == null || workdir.isBlank()) {
      throw new IllegalArgumentException("workdir must be a non-blank absolute directory path");
    }
    if (!workdir.equals(workdir.strip())) {
      throw new IllegalArgumentException("workdir must not have surrounding whitespace");
    }
    if (workdir.length() > MAX_LENGTH) {
      throw new IllegalArgumentException("workdir must be at most " + MAX_LENGTH + " characters");
    }
    if (workdir.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("workdir must not contain control characters");
    }
    rejectUnexpandedPlaceholder(workdir);
    if (operatingSystem == DaemonOperatingSystem.WINDOWS) {
      return unifySeparators(requireWindowsAbsolute(workdir));
    }
    return requireUnixAbsolute(workdir);
  }

  /** Unix 形态只要求以 {@code /} 开头；其余形状（含 {@code //} 前缀）交给目标文件系统处理。 */
  private static String requireUnixAbsolute(String workdir) {
    if (!workdir.startsWith("/")) {
      throw new IllegalArgumentException(
          "workdir must be an absolute path on the target daemon: " + workdir);
    }
    return workdir;
  }

  private static String requireWindowsAbsolute(String workdir) {
    if (isUncPath(workdir)) {
      return workdir;
    }
    if (isDriveAbsolutePath(workdir)) {
      return workdir;
    }
    if (hasDriveLetter(workdir)) {
      throw new IllegalArgumentException(
          "workdir must not be a drive-relative path on the target daemon: " + workdir);
    }
    throw new IllegalArgumentException(
        "workdir must be an absolute path on the target daemon: " + workdir);
  }

  private static boolean isUncPath(String workdir) {
    boolean backslashUnc = workdir.startsWith("\\\\");
    boolean slashUnc = workdir.startsWith("//");
    if (!backslashUnc && !slashUnc) {
      return false;
    }
    String remainder = workdir.substring(2);
    if (!hasServerAndShare(remainder)) {
      throw new IllegalArgumentException(
          "workdir must be a UNC path with a non-blank server and share: " + workdir);
    }
    return true;
  }

  private static boolean isDriveAbsolutePath(String workdir) {
    if (!hasDriveLetter(workdir)) {
      return false;
    }
    if (workdir.length() < 3) {
      return false;
    }
    char separator = workdir.charAt(2);
    if (separator != '/' && separator != '\\') {
      return false;
    }
    // 盘符根（C:\ 或 C:/）与更长路径同样合法；`//` 紧随盘符按普通重合分隔符处理。
    return true;
  }

  private static boolean hasDriveLetter(String workdir) {
    if (workdir.length() < 2) {
      return false;
    }
    char letter = workdir.charAt(0);
    boolean asciiLetter = (letter >= 'a' && letter <= 'z') || (letter >= 'A' && letter <= 'Z');
    return asciiLetter && workdir.charAt(1) == ':';
  }

  /** UNC 的前两段必须分别是非空白 server 与 share。 */
  private static boolean hasServerAndShare(String remainder) {
    String[] segments = remainder.split("[/\\\\]", -1);
    return segments.length >= 2 && !segments[0].isBlank() && !segments[1].isBlank();
  }

  /** 统一为 {@code '/'} 分隔，供跨平台词法比较与 gitignore pattern 使用。 */
  private static String unifySeparators(String workdir) {
    return workdir.indexOf('\\') < 0 ? workdir : workdir.replace('\\', '/');
  }

  /**
   * 只拒绝真正未展开的占位符：{@code $VAR}、{@code ${VAR}}、{@code %VAR%}。
   *
   * <p>含裸 {@code $} 或 {@code %} 的普通文件名不在拒绝范围内。
   */
  private static void rejectUnexpandedPlaceholder(String workdir) {
    if (workdir.startsWith("~/") || workdir.startsWith("~\\")) {
      throw new IllegalArgumentException("workdir must not use '~'; provide an expanded path");
    }
    if (containsDollarPlaceholder(workdir)) {
      throw new IllegalArgumentException(
          "workdir must not contain unexpanded environment variables: " + workdir);
    }
    if (containsPercentPlaceholder(workdir)) {
      throw new IllegalArgumentException(
          "workdir must not contain unexpanded environment variables: " + workdir);
    }
  }

  private static boolean containsDollarPlaceholder(String workdir) {
    int index = workdir.indexOf('$');
    while (index >= 0) {
      if (index + 1 < workdir.length()) {
        char next = workdir.charAt(index + 1);
        if (next == '{' || Character.isLetter(next) || next == '_') {
          return true;
        }
      }
      index = workdir.indexOf('$', index + 1);
    }
    return false;
  }

  private static boolean containsPercentPlaceholder(String workdir) {
    int index = workdir.indexOf('%');
    while (index >= 0) {
      int end = workdir.indexOf('%', index + 1);
      if (end > index + 1) {
        boolean identifier = true;
        for (int position = index + 1; position < end; position++) {
          char ch = workdir.charAt(position);
          if (!Character.isLetterOrDigit(ch) && ch != '_') {
            identifier = false;
            break;
          }
        }
        if (identifier) {
          return true;
        }
      }
      index = workdir.indexOf('%', index + 1);
    }
    return false;
  }
}
