package fun.fengwk.kkstudio.harness.daemon.process;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.platform.mac.SystemB;
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
 * POSIX **会话**原语：父进程与 scope helper 都只通过这里触达 libc / libproc。
 *
 * <p>收敛范围是会话而不是单个进程组：交互式 shell 的 job control 会为每个前台/后台作业建立自己的进程组，因此「只 kill helper
 * 所在的进程组」会漏掉其它作业组。helper 先成为新会话的 leader（管道模式用 {@code setsid}，PTY 模式由 pty4j 原生 {@code login_tty}
 * 完成），因此会话 id 恒等于 helper 的 pid；用户命令与它的普通后代都继承该会话。
 *
 * <p>成员枚举必须是真实的内核查询：Linux/WSL 读 {@code /proc/<pid>/stat} 的 session 字段；macOS 用 libproc {@code
 * proc_listpids} 枚举全部 pid 后逐个 {@code getsid(pid)} 判定归属，再用 {@code proc_pidinfo(PROC_PIDTBSDINFO)}
 * 取状态与启动身份。 两者都不猜测原生结构体的字节偏移（macOS 结构体布局交给 JNA 的 {@link SystemB} 定义）。没有真实成员查询的平台一律返回
 * 「不可判定」，绝不当成「已经收敛」。
 *
 * <p>身份（启动时刻）随快照带出：发信号之前必须重新核验「仍属于本会话」且「启动时刻未变」，因此 pid/sid 复用不会误杀无关进程。
 *
 * <p>「不可判定」与「没有成员」必须严格区分：只要有一个已确认属于本会话、仍活着的成员读不到身份，整次枚举就返回 {@code null}
 * （不可判定）。把这种情况过滤成空列表会把「查不到」谎报成「已经收敛」，那正是收敛判定最不能犯的错误。
 */
final class PosixProcessSession {

  /** 温和终止信号。 */
  static final int SIGTERM = 15;

  /** 不可忽略的强制终止信号。 */
  static final int SIGKILL = 9;

  /** 需要排除调用者时使用的「没有进程」取值。 */
  static final long NO_PROCESS = -1;

  /** Linux {@code prctl} 的 {@code PR_SET_CHILD_SUBREAPER}。 */
  private static final int PR_SET_CHILD_SUBREAPER = 36;

  /** {@code waitpid} 的非阻塞选项。 */
  private static final int WNOHANG = 1;

  /** {@code SIG_IGN} 的原生取值，即 {@code (void *) 1}。 */
  private static final Pointer SIG_IGN = new Pointer(1);

  private static final Path PROC = Path.of("/proc");

  /** macOS {@code pbi_status}：僵尸进程（{@code <sys/proc.h>} 的 {@code SZOMB}）。 */
  private static final int MAC_STATUS_ZOMBIE = 5;

  /** Darwin {@code getsid}：内核中找不到活进程。 */
  private static final int ESRCH = 3;

  /** macOS pid 枚举的安全上限：按几何增长重试时用来封顶，避免病态情况下无界循环。 */
  private static final int MAC_PID_LIMIT = 1 << 20;

  private PosixProcessSession() {}

  /**
   * 让当前进程成为新 session 与进程组的 leader（管道模式）。
   *
   * <p>PTY 模式下 pty4j 的原生 {@code login_tty} 已经完成同样的建立，调用方改用 {@link #currentSession()} / {@link
   * #currentGroup()} 验证身份，绝不重复调用本方法。
   *
   * <p>失败（例如调用进程已是进程组 leader）必须显式报错：静默回退会让「整会话收敛」的承诺失真。
   */
  static void createSession() {
    if (LibC.INSTANCE.setsid() < 0) {
      throw new IllegalStateException(
          "cannot create a POSIX session for the command scope: setsid failed with errno "
              + Native.getLastError());
    }
  }

  /** 当前进程的会话 id；helper 成为 leader 之后等于自身 pid。 */
  static long currentSession() {
    return Integer.toUnsignedLong(LibC.INSTANCE.getsid(0));
  }

  /** 当前进程组 id；{@link #createSession()} 或 pty4j 建立会话之后等于当前进程 id。 */
  static long currentGroup() {
    return Integer.toUnsignedLong(LibC.INSTANCE.getpgrp());
  }

  /** 文件描述符是否连接终端；PTY 模式下 helper 用它验证自己继承了伪终端从端。 */
  static boolean isTerminal(int fd) {
    return LibC.INSTANCE.isatty(fd) == 1;
  }

