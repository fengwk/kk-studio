package fun.fengwk.kkstudio.harness.daemon;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 生产用分离更新器启动器：按 OS 选择一条独立于旧 Daemon 生命周期的 OS 级启动命令。
 *
 * <p>命令只负责把更新脚本交给 OS 作为独立作业运行，随后立即返回；真正的替换与重启由被启动的脚本完成。刻意不设计常驻进程、通用升级 引擎、自动回滚或隐藏提权。
 *
 * <ul>
 *   <li>Linux/WSL：{@code systemd-run --user} 建立独立短生命周期瞬时单元，停止旧服务不会连带杀死更新器。
 *   <li>macOS：{@code launchctl submit} 提交一个独立瞬时 job，独立于受管 LaunchAgent。
 *   <li>Windows：注册并启动一个一次性计划任务，独立于受管 Daemon 计划任务；更新脚本完成后自删该任务。
 * </ul>
 */
public final class DetachedUpdateLauncher implements ManagedUpdateLauncher {

  /** 启动命令自身必须尽快返回；超过该时间仍不退出视为启动失败。 */
  static final long LAUNCH_TIMEOUT_SECONDS = 15;

  /** Windows 一次性更新任务的固定名前缀。 */
  static final String WINDOWS_UPDATE_TASK_PREFIX = "kk-studio-daemon-update-";

  /** Linux 瞬时单元与 macOS 瞬时 job 的固定名前缀。 */
  static final String TRANSIENT_UNIT_PREFIX = "kk-studio-daemon-update-";

  private final DaemonOperatingSystem operatingSystem;

  public DetachedUpdateLauncher() {
    this(DaemonOperatingSystemDetector.detectCurrent());
  }

  DetachedUpdateLauncher(DaemonOperatingSystem operatingSystem) {
    this.operatingSystem = Objects.requireNonNull(operatingSystem, "operatingSystem");
  }

  @Override
  public boolean launch(ManagedUpdateOutcome.Prepared prepared) {
    Objects.requireNonNull(prepared, "prepared");
    List<String> command = commandLine(operatingSystem, prepared);
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
    builder.redirectErrorStream(true);
    try {
      Process process = builder.start();
      boolean exited = process.waitFor(LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (!exited) {
        process.destroyForcibly();
        return false;
      }
      return process.exitValue() == 0;
    } catch (IOException error) {
      return false;
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** 纯命令构造，便于按 OS 断言分离方式而不真正执行。 */
  static List<String> commandLine(
      DaemonOperatingSystem operatingSystem, ManagedUpdateOutcome.Prepared prepared) {
    String operationId = prepared.handoffDirectory().getFileName().toString();
    String script = prepared.updateScript().toString();
    String staged = prepared.stagedJar().toString();
    String installed = prepared.installedJar().toString();
    return switch (operatingSystem) {
      case LINUX, WSL -> List.of(
          "systemd-run",
          "--user",
          "--collect",
          "--unit=" + TRANSIENT_UNIT_PREFIX + operationId,
          "bash",
          script,
          staged,
          installed);
      case MACOS -> List.of(
          "launchctl",
          "submit",
          "-l",
          TRANSIENT_UNIT_PREFIX + operationId,
          "--",
          "bash",
          script,
          staged,
          installed);
      case WINDOWS -> List.of(
          "powershell",
          "-NoProfile",
          "-NonInteractive",
          "-ExecutionPolicy",
          "Bypass",
          "-Command",
          windowsOneShotTaskCommand(operationId, script, staged, installed));
    };
  }

  /** 注册并启动一个一次性计划任务：它是独立的 OS 作业，不随受管 Daemon 计划任务被结束。任务名与更新脚本约定一致，脚本完成后自删。 */
  private static String windowsOneShotTaskCommand(
      String operationId, String script, String staged, String installed) {
    String task = WINDOWS_UPDATE_TASK_PREFIX + operationId;
    String argument =
        "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File \""
            + script
            + "\" -StagedJar \""
            + staged
            + "\" -InstalledJar \""
            + installed
            + "\"";
    return "$a = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "
        + psQuote(argument)
        + "; "
        + "$t = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(10); "
        + "$p = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel Limited; "
        + "Register-ScheduledTask -TaskName "
        + psQuote(task)
        + " -Action $a -Trigger $t -Principal $p -Force | Out-Null; "
        + "Start-ScheduledTask -TaskName "
        + psQuote(task);
  }

  private static String psQuote(String value) {
    return "'" + value.replace("'", "''") + "'";
  }
}
