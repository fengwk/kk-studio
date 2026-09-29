package fun.fengwk.kkstudio.harness.daemon.coding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 跨平台执行范围用例的真实进程夹具：只依赖 JDK 的 Java main，用来在没有 shell、没有 MSYS 的情况造出真实的进程层级。
 *
 * <p>它存在的理由是 C01 的验收事实必须用原生 PID 观察：执行范围由 {@link ProcessScope} 建立，本夹具作为「用户命令」运行，再由它 自己派生子
 * JVM，因此「根进程自然退出后后台进程是否被收敛」「终止是否覆盖嵌套后代」可以在 Linux/macOS/Windows 上用同一段测试 断言，而不依赖任何平台的 shell 语义。
 *
 * <p>用法（第一个参数是模式，第二个参数永远是自己写 pid 的目标文件）：
 *
 * <pre>
 *   hold      &lt;pidFile&gt;                             写完 pid 后长时间存活（不会自己退出）
 *   eof       &lt;pidFile&gt; [exitCode]                  读 stdin 直到 EOF，再用给定退出码自然退出
 *   fork-exit &lt;pidFile&gt; &lt;childPidFile&gt;              派生子进程，等它写出 pid 后自己退出，留下活着的子进程
 *   fork-hold &lt;pidFile&gt; &lt;childPidFile&gt;              派生子进程，等它写出 pid 后继续存活
 *   nest-hold &lt;pidFile&gt; &lt;childPidFile&gt; &lt;grandPidFile&gt; 派生子进程（fork-hold），等孙进程写出 pid 后继续存活
 * </pre>
 *
 * <p>所有子进程的实际存活时间都是 {@link #HOLD_MILLIS}，远长于任何测试窗口，因此「pid 不再存活」只能来自执行范围的收敛。
 */
public final class ProcessScopeFixtureMain {

  /** 子进程的存活时间：必须远大于测试窗口，使「已经消失」只能由收敛解释。 */
  private static final long HOLD_MILLIS = 600_000;

  /** 等待子进程写出 pid 的预算。 */
  private static final Duration CHILD_BUDGET = Duration.ofSeconds(20);

  private ProcessScopeFixtureMain() {}

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
      case "hold" -> sleepForever();
      case "eof" -> {
        readUntilEof();
        System.exit(exitCode(args));
      }
      case "fork-exit" -> {
        spawn("hold", args[2]);
        awaitChild(args[2]);
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