  /** 进程此刻所属的会话；进程不存在或本平台无查询能力时返回 {@code -1}。 */
  static long sessionOf(long pid) {
    if (isLinux()) {
      ProcessStat stat = readProcessStat(PROC.resolve(Long.toString(pid)).resolve("stat"));
      return stat == null ? -1 : stat.session();
    }
    if (isMac()) {
      int session = LibC.INSTANCE.getsid((int) pid);
      return session < 0 ? -1 : Integer.toUnsignedLong(session);
    }
    return -1;
  }

  /**
   * 进程此刻的启动身份（用于对抗 pid 复用）；进程不存在、已经是僵尸或读不到时返回 {@code null}。
   *
   * <p>枚举与重新核验必须用同一个来源，否则「快照里的身份」与「核验时的身份」不可比。
   */
  static Instant processStart(long pid) {
    if (isLinux()) {
      return ProcessHandle.of(pid).flatMap(handle -> handle.info().startInstant()).orElse(null);
    }
    if (isMac()) {
      SystemB.ProcBsdInfo info = macBsdInfo(pid);
      if (info == null || info.pbi_status == MAC_STATUS_ZOMBIE) {
        return null;
      }
      return macStart(info);
    }
    return null;
  }

  /**
   * 让本进程忽略 {@code SIGTERM}。
   *
   * <p>只在 helper 已经进入收尾时使用：那时重复的温和信号不得打断强杀与收敛发布。启动阶段绝不调用它——{@code SIG_IGN} 会被 {@code fork} 继承且非交互
   * shell 无法覆盖，命令因此带着「进入时已被忽略」的信号开始。
   */
  static void ignoreTerminationSignal() {
    LibC.INSTANCE.signal(SIGTERM, SIG_IGN);
  }

  /**
   * 会内成员的快照项：pid 与它在快照时刻的身份（启动时刻）。
   *
   * <p>只带 pid 的快照无法对抗 pid 复用：调用方必须在强杀之前重新核验身份。
   */
  record GroupMember(long pid, Instant start) {}

  /**
   * 会话里当前活着（非僵尸）的成员的快照，排除 {@code excludedPid}。
   *
   * <p>返回 {@code null} 表示「不可判定」：本平台没有真实的成员枚举能力、扫描本身失败，或**某个已确认属于本会话且仍活着的成员 读不到身份**。调用方必须把 {@code
   * null} 当成「还没有收敛」——「不可判定」绝不等于「没有成员」——但也绝不因此向任何 pid 发信号。
   */
  static List<GroupMember> membersSnapshot(long session, long excludedPid) {
    if (isLinux()) {
      return linuxMembers(session, excludedPid);
    }
    if (isMac()) {
      return macMembers(session, excludedPid);
    }
    return null;
  }

  /** 会话里活着的成员数量；{@code -1} 表示不可判定。 */
  static long liveMemberCount(long session, long excludedPid) {
    List<GroupMember> members = membersSnapshot(session, excludedPid);
    return members == null ? -1 : members.size();
  }

  /**
   * 会话里是否还有「活着」的成员。
   *
   * <p>不可判定（没有真实枚举能力、扫描失败或身份读不到）必须表现为「还有成员」：谎称已经收敛会让收尾跳过强杀阶段。
   */
  static boolean hasLiveMember(long session, long excludedPid) {
    List<GroupMember> members = membersSnapshot(session, excludedPid);
    return members == null || !members.isEmpty();
  }

  /**
   * 向会话里的成员逐个发信号，但只在身份被重新核验通过时动手。
   *
   * <p>快照与发信号之间存在时间差，pid/sid 可能已经被回收；因此这里同时核验「所属会话仍然是本会话」与「启动时刻与快照一致」。
   * 核验不通过就不发信号：宁可报告未收敛，也绝不杀错进程。返回是否至少交付了一个信号。
   */
  static boolean signalSession(long session, int signal) {
    return signalSession(session, signal, NO_PROCESS);
  }

