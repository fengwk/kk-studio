package fun.fengwk.kkstudio.harness.daemon.process;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.Wincon;
import com.sun.jna.platform.win32.Wincon.SMALL_RECT;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 跨平台执行范围用例的真实进程夹具：通用模式只依赖 JDK，不依赖 shell/MSYS；Windows 原生窗口探测使用已有的 JNA。
 *
 * <p>它存在的理由是 C01 的验收事实必须用原生 PID 观察：执行范围由 {@link ProcessScope} 建立，本夹具作为「用户命令」运行，再由它 自己派生子
 * JVM，因此「根进程自然退出后后台进程是否被收敛」「终止是否覆盖嵌套后代」可以在 Linux/macOS/Windows 上用同一段测试 断言，而不依赖任何平台的 shell 语义。
 *
 * <p>用法（第一个参数是模式，第二个参数永远是自己写 pid 的目标文件）：
 *
 * <pre>
 *   hold             &lt;pidFile&gt;                             写完 pid 后长时间存活（不会自己退出）
 *   eof              &lt;pidFile&gt; [exitCode]                  读 stdin 直到 EOF，再用给定退出码自然退出
 *   duplex-echo      &lt;pidFile&gt; [exitCode]                  先写 stderr 标记，再把 stdin 的字节原样回写到 stdout，读到 EOF 后自然退出
 *   fork-exit        &lt;pidFile&gt; &lt;childPidFile&gt; &lt;permitFile&gt; 派生子进程，等它写出 pid、且调用方写下许可文件后自己退出，留下当时仍活着的子进程
 *   duplex-fork-exit &lt;pidFile&gt; &lt;childPidFile&gt;              派生子进程，等它写出 pid、且调用方在 stdin 上写下许可后自己退出
 *   fork-hold        &lt;pidFile&gt; &lt;childPidFile&gt;              派生子进程，等它写出 pid 后继续存活
 *   nest-hold        &lt;pidFile&gt; &lt;childPidFile&gt; &lt;grandPidFile&gt; 派生子进程（fork-hold），等孙进程写出 pid 后继续存活
 *   pty-probe        &lt;pidFile&gt; &lt;resultFile&gt;               把自己的 pid/sid/pgrp 与 fd 0/1/2 的 TTY 事实写进结果文件
 *   pty-exit         &lt;pidFile&gt; [exitCode]                  把固定标记写进 stdout（跨平台 PTY 用例）后按代码退出
 *   conpty-size      &lt;pidFile&gt; &lt;initialSizeFile&gt; &lt;resizePermitFile&gt; &lt;resizedSizeFile&gt; &lt;expectedColumns&gt; &lt;expectedRows&gt;  读回本进程原生 ConPTY 窗口尺寸，等许可后轮询到期望尺寸再发布（仅 Windows）
 * </pre>
 *
 * <p>{@code conpty-size} 只服务 Windows ConPTY 用例：它不读 pty4j 的缓存，而是直接用 Win32 {@code GetStdHandle} +
 * {@code GetConsoleScreenBufferInfo} 读回自己可见视口的列/行（{@code srWindow}），因此父进程断言的是「命令自身在原生控制台里看到的窗口」，
 * 而不是 pty4j 记下的最后一次请求值。尺寸只写进文件，不改动 stdout，也不打印任何终端历史。
 *
 * <p>两种「自然退出」模式都必须先拿到调用方许可才返回：{@code fork-exit} 等许可文件（捕获模式下命令的 stdin 是一条已关闭的空管道，许可选不到 stdin），{@code
 * duplex-fork-exit} 等 stdin 上的一个字节。许可让「根进程退出时子进程仍然活着」成为调用方掌握的事实，而不是让用例与收敛赛跑—— 没有许可时，快机器完全可能在用例读到子进程
 * pid 之前就已经把整组收敛干净，那是正确行为，却会让活前置条件随机失败。
 *
 * <p>所有子进程的实际存活时间都是 {@link #HOLD_MILLIS}，远长于任何测试窗口，因此「pid 不再存活」只能来自执行范围的收敛。
 */
public final class ProcessScopeFixtureMain {

  /** 子进程的存活时间：必须远大于测试窗口，使「已经消失」只能由收敛解释。 */
  private static final long HOLD_MILLIS = 600_000;

  /** 等待子进程写出 pid 的预算。 */
  private static final Duration CHILD_BUDGET = Duration.ofSeconds(20);

  /** 等待原生 ConPTY 窗口轮询到目标尺寸的预算。 */
  private static final Duration VIEWPORT_BUDGET = Duration.ofSeconds(20);

  /** 原生窗口尺寸的轮询步长。 */
  private static final long VIEWPORT_POLL_MILLIS = 20;

  /** Win32 空句柄值。 */
  private static final long NULL_HANDLE = 0L;

  /** Win32 {@code INVALID_HANDLE_VALUE}。 */
  private static final long INVALID_HANDLE_VALUE = -1L;

  /**
   * 写在 stderr 上的固定标记：测试用它判断命令的诊断是否走了自己的通道。
   *
   * <p>捕获模式会把命令的 stderr 合并进 stdout，双向模式必须保持两路独立——这个标记落在哪一路就是这条区别的证据。
   */
  static final String STDERR_MARKER = "FIXTURE-STDERR-MARKER";

  private ProcessScopeFixtureMain() {}

  /** 构造运行本夹具的命令行（测试用）：与 {@link ProcessScope} 的启动方式一致（同一 JVM 与类路径，带上覆盖率代理）。 */
  static List<String> fixtureCommand(String... arguments) {
    List<String> command = new ArrayList<>();
    command.add(javaBinary());
    command.addAll(TestCoverageAgentArguments.forwarded());
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(ProcessScopeFixtureMain.class.getName());
    command.addAll(List.of(arguments));
    return command;
  }

  public static void main(String[] args) {
    if (args.length < 2) {
      System.err.println("usage: <mode> <pidFile> [...]");
      System.exit(2);
      return;
    }
    try {
      run(args);
    } catch (Exception error) {
      error.printStackTrace();
      System.exit(1);
    }
  }

  private static void run(String[] args) throws Exception {
    String mode = args[0];
    record(Path.of(args[1]));
    switch (mode) {
      case "pty-probe" -> {
        // PTY 模式：把自己在伪终端里的身份与三路标准流的 TTY 事实写进结果文件（只能由 POSIX 用例驱动）。
        Files.writeString(
            Path.of(args[2]),
            String.join(
                "\n",
                "pid=" + ProcessHandle.current().pid(),
                "sid=" + PosixProcessSession.currentSession(),
                "pgrp=" + PosixProcessSession.currentGroup(),
                "tty0=" + PosixProcessSession.isTerminal(0),
                "tty1=" + PosixProcessSession.isTerminal(1),
                "tty2=" + PosixProcessSession.isTerminal(2)),
            StandardCharsets.UTF_8);
      }
      case "pty-exit" -> {
        // 跨平台 PTY 夹具：把标记写进 stdout（PTY 从端）后按给定代码退出，不依赖任何 shell。
        System.out.println("__PTY_FIXTURE_OK__");
        System.out.flush();
        System.exit(exitCode(args));
      }
      case "conpty-size" -> conPtySize(args);
      case "hold" -> sleepForever();
      case "eof" -> {
        readUntilEof();
        System.exit(exitCode(args));
      }
      case "duplex-echo" -> {
        // 双向模式：先把诊断写进 stderr，再把 stdin 的字节原样回写 stdout，最后等 EOF 自然退出。
        System.err.println(STDERR_MARKER);
        System.err.flush();
        echoUntilEof();
        System.exit(exitCode(args));
      }
      case "duplex-fork-exit" -> {
        spawn("hold", args[2]);
        awaitChild(args[2]);
        awaitStdinPermit();
      }
      case "fork-exit" -> {
        spawn("hold", args[2]);
        awaitChild(args[2]);
        awaitReleaseFile(Path.of(args[3]));
      }
      case "fork-hold" -> {
        spawn("hold", args[2]);
        awaitChild(args[2]);
        sleepForever();
      }
      case "nest-hold" -> {
        spawn("fork-hold", args[2], args[3]);
        awaitChild(args[3]);
        sleepForever();
      }
      default -> {
        System.err.println("unknown fixture mode: " + mode);
        System.exit(2);
      }
    }
  }

  /**
   * Windows ConPTY 专用：把本进程自己读到的原生控制台窗口尺寸发布给父进程，供「resize 真的改变了窗口」这一验收使用。
   *
   * <p>初始尺寸先发布一次，父进程据此断言启动尺寸；随后等父进程写下许可文件（它已完成 resize），再轮询原生窗口直到期望尺寸出现才发布， 因此父进程读到的永远是命令自身在 ConPTY
   * 里看到的尺寸，而不是一个定时猜测。
   */
  private static void conPtySize(String[] args) throws Exception {
    Path initialSizeFile = Path.of(args[2]);
    Path resizePermitFile = Path.of(args[3]);
    Path resizedSizeFile = Path.of(args[4]);
    int expectedColumns = Integer.parseInt(args[5]);
    int expectedRows = Integer.parseInt(args[6]);

    publishAtomically(initialSizeFile, formatViewport(readConsoleViewport()));
    awaitReleaseFile(resizePermitFile);
    publishAtomically(
        resizedSizeFile, formatViewport(awaitConsoleViewport(expectedColumns, expectedRows)));
  }

  /**
   * 读回本进程原生控制台窗口的可见视口尺寸（列、行）。
   *
   * <p>只走 Win32 控制台 API：{@code GetStdHandle(STD_OUTPUT_HANDLE)} 取得 ConPTY 屏幕缓冲区句柄，再用 {@code
   * GetConsoleScreenBufferInfo} 读回 {@code srWindow}。任何一步失败都带上原生错误码显式抛出，绝不退回缓存值——pty4j 的 {@code
   * getWinSize()} 只返回它自己记下的最后一次请求值，无法证明原生窗口真的变了。
   */
  private static ConsoleViewport readConsoleViewport() {
    WinNT.HANDLE handle = Kernel32.INSTANCE.GetStdHandle(Wincon.STD_OUTPUT_HANDLE);
    if (handle == null
        || handle.getPointer() == null
        || Pointer.nativeValue(handle.getPointer()) == NULL_HANDLE
        || Pointer.nativeValue(handle.getPointer()) == INVALID_HANDLE_VALUE) {
      throw new IllegalStateException(
          "no ConPTY stdout console handle (GetLastError="
              + Kernel32.INSTANCE.GetLastError()
              + ")");
    }
    Wincon.CONSOLE_SCREEN_BUFFER_INFO info = new Wincon.CONSOLE_SCREEN_BUFFER_INFO();
    if (!Kernel32.INSTANCE.GetConsoleScreenBufferInfo(handle, info)) {
      throw new IllegalStateException(
          "GetConsoleScreenBufferInfo failed (GetLastError="
              + Kernel32.INSTANCE.GetLastError()
              + ")");
    }
    // 可见窗口而不是回滚缓冲区：srWindow 的宽高才是命令实际看到的视口。
    SMALL_RECT window = info.srWindow;
    return new ConsoleViewport(window.Right - window.Left + 1, window.Bottom - window.Top + 1);
  }

  /** 有界轮询原生窗口，直到父进程 resize 后的期望尺寸真的可读。 */
  private static ConsoleViewport awaitConsoleViewport(int expectedColumns, int expectedRows)
      throws InterruptedException {
    long deadline = System.nanoTime() + VIEWPORT_BUDGET.toNanos();
    ConsoleViewport observed = readConsoleViewport();
    while (observed.columns() != expectedColumns || observed.rows() != expectedRows) {
      if (System.nanoTime() >= deadline) {
        throw new IllegalStateException(
            "console viewport never reached "
                + expectedColumns
                + "x"
                + expectedRows
                + " within "
                + VIEWPORT_BUDGET
                + " (last observed "
                + observed.columns()
                + "x"
                + observed.rows()
                + ")");
      }
      Thread.sleep(VIEWPORT_POLL_MILLIS);
      observed = readConsoleViewport();
    }
    return observed;
  }

  /** 尺寸文件内容：两段数值，列在前、行在后，仅此而已。 */
  private static String formatViewport(ConsoleViewport viewport) {
    return viewport.columns() + " " + viewport.rows() + "\n";
  }

  /** 先写临时文件再原子改名，父进程绝不会读到只写了一半的数值。 */
  private static void publishAtomically(Path target, String content) throws IOException {
    Path temporary =
        Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
    try {
      Files.writeString(temporary, content, StandardCharsets.UTF_8);
      Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  /** 本进程在 ConPTY 里实际看到的可见视口尺寸。 */
  private record ConsoleViewport(int columns, int rows) {}

  /** 派生一层子进程：同一个类路径、同一个 JVM 可执行文件，因此它一定落在本进程一开始所属的执行范围里。 */
  private static void spawn(String mode, String... files) throws Exception {
    List<String> argv =
        new ArrayList<>(List.of(javaBinary(), "-cp", classpath(), className(), mode));
    argv.addAll(List.of(files));
    new ProcessBuilder(argv).inheritIO().start();
  }

  private static void awaitChild(String pidFile) throws Exception {
    long deadline = System.nanoTime() + CHILD_BUDGET.toNanos();
    while (readPid(Path.of(pidFile)) <= 0) {
      if (System.nanoTime() >= deadline) {
        throw new IllegalStateException("child did not publish its pid within " + CHILD_BUDGET);
      }
      Thread.sleep(10);
    }
  }

  /**
   * 等调用方在 stdin 上写下「可以退出」的许可（双向标准流模式）。
   *
   * <p>读到 EOF 也算许可：调用方若直接关掉自己那一端，夹具照样结束，而不会把「没等到许可」变成一个挂起的用例。
   */
  private static void awaitStdinPermit() throws IOException {
    System.in.read();
  }

  /**
   * 等调用方写下许可文件（捕获模式）。
   *
   * <p>捕获模式下命令的 stdin 是一条已经关闭的空管道，许可无法从 stdin 传来，所以留一个文件作为调用方可控的出口时间点。
   */
  private static void awaitReleaseFile(Path permitFile) throws Exception {
    long deadline = System.nanoTime() + CHILD_BUDGET.toNanos();
    while (!Files.exists(permitFile)) {
      if (System.nanoTime() >= deadline) {
        throw new IllegalStateException(
            "no permit published in " + permitFile + " within " + CHILD_BUDGET);
      }
      Thread.sleep(10);
    }
  }

  private static void record(Path pidFile) throws Exception {
    Files.writeString(
        pidFile, Long.toString(ProcessHandle.current().pid()) + "\n", StandardCharsets.UTF_8);
  }

  private static long readPid(Path pidFile) {
    try {
      return Files.isRegularFile(pidFile) ? Long.parseLong(Files.readString(pidFile).trim()) : -1;
    } catch (Exception error) {
      return -1;
    }
  }

  private static void sleepForever() throws InterruptedException {
    Thread.sleep(HOLD_MILLIS);
  }

  /**
   * 读 stdin 直到 EOF。
   *
   * <p>这条路径的用途是「等待 EOF 的命令必须自然退出」：只要 stdin 管道还有任何一个写端活着，这里就永远读不到 EOF。
   */
  private static void readUntilEof() throws IOException {
    byte[] buffer = new byte[4096];
    while (System.in.read(buffer) >= 0) {
      // 调用方不向命令写入数据，因此这里只等到 EOF。
    }
  }

  /** 把 stdin 的字节原样回写到 stdout，直到 EOF；每一段都立刻 flush，调用方才能按行等待。 */
  private static void echoUntilEof() throws IOException {
    byte[] buffer = new byte[4096];
    int read;
    while ((read = System.in.read(buffer)) >= 0) {
      System.out.write(buffer, 0, read);
      System.out.flush();
    }
  }

  private static int exitCode(String[] args) {
    return args.length > 2 ? Integer.parseInt(args[2]) : 0;
  }

  private static String classpath() {
    return System.getProperty("java.class.path");
  }

  private static String className() {
    return ProcessScopeFixtureMain.class.getName();
  }

  private static String javaBinary() {
    boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java")
        .toString();
  }
}
