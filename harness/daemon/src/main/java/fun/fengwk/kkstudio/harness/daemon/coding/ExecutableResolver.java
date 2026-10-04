package fun.fengwk.kkstudio.harness.daemon.coding;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
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
 *
 * <p>路径来源非法（例如含宿主不允许的字符）只收敛为空结果，不抛出携带原始取值的异常；命中的 PATH 条目一律解析为绝对路径， 避免子进程按自身 workdir 误解析相对程序。
 */
public final class ExecutableResolver {

  private static final String DEFAULT_PATHEXT = ".EXE;.CMD;.BAT;.COM";

  private ExecutableResolver() {}

  /**
   * 用当前宿主环境解析可执行程序。
   *
   * @param command 配置或默认的命令名/路径
   * @return 命中时的绝对宿主路径；找不到、不可执行或路径来源非法时为空
   */
  public static Optional<String> resolve(String command) {
    return resolve(command, isWindows(), System.getenv("PATH"), System.getenv("PATHEXT"));
  }

  /** 注入宿主环境的解析入口：便于确定性测试 Windows/PATH/PATHEXT 语义，不读进程全局状态。 */
  static Optional<String> resolve(
      String command, boolean windows, String pathVariable, String pathext) {
    Objects.requireNonNull(command, "command");
    String expanded = expandHome(command);
    if (expanded.indexOf('/') < 0 && expanded.indexOf('\\') < 0) {
      return resolveOnPath(expanded, windows, pathVariable, pathext);
    }
    Path path;
    try {
      path = Path.of(expanded);
    } catch (InvalidPathException error) {
      return Optional.empty();
    }
    if (!path.isAbsolute()) {
      return Optional.empty();
    }
    return isRunnable(path, windows) ? Optional.of(path.toString()) : Optional.empty();
  }

  private static Optional<String> resolveOnPath(
      String command, boolean windows, String pathVariable, String pathext) {
    if (pathVariable == null || pathVariable.isBlank()) {
      return Optional.empty();
    }
    for (String element : pathVariable.split(Pattern.quote(File.pathSeparator))) {
      if (element.isBlank()) {
        continue;
      }
      Path directory;
      try {
        // 相对 PATH 条目按进程 cwd 解析为绝对路径：子进程可能使用不同 workdir 解析相对程序。
        directory = Path.of(expandHome(element)).toAbsolutePath().normalize();
      } catch (InvalidPathException error) {
        continue;
      }
      Optional<String> candidate =
          windows ? resolveWindows(directory, command, pathext) : resolvePosix(directory, command);
      if (candidate.isPresent()) {
        return candidate;
      }
    }
    return Optional.empty();
  }

  private static Optional<String> resolvePosix(Path directory, String command) {
    Path candidate = directory.resolve(command);
    return Files.isRegularFile(candidate) && Files.isExecutable(candidate)
        ? Optional.of(candidate.toString())
        : Optional.empty();
  }

  /**
   * Windows 语义：命令已带被识别的 {@code PATHEXT} 后缀（大小写不敏感）时按原样解析，否则追加后缀查找。
   *
   * <p>这样 {@code bash.exe} 不会被误拼成 {@code bash.exe.EXE}。
   */
  private static Optional<String> resolveWindows(Path directory, String command, String pathext) {
    List<String> suffixes = windowsExecutableSuffixes(pathext);
    if (hasRecognizedSuffix(command, suffixes)) {
      Path candidate = directory.resolve(command);
      return Files.isRegularFile(candidate) ? Optional.of(candidate.toString()) : Optional.empty();
    }
    for (String suffix : suffixes) {
      Path candidate = directory.resolve(command + suffix);
      if (Files.isRegularFile(candidate)) {
        return Optional.of(candidate.toString());
      }
    }
    return Optional.empty();
  }

  private static boolean hasRecognizedSuffix(String command, List<String> suffixes) {
    for (String suffix : suffixes) {
      if (command.length() >= suffix.length()
          && command.regionMatches(
              true, command.length() - suffix.length(), suffix, 0, suffix.length())) {
        return true;
      }
    }
    return false;
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

  private static boolean isRunnable(Path candidate, boolean windows) {
    return Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate));
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
