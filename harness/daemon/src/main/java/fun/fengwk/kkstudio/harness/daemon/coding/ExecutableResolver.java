package fun.fengwk.kkstudio.harness.daemon.coding;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 宿主可执行程序的只读解析：绝对/含分隔符路径、裸命令的 PATH 查找、HOME 前缀展开与 Windows PATHEXT 后缀。
 *
 * <p>只做文件系统探测，绝不启动进程。LSP 服务器命令与 Daemon 配置的 bash 共用同一解析， 保证能力执行时使用的就是这里判定为可用的宿主路径。
 */
public final class ExecutableResolver {

  private static final String DEFAULT_PATHEXT = ".EXE;.CMD;.BAT;.COM";

  private ExecutableResolver() {}

  /**
   * 解析宿主可执行程序。
   *
   * <p>含分隔符的命令必须是绝对路径且为可执行文件；裸命令按 {@code PATH} 查找，Windows 上还要求命中 {@code PATHEXT} 后缀。
   *
   * @param command 配置或默认的命令名/路径
   * @return 命中时的宿主路径；找不到或不可执行时为空
   */
  public static Optional<String> resolve(String command) {
    Objects.requireNonNull(command, "command");
    String expanded = expandHome(command);
    if (expanded.indexOf('/') < 0 && expanded.indexOf('\\') < 0) {
      return resolveOnPath(expanded);
    }
    Path path = Path.of(expanded);
    if (!path.isAbsolute()) {
      return Optional.empty();
    }
    return isRunnable(path) ? Optional.of(path.toString()) : Optional.empty();
  }

  private static Optional<String> resolveOnPath(String command) {
    String path = System.getenv("PATH");
    if (path == null || path.isBlank()) {
      return Optional.empty();
    }
    boolean windows = isWindows();
    for (String element : path.split(Pattern.quote(File.pathSeparator))) {
      if (element.isBlank()) {
        continue;
      }
      Path directory = Path.of(expandHome(element));
      if (!windows) {
        Path candidate = directory.resolve(command);
        if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
          return Optional.of(candidate.toString());
        }
        continue;
      }
      for (String suffix : windowsExecutableSuffixes(System.getenv("PATHEXT"))) {
        Path candidate = directory.resolve(command + suffix);
        if (Files.isRegularFile(candidate)) {
          return Optional.of(candidate.toString());
        }
      }
    }
    return Optional.empty();
  }

  /** Windows 上裸命令必须命中 {@code PATHEXT} 后缀，普通文本文件不算可执行程序。 */
  static List<String> windowsExecutableSuffixes(String pathext) {
    String value = pathext == null || pathext.isBlank() ? DEFAULT_PATHEXT : pathext;
    List<String> suffixes = new ArrayList<>();
    for (String suffix : value.split(";")) {
      String trimmed = suffix.trim();
      if (!trimmed.isEmpty()) {
        suffixes.add(trimmed.startsWith(".") ? trimmed : "." + trimmed);
      }
    }
    return suffixes;
  }

  private static boolean isRunnable(Path candidate) {
    return Files.isRegularFile(candidate) && (isWindows() || Files.isExecutable(candidate));
  }

  static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  /** 展开 {@code ~}、{@code ~/}、{@code $HOME/} 与 {@code ${HOME}/} 前缀。 */
  static String expandHome(String value) {
    Objects.requireNonNull(value, "value");
    String home = System.getProperty("user.home", "");
    if (value.equals("~")) {
      return home;
    }
    if (value.startsWith("~/") || value.startsWith("~\\")) {
      return home + value.substring(1);
    }
    if (value.startsWith("$HOME/") || value.startsWith("$HOME\\")) {
      return home + value.substring("$HOME".length());
    }
    if (value.startsWith("${HOME}/") || value.startsWith("${HOME}\\")) {
      return home + value.substring("${HOME}".length());
    }
    return value;
  }
}
