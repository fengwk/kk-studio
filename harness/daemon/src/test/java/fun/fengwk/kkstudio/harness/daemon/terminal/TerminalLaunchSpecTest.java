package fun.fengwk.kkstudio.harness.daemon.terminal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.share.ai.environment.DaemonTerminalConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** 终端 launch spec 的确定性解析：显式值优先、默认值选择一次、失败关闭且不泄露取值或 argv。 */
class TerminalLaunchSpecTest {
  @TempDir Path workdir;

  /** 只读 resolver 桩：把命令解析成固定宿主路径，不读进程环境。 */
  private static final Function<String, Optional<String>> POSIX =
      command -> Optional.of("/resolved/" + command);

  @Test
  void resolvesExplicitSpecAndPreservesArgvExactly() {
    DaemonTerminalConfiguration configuration = new DaemonTerminalConfiguration();
    configuration.setExecutable("/usr/bin/zsh");
    List<String> args = new ArrayList<>(List.of("--login", "", "  spaced  "));
    configuration.setArgs(args);
    configuration.setWorkdir(workdir.toString());

    TerminalLaunchSpec spec =
        TerminalLaunchSpec.resolve(
            configuration, DaemonOperatingSystem.LINUX, "/bin/dash", "/ignored", POSIX);

    assertEquals("/resolved//usr/bin/zsh", spec.executable());
    assertEquals(List.of("--login", "", "  spaced  "), spec.args());
    assertEquals(workdir.toAbsolutePath().normalize(), spec.workdir());
    assertThrows(UnsupportedOperationException.class, () -> spec.args().add("mutate"));
    args.add("--later");
    assertEquals(3, spec.args().size(), "规格必须持有输入 argv 的不可变副本");
    assertFalse(spec.toString().contains("--login"), spec.toString());
    assertFalse(spec.toString().contains("spaced"), spec.toString());
  }

  @Test
  void unixDefaultsToResolvableShellThenSh() {
    DaemonTerminalConfiguration empty = new DaemonTerminalConfiguration();
    empty.setArgs(List.of());

    TerminalLaunchSpec fromShell =
        TerminalLaunchSpec.resolve(
            empty, DaemonOperatingSystem.LINUX, "/bin/zsh", workdir.toString(), POSIX);
    assertEquals("/resolved//bin/zsh", fromShell.executable());

    TerminalLaunchSpec fromBlank =
        TerminalLaunchSpec.resolve(
            empty, DaemonOperatingSystem.MACOS, "  ", workdir.toString(), POSIX);
    assertEquals("/resolved/" + TerminalLaunchSpec.DEFAULT_UNIX_SHELL, fromBlank.executable());

    TerminalLaunchSpec fromNull =
        TerminalLaunchSpec.resolve(
            null, DaemonOperatingSystem.WSL, null, workdir.toString(), POSIX);
    assertEquals("/resolved/" + TerminalLaunchSpec.DEFAULT_UNIX_SHELL, fromNull.executable());
    assertEquals(List.of(), fromNull.args());
  }

  @Test
  void unresolvedExecutableFailsClosedWithoutEchoingCommand() {
    DaemonTerminalConfiguration configuration = new DaemonTerminalConfiguration();
    configuration.setExecutable("secret-shell");
    IllegalArgumentException explicit =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                TerminalLaunchSpec.resolve(
                    configuration,
                    DaemonOperatingSystem.LINUX,
                    null,
                    workdir.toString(),
                    command -> Optional.empty()));
    assertTrue(explicit.getMessage().startsWith("daemon.terminal.executable"));
    assertFalse(explicit.getMessage().contains("secret-shell"), explicit.getMessage());

    IllegalArgumentException shell =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                TerminalLaunchSpec.resolve(
                    null,
                    DaemonOperatingSystem.LINUX,
                    "/secret/shell",
                    workdir.toString(),
                    command -> Optional.empty()));
    assertTrue(shell.getMessage().startsWith("daemon.terminal.executable"));
    assertFalse(shell.getMessage().contains("/secret/shell"), shell.getMessage());
  }

  @Test
  void windowsSelectsFirstResolvableShellWithoutRuntimeFallback() {
    List<String> tried = new ArrayList<>();
    Function<String, Optional<String>> resolver =
        command -> {
          tried.add(command);
          return command.equals("powershell")
              ? Optional.of("C:\\Windows\\powershell.exe")
              : Optional.empty();
        };
    TerminalLaunchSpec spec =
        TerminalLaunchSpec.resolve(
            null, DaemonOperatingSystem.WINDOWS, "/bin/sh", workdir.toString(), resolver);
    assertEquals("C:\\Windows\\powershell.exe", spec.executable());
    assertEquals(List.of("pwsh", "powershell"), tried);

    IllegalArgumentException none =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                TerminalLaunchSpec.resolve(
                    null,
                    DaemonOperatingSystem.WINDOWS,
                    null,
                    workdir.toString(),
                    command -> Optional.empty()));
    assertEquals(
        "daemon.terminal.executable: no shell found among pwsh, powershell, cmd",
        none.getMessage());
  }

  @Test
  void workdirDefaultsToHomeAndRequiresAccessibleAbsoluteDirectory() {
    TerminalLaunchSpec spec =
        TerminalLaunchSpec.resolve(
            null, DaemonOperatingSystem.LINUX, null, workdir.toString(), POSIX);
    assertEquals(workdir.toAbsolutePath().normalize(), spec.workdir());

    DaemonTerminalConfiguration lexical = new DaemonTerminalConfiguration();
    lexical.setWorkdir(workdir.resolve("absent/..").toString());
    assertEquals(
        workdir.toAbsolutePath().normalize(),
        TerminalLaunchSpec.resolve(
                lexical, DaemonOperatingSystem.LINUX, null, workdir.toString(), POSIX)
            .workdir());

    DaemonTerminalConfiguration relative = new DaemonTerminalConfiguration();
    relative.setExecutable("/bin/sh");
    relative.setWorkdir("relative/dir");
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                TerminalLaunchSpec.resolve(
                    relative, DaemonOperatingSystem.LINUX, null, workdir.toString(), POSIX));
    assertEquals(
        "daemon.terminal.workdir: must be an absolute accessible directory", error.getMessage());
    assertFalse(error.getMessage().contains("relative"), error.getMessage());

    DaemonTerminalConfiguration absent = new DaemonTerminalConfiguration();
    absent.setExecutable("/bin/sh");
    absent.setWorkdir(workdir.resolve("absent").toString());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TerminalLaunchSpec.resolve(
                absent, DaemonOperatingSystem.LINUX, null, workdir.toString(), POSIX));
  }

  @Test
  void recordRejectsInvalidComponentsWithFixedRules() {
    assertThrows(
        IllegalArgumentException.class, () -> new TerminalLaunchSpec(" ", List.of(), workdir));
    assertThrows(IllegalArgumentException.class, () -> new TerminalLaunchSpec("sh", null, workdir));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TerminalLaunchSpec("sh", List.of(), Path.of("relative")));
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> new TerminalLaunchSpec("sh", List.of("--secret"), Path.of("relative/secret")));
    assertFalse(error.getMessage().contains("secret"), error.getMessage());
    assertEquals(
        workdir.toAbsolutePath().normalize(),
        new TerminalLaunchSpec("sh", List.of(), workdir).workdir());
  }
}
