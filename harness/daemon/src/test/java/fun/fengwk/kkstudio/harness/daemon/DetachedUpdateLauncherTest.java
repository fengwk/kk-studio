package fun.fengwk.kkstudio.harness.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;

import java.nio.file.Path;
import java.util.List;

/**
 * 意图：分离更新器必须用 OS 级独立作业启动（systemd 瞬时单元 / launchd 瞬时 job / 一次性计划任务）， 而不是普通子进程；这里只断言命令形状，绝不真正触发替换或重启。
 */
class DetachedUpdateLauncherTest {

  private static final Path UPDATE_DIR = Path.of("/home/dev/.kk-studio/updates/op-1");
  private static final Path SCRIPT = UPDATE_DIR.resolve("kk-studio-daemon-update.sh");
  private static final Path STAGED = UPDATE_DIR.resolve("kk-studio-daemon-v1.0.9.jar");
  private static final Path INSTALLED = Path.of("/home/dev/.kk-studio/lib/kk-studio-daemon.jar");

  private static ManagedUpdateOutcome.Prepared prepared(Path script) {
    return new ManagedUpdateOutcome.Prepared("1.0.9", UPDATE_DIR, script, STAGED, INSTALLED);
  }

  @Test
  void linuxUsesSystemdTransientUnit() {
    List<String> command =
        DetachedUpdateLauncher.commandLine(DaemonOperatingSystem.LINUX, prepared(SCRIPT));
    assertEquals("systemd-run", command.get(0));
    assertTrue(command.contains("--user"));
    assertTrue(command.contains("--collect"));
    assertTrue(command.contains("--unit=" + DetachedUpdateLauncher.TRANSIENT_UNIT_PREFIX + "op-1"));
    assertEquals(
        List.of("bash", SCRIPT.toString(), STAGED.toString(), INSTALLED.toString()),
        command.subList(command.size() - 4, command.size()));
  }

  @Test
  void wslUsesSystemdTransientUnit() {
    assertEquals(
        DetachedUpdateLauncher.commandLine(DaemonOperatingSystem.LINUX, prepared(SCRIPT)),
        DetachedUpdateLauncher.commandLine(DaemonOperatingSystem.WSL, prepared(SCRIPT)));
  }

  @Test
  void macosUsesLaunchdTransientJob() {
    List<String> command =
        DetachedUpdateLauncher.commandLine(DaemonOperatingSystem.MACOS, prepared(SCRIPT));
    assertEquals("launchctl", command.get(0));
    assertEquals("submit", command.get(1));
    assertTrue(command.contains("-l"));
    assertTrue(command.contains(DetachedUpdateLauncher.TRANSIENT_UNIT_PREFIX + "op-1"));
    assertTrue(command.contains("--"));
  }

  @Test
  void windowsRegistersOneShotScheduledTask() {
    Path script = UPDATE_DIR.resolve("kk-studio-daemon-update.ps1");
    List<String> command =
        DetachedUpdateLauncher.commandLine(DaemonOperatingSystem.WINDOWS, prepared(script));
    assertEquals("powershell", command.get(0));
    String launchCommand = command.get(command.size() - 1);
    assertTrue(launchCommand.contains("Register-ScheduledTask"));
    assertTrue(launchCommand.contains("Start-ScheduledTask"));
    assertTrue(launchCommand.contains(DetachedUpdateLauncher.WINDOWS_UPDATE_TASK_PREFIX + "op-1"));
    assertTrue(launchCommand.contains("-File"));
    assertTrue(launchCommand.contains(STAGED.toString()));
  }
}
