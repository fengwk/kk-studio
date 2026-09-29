package fun.fengwk.kkstudio.harness.daemon.coding;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

import fun.fengwk.kkstudio.harness.daemon.DaemonOperatingSystemDetector;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * POSIX 进程组原语：父进程与 scope helper 都只通过这里触达 libc。
 *
 * <p>JNA 的本地库按需加载：只有真正调用本类方法时才解析 {@code libc}，因此同一份单文件 Daemon 在 Windows 上启动不会 尝试加载 POSIX 动态库。
 *
 * <p>会话与进程组是 POSIX 侧的执行范围：helper 先 {@link #createSession()} 建立独立 session 与进程组，随后启动的命令与它
 * 的普通后代都自动归属该组，终止时一次组信号即可覆盖整组，而不会波及 daemon 自己所在的进程组。
 *
 * <p>「组还在不在」只有 {@code kill(-pgid, 0)} 可问，而它对僵尸同样成功：僵尸仍然是组成员，只是不再写输出、不再改副作用。 因此收敛判定使用 {@link
 * #hasLiveMember(long, long)}，它在 Linux 上进一步排除僵尸；其它 POSIX 平台没有等价的廉价手段，只能把「组
 * 仍然存在」当成「还没有收敛」——宁可报告未收敛，也不谎称已经收敛。
 */
final class PosixProcessGroup {

  /** 温和终止信号。 */
  static final int SIGTERM = 15;

  /** 不可忽略的强制终止信号。 */
  static final int SIGKILL = 9;

  /** {@link #hasLiveMember(long, long)} 中「没有进程需要排除」的取值。 */
  static final long NO_PROCESS = -1;

  /** POSIX {@code errno} ESRCH：目标进程或进程组不存在。 */
  private static final int ESRCH = 3;

  /** Linux {@code prctl} 的 {@code PR_SET_CHILD_SUBREAPER}。 */
  private static final int PR_SET_CHILD_SUBREAPER = 36;

  /** {@code waitpid} 的非阻塞选项。 */
  private static final int WNOHANG = 1;

  /** {@code SIG_IGN} 的原生取值，即 {@code (void *) 1}。 */
  private static final Pointer SIG_IGN = new Pointer(1);

  private static final Path PROC = Path.of("/proc");

  private PosixProcessGroup() {}

  /**
   * 让当前进程成为新 session 与进程组的 leader，从而把后续命令的 OS 所有权从 daemon 手里交给 helper。
   *
   * <p>失败（例如调用进程已是进程组 leader）必须显式报错：静默回退会让「整组收敛」的承诺失真，调用方宁可直接拒绝 本次执行。
   */
  static void createSession() {
    if (LibC.INSTANCE.setsid() < 0) {
      throw new IllegalStateException(
          "cannot create a POSIX session for the command scope: setsid failed with errno "
              + Native.getLastError());
    }
  }

  /** 当前进程组 id；{@link #createSession()} 成功之后等于当前进程 id。 */
  static long currentGroup() {
    return Integer.toUnsignedLong(LibC.INSTANCE.getpgrp());
  }

  /**
   * 让本进程忽略 {@code SIGTERM}。
   *
   * <p>只在 helper 已经进入收尾时使用：那时重复的温和信号不得打断强杀与收敛发布。启动阶段绝不调用它——{@code SIG_IGN} 会被 {@code fork} 继承且非交互
   * shell 无法覆盖，命令因此带着「进入时已被忽略」的信号开始，用户脚本的 trap 会静默失效。
   */
  static void ignoreTerminationSignal() {
    LibC.INSTANCE.signal(SIGTERM, SIG_IGN);
  }

  /**
   * 组内成员的快照项：pid 与它在快照时刻的身份（启动时刻）。
   *
   * <p>只带 pid 的快照无法对抗 pid 复用：调用方必须在强杀之前重新核验身份，因此快照把身份一起带出来。
   */
  record GroupMember(long pid, Instant start) {}

  /**
   * 组里当前活着（非僵尸）的成员的快照，排除 {@code excludedProcess}。
   *
   * <p>返回 {@code null} 表示「不可判定」：本平台没有 {@code /proc}（无法枚举），或者扫描本身失败。调用方必须把它当成
   * 「还没有收敛」，绝不可判定绝不等于收敛——但也绝不因此向任何 pid 发信号。身份（启动时刻）读不到的成员同样不进快照：只强杀 身份可核验的成员。
   */
  static List<GroupMember> membersSnapshot(long processGroup, long excludedProcess) {
    if (!isLinux()) {
      return null;
    }
    List<GroupMember> members = new ArrayList<>();
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(PROC)) {
      for (Path entry : entries) {
        long pid = parseProcessId(entry.getFileName().toString());
        if (pid <= 0 || pid == excludedProcess) {
          continue;
        }
        ProcessStat stat = readProcessStat(entry.resolve("stat"));
        if (stat == null || stat.group != processGroup || !isLiveState(stat.state)) {
          continue;
        }
        Instant start =
            ProcessHandle.of(pid).flatMap(handle -> handle.info().startInstant()).orElse(null);
        if (start != null) {
          members.add(new GroupMember(pid, start));
        }
      }
    } catch (IOException error) {
      // 扫描失败按「不可判定」处理：调用方会把它当成「还没有收敛」，绝不据此宣布收敛。
      return null;
    }
    return members;
  }

  /** 进程此刻所属的进程组；进程已经不存在或状态不可读时返回 {@code -1}。 */
  static long processGroupOf(long pid) {
    ProcessStat stat = readProcessStat(PROC.resolve(Long.toString(pid)).resolve("stat"));
    return stat == null ? -1 : stat.group;
  }

  /**
   * 让本进程的 stderr 指向 stdout：随后启动的命令因此把两路输出合并到同一条父进程捕获管道。
   *
   * <p>调用方必须先把 helper 自己的诊断引到别处，否则 helper 之后写到 stderr 的内容也会进入命令输出。
   */
  static void mergeStandardErrorIntoStandardOutput() {
    if (LibC.INSTANCE.dup2(1, 2) < 0) {
      throw new IllegalStateException(
          "cannot merge the command error stream into the captured output: dup2 failed with errno "
              + Native.getLastError());
    }
  }

  /**
   * 让 helper 成为子进程收养者（Linux {@code PR_SET_CHILD_SUBREAPER}）：命令的后代在它们的父进程消失后改由 helper 收养， helper 可以用
   * {@link #reapOrphans()} 立即回收，进程组不会因为「没有父进程回收」的僵尸而长期无法判定收敛。
   *
   * <p>非 Linux 平台没有这个能力（孤儿由 init/launchd 回收），返回 {@code false} 表示「没有启用」，而不是失败。
   */
  static boolean becomeChildSubreaper() {
    if (!isLinux()) {
      return false;
    }
    return LibC.INSTANCE.prctl(
            PR_SET_CHILD_SUBREAPER,
            new NativeLong(1),
            new NativeLong(0),
            new NativeLong(0),
            new NativeLong(0))
        == 0;
  }

  /**
   * 回收已经被收养的孤儿后代。
   *
   * <p>调用方必须等用户命令自己的退出码已经被 {@code java.lang.Process} 回收之后再调用：{@code waitpid(-1)} 会回收任意
   * 子进程，过早调用会偷走命令的退出码。
   */
  static void reapOrphans() {
    IntByReference status = new IntByReference();
    while (LibC.INSTANCE.waitpid(-1, status, WNOHANG) > 0) {
      // 循环回收所有已退出但还挂在进程表上的被收养后代。
    }
  }

  /**
   * 向整个进程组发信号。
   *
   * <p>返回 {@code true} 表示信号已经交付或进程组已经不存在（后者同样意味着收敛已经达成）；返回 {@code false} 表示信号 被拒绝且原因不是
   * ESRCH——这类错误绝不能被当成「已经收敛」。
   */
  static boolean signalGroup(long processGroup, int signal) {
    if (LibC.INSTANCE.kill((int) -processGroup, signal) == 0) {
      return true;
    }
    return Native.getLastError() == ESRCH;
  }

  /**
   * 进程组是否还有成员（僵尸也算成员）。
   *
   * <p>权限不足等不可判定的情形按「仍然存在」处理：不可判定必须表现为「还没有收敛」。
   */
  static boolean groupExists(long processGroup) {
    if (LibC.INSTANCE.kill((int) -processGroup, 0) == 0) {
      return true;
    }
    return Native.getLastError() != ESRCH;
  }

  /**
   * 进程组里是否还有「活着」的成员：僵尸不写输出、不改副作用，因此不算活着的成员。
   *
   * <p>{@code excludedProcess} 用来排除调用者自己——scope helper 是组长，它活着不代表命令还在运行。非 Linux 平台只能退回
   * 「组是否存在」，此时调用方应把「仍然存在」当作未收敛（僵尸会被 init 快速回收，因此这个保守判定通常不会真的阻塞）。
   */
  static boolean hasLiveMember(long processGroup, long excludedProcess) {
    if (!groupExists(processGroup)) {
      return false;
    }
    List<GroupMember> members = membersSnapshot(processGroup, excludedProcess);
    if (members == null) {
      // 没有 /proc 的平台，或扫描不可判定：只能把「组仍然存在」当成「还没有收敛」。僵尸会被 init 快速回收，因此这个保守判定
      // 通常不会真的阻塞；反过来谎称已经收敛会让收尾跳过强杀阶段。
      return true;
    }
    return !members.isEmpty();
  }

  /** {@code /proc/<pid>/stat} 的状态字段：{@code Z}/{@code X}/{@code x} 是不会再执行任何代码的成员。 */
  private static boolean isLiveState(char state) {
    return state != 'Z' && state != 'X' && state != 'x';
  }

  /** {@code /proc/<pid>/stat} 中本类需要的两个字段：状态与进程组 id。 */
  /** 解析 {@code /proc/<pid>/stat}：包级可见是为了让纯解析规则可以按真实样本回归（不改变任何行为）。 */
  static ProcessStat readProcessStat(Path stat) {
    try {
      String content = Files.readString(stat);
      // 格式为 "pid (comm) state ppid pgrp ..."，而 comm 可以包含空格与括号，因此从最后一个 ')' 之后开始解析。
      int end = content.lastIndexOf(')');
      if (end < 0) {
        return null;
      }
      String[] fields = content.substring(end + 1).trim().split("\\s+");
      if (fields.length < 3) {
        return null;
      }
      return new ProcessStat(fields[0].charAt(0), Long.parseLong(fields[2]));
    } catch (IOException | RuntimeException error) {
      return null;
    }
  }

  /** 解析 {@code /proc} 目录项里的 pid：包级可见是为了让解析规则可以按真实样本回归（不改变任何行为）。 */
  static long parseProcessId(String name) {
    try {
      return Long.parseLong(name);
    } catch (NumberFormatException error) {
      return -1;
    }
  }

  private static boolean isLinux() {
    DaemonOperatingSystem operatingSystem = DaemonOperatingSystemDetector.detectCurrent();
    return operatingSystem == DaemonOperatingSystem.LINUX
        || operatingSystem == DaemonOperatingSystem.WSL;
  }

  /** {@code /proc/<pid>/stat} 的投影。 */
  record ProcessStat(char state, long group) {}

  /** libc 绑定；只在 POSIX 平台访问，因此 Windows 上不会被初始化。 */
  interface LibC extends Library {

    LibC INSTANCE = Native.load("c", LibC.class);

    int setsid();

    int getpgrp();

    int kill(int pid, int signal);

    int dup2(int source, int target);

    int prctl(
        int option,
        NativeLong argument2,
        NativeLong argument3,
        NativeLong argument4,
        NativeLong argument5);

    int waitpid(int pid, IntByReference status, int options);

    Pointer signal(int signal, Pointer handler);
  }
}