  /** 同上，但排除 {@code excludedPid}。helper 收尾时用它避免在发布收敛结论之前被自己的强杀信号带走。 */
  static boolean signalSession(long session, int signal, long excludedPid) {
    List<GroupMember> members = membersSnapshot(session, excludedPid);
    if (members == null) {
      return false;
    }
    boolean delivered = false;
    for (GroupMember member : members) {
      Instant now = processStart(member.pid());
      if (now == null || !now.equals(member.start()) || sessionOf(member.pid()) != session) {
        continue;
      }
      if (LibC.INSTANCE.kill((int) member.pid(), signal) == 0) {
        delivered = true;
      }
    }
    return delivered;
  }

  /**
   * 让本进程的 stderr 指向 stdout：随后启动的命令因此把两路输出合并到同一条父进程捕获管道。
   *
   * <p>调用方必须先把 helper 自己的诊断引到别处，否则 helper 之后写到 stderr 的内容也会进入命令输出。PTY 与双向模式不调用它。
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
   * 回收已经被收养的孤儿后代（Linux）。
   *
   * <p>调用方必须等用户命令自己的退出码已经被 {@code java.lang.Process} 回收之后再调用：{@code waitpid(-1)} 会回收任意
   * 子进程，过早调用会偷走命令的退出码。
   */
  static void reapOrphans() {
    if (!isLinux()) {
      return;
    }
    IntByReference status = new IntByReference();
    while (LibC.INSTANCE.waitpid(-1, status, WNOHANG) > 0) {
      // 循环回收所有已退出但还挂在进程表上的被收养后代。
    }
  }

