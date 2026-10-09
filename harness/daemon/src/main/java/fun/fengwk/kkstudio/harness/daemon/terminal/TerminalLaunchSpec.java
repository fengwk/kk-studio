package fun.fengwk.kkstudio.harness.daemon.terminal;

import fun.fengwk.kkstudio.harness.daemon.coding.ExecutableResolver;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.share.ai.environment.DaemonTerminalConfiguration;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * 已解析的人工终端启动规格：可执行程序、按原样组成的 argv 与绝对规范化工作目录。
 *
 * <p>解析只在配置读取时发生一次：显式 executable/workdir 立即只读校验并失败关闭；缺省时按宿主 OS 选择 shell、工作目录取 user.home。 运行失败后不换
 * shell，也不把参数拼接成 shell 字符串。args 可能包含秘密，因此 {@link #toString()} 不输出 argv。
 */
public record TerminalLaunchSpec(String executable, List<String> args, Path workdir) {

  /** unix 未设置或为空 {@code $SHELL} 时的回退；仍必须能解析，不能静默使用不存在的程序。 */
  static final String DEFAULT_UNIX_SHELL = "/bin/sh";

  private static final List<String> WINDOWS_SHELLS = List.of("pwsh", "powershell", "cmd");

  public TerminalLaunchSpec {
    if (executable == null || executable.isBlank()) {
      throw new IllegalArgumentException("executable must be a nonblank string");
    }
    if (args == null) {
      throw new IllegalArgumentException("args must not be null");
    }
    args = List.copyOf(args);
    if (workdir == null || !workdir.isAbsolute()) {
      throw new IllegalArgumentException("workdir must be an absolute path");
    }
    workdir = workdir.normalize();
  }

  /** 生产入口：读取当前宿主 OS、{@code $SHELL}、user.home 与只读可执行解析；不启动进程。 */
  public static TerminalLaunchSpec resolve(
      DaemonTerminalConfiguration configuration, DaemonOperatingSystem operatingSystem) {
    return resolve(
        configuration,
        operatingSystem,
        System.getenv("SHELL"),
        System.getProperty("user.home"),
        ExecutableResolver::resolve);
  }

  /** 确定性测试入口：OS/env/home/resolver 全部注入，不读进程全局状态。 */
  static TerminalLaunchSpec resolve(
      DaemonTerminalConfiguration configuration,
      DaemonOperatingSystem operatingSystem,
      String shellVariable,
      String home,
      Function<String, Optional<String>> resolver) {
    Objects.requireNonNull(operatingSystem, "operatingSystem");
    Objects.requireNonNull(resolver, "resolver");
    String configured = configuration == null ? null : configuration.getExecutable();
    String executable =
        configured != null
            ? resolveRequired(resolver, configured, "daemon.terminal.executable")
            : defaultExecutable(operatingSystem, shellVariable, resolver);
    List<String> args =
        configuration == null || configuration.getArgs() == null
            ? List.of()
            : List.copyOf(configuration.getArgs());
    String configuredWorkdir = configuration == null ? null : configuration.getWorkdir();
    Path workdir = workdir(configuredWorkdir != null ? configuredWorkdir : home);
    return new TerminalLaunchSpec(executable, args, workdir);
  }

  /** 参数可能包含秘密，文档化输出不暴露 argv。 */
  @Override
  public String toString() {
    return "TerminalLaunchSpec[executable="
        + executable
        + ", args=[redacted], workdir="
        + workdir
        + "]";
  }

  private static String defaultExecutable(
      DaemonOperatingSystem operatingSystem,
      String shellVariable,
      Function<String, Optional<String>> resolver) {
    if (operatingSystem == DaemonOperatingSystem.WINDOWS) {
      for (String candidate : WINDOWS_SHELLS) {
        Optional<String> resolved = resolver.apply(candidate);
        if (resolved.isPresent()) {
          return resolved.get();
        }
      }
      throw new IllegalArgumentException(
          "daemon.terminal.executable: no shell found among pwsh, powershell, cmd");
    }
    return resolveRequired(
        resolver,
        shellVariable != null && !shellVariable.isBlank() ? shellVariable : DEFAULT_UNIX_SHELL,
        "daemon.terminal.executable");
  }

  private static String resolveRequired(
      Function<String, Optional<String>> resolver, String command, String path) {
    return resolver
        .apply(command)
        .orElseThrow(() -> new IllegalArgumentException(path + ": must resolve to an executable"));
  }

  /** 显式 workdir 与默认 user.home 共用同一规则：非空白、绝对、现存的可读可执行目录。 */
  private static Path workdir(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(
          "daemon.terminal.workdir: must be an absolute accessible directory");
    }
    Path path;
    try {
      path = Path.of(value).normalize();
    } catch (InvalidPathException error) {
      throw new IllegalArgumentException(
          "daemon.terminal.workdir: must be an absolute accessible directory");
    }
    if (!path.isAbsolute()
        || !Files.isDirectory(path)
        || !Files.isReadable(path)
        || !Files.isExecutable(path)) {
      throw new IllegalArgumentException(
          "daemon.terminal.workdir: must be an absolute accessible directory");
    }
    return path;
  }
}