  /** 用 {@code /proc} 枚举 Linux/WSL 的会话成员；扫描失败或身份不可判定返回 {@code null}。 */
  private static List<GroupMember> linuxMembers(long session, long excludedPid) {
    List<GroupMember> members = new ArrayList<>();
    boolean undecidable = false;
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(PROC)) {
      for (Path entry : entries) {
        long pid = parseProcessId(entry.getFileName().toString());
        if (pid <= 0 || pid == excludedPid) {
          continue;
        }
        ProcessStat stat = readProcessStat(entry.resolve("stat"));
        if (stat == null) {
          // 读不到 stat：进程可能已经消失；若它仍存在，这次枚举就无法确认「没有成员」。
          undecidable |= ProcessHandle.of(pid).isPresent();
          continue;
        }
        if (stat.session() != session || !isLiveState(stat.state())) {
          continue;
        }
        Instant start = processStart(pid);
        if (start == null) {
          // 已确认属于本会话且仍活着，却读不到身份：不能把它过滤掉后谎报「没有成员」。
          undecidable = true;
          continue;
        }
        members.add(new GroupMember(pid, start));
      }
    } catch (IOException error) {
      return null;
    }
    return undecidable ? null : members;
  }

  /**
   * 用 libproc 枚举 macOS 的会话成员。
   *
   * <p>pid 列表来自 {@code proc_listpids(PROC_ALL_PIDS, 0, NULL, 0)}（返回所需字节数）后按需扩大缓冲；只有当写入字节数小于容量时才认定这次
   * 枚举完整，否则继续扩大预算——「缓冲填满」不能伪称枚举完整，否则会漏掉成员。
   *
   * <p>归属用 {@code getsid(pid)} 判定，状态与启动身份用 {@code proc_pidinfo(PROC_PIDTBSDINFO)} 读取：僵尸（{@code
   * SZOMB}）不算 活成员，读不到身份时按「不可判定」返回 {@code null}，绝不把「查不到」当成「已经消失」。
   */
  private static List<GroupMember> macMembers(long session, long excludedPid) {
    return macMembers(session, excludedPid, macProcessIds());
  }

  /** 按一次真实 pid 快照查询；包内回归用已消失 pid 验证扫描期间的退出竞态。 */
  static List<GroupMember> macMembers(long session, long excludedPid, int[] pids) {
    if (pids == null) {
      return null;
    }
    List<GroupMember> members = new ArrayList<>();
    boolean undecidable = false;
    for (int pidValue : pids) {
      long pid = Integer.toUnsignedLong(pidValue);
      if (pid <= 0 || pid == excludedPid) {
        continue;
      }
      int processSession = LibC.INSTANCE.getsid((int) pid);
      if (processSession < 0) {
        // ESRCH 已由内核证明没有活成员；JDK 句柄仍可看到僵尸，不能反向把它判为活着。
        undecidable |= Native.getLastError() != ESRCH;
        continue;
      }
      if (Integer.toUnsignedLong(processSession) != session) {
        continue;
      }
      SystemB.ProcBsdInfo info = macBsdInfo(pid);
      if (info == null) {
        undecidable |= ProcessHandle.of(pid).isPresent();
        continue;
      }
      if (info.pbi_status == MAC_STATUS_ZOMBIE) {
        continue;
      }
      members.add(new GroupMember(pid, macStart(info)));
    }
    return undecidable ? null : members;
  }

  /**
   * macOS 全部 pid 的完整枚举：先问所需字节数，再按需扩大缓冲，直到写入字节数小于容量。
   *
   * <p>{@code proc_listpids} 的参数和返回值都以字节计；缓冲填满表示可能被截断，不能认定枚举完整。
   */
  private static int[] macProcessIds() {
    int requiredBytes = SystemB.INSTANCE.proc_listpids(SystemB.PROC_ALL_PIDS, 0, null, 0);
    if (requiredBytes <= 0 || requiredBytes % Integer.BYTES != 0) {
      return null;
    }
    int capacity = requiredBytes / Integer.BYTES;
    while (capacity <= MAC_PID_LIMIT) {
      int size = Math.min(MAC_PID_LIMIT, capacity + Math.max(64, capacity / 4));
      int[] buffer = new int[size];
      int writtenBytes =
          SystemB.INSTANCE.proc_listpids(SystemB.PROC_ALL_PIDS, 0, buffer, size * Integer.BYTES);
      if (writtenBytes <= 0 || writtenBytes % Integer.BYTES != 0) {
        return null;
      }
      if (writtenBytes < size * Integer.BYTES) {
        int count = writtenBytes / Integer.BYTES;
        int[] complete = new int[count];
        System.arraycopy(buffer, 0, complete, 0, count);
        return complete;
      }
      // 缓冲填满：可能被截断，扩大预算重试。
      if (size == MAC_PID_LIMIT) {
        return null;
      }
      capacity = size;
    }
    return null;
  }

  /** 读取 {@code proc_bsdinfo}；进程不存在或读取失败返回 {@code null}。 */
  private static SystemB.ProcBsdInfo macBsdInfo(long pid) {
    SystemB.ProcBsdInfo info = new SystemB.ProcBsdInfo();
    int size = info.size();
    int written = SystemB.INSTANCE.proc_pidinfo((int) pid, SystemB.PROC_PIDTBSDINFO, 0, info, size);
    return written == size ? info : null;
  }

  /** {@code proc_bsdinfo} 的启动时刻。 */
  private static Instant macStart(SystemB.ProcBsdInfo info) {
    return Instant.ofEpochSecond(info.pbi_start_tvsec, info.pbi_start_tvusec * 1_000L);
  }

  /** {@code /proc/<pid>/stat} 的状态字段：{@code Z}/{@code X}/{@code x} 是不会再执行任何代码的成员。 */
  private static boolean isLiveState(char state) {
    return state != 'Z' && state != 'X' && state != 'x';
  }

  /**
   * 解析 {@code /proc/<pid>/stat}：状态、进程组与会话。
   *
   * <p>包级可见是为了让纯解析规则可以按真实样本回归（不改变任何行为）。
   */
  static ProcessStat readProcessStat(Path stat) {
    try {
      String content = Files.readString(stat);
      // 格式为 "pid (comm) state ppid pgrp session ..."，而 comm 可以包含空格与括号，因此从最后一个 ')' 之后开始解析。
      int end = content.lastIndexOf(')');
      if (end < 0) {
        return null;
      }
      String[] fields = content.substring(end + 1).trim().split("\\s+");
      if (fields.length < 4) {
        return null;
      }
      return new ProcessStat(
          fields[0].charAt(0), Long.parseLong(fields[2]), Long.parseLong(fields[3]));
    } catch (IOException | RuntimeException error) {
      return null;
    }
  }

  /** 解析 {@code /proc} 目录项里的 pid：包级可见是为了让解析规则可以按真实样本回归。 */
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

  private static boolean isMac() {
    return DaemonOperatingSystemDetector.detectCurrent() == DaemonOperatingSystem.MACOS;
  }

  /** {@code /proc/<pid>/stat} 的投影：状态、进程组与会话。 */
  record ProcessStat(char state, long group, long session) {}

  /** libc 绑定；只在对应 POSIX 平台访问，因此其它平台不会初始化它。 */
  interface LibC extends Library {

    LibC INSTANCE = Native.load("c", LibC.class);

    int setsid();

    int getsid(int pid);

    int getpgrp();

    int isatty(int fd);

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
