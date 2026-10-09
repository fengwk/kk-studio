package fun.fengwk.kkstudio.harness.daemon.terminal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 终端运行时用例的跨平台进程夹具：只用 JDK，不依赖 shell，用于在真实 PTY 中证明输出经 PTY 回传、写入到达命令与自然退出。
 *
 * <p>它是 {@link TerminalRuntime} 启动的用户命令（helper 的子进程），三条标准流就是伪终端，因此「输出经 PTY 回传」与「写入到达
 * 命令」都由命令自身的行为证明。用法（第一个参数是模式）：
 *
 * <pre>
 *   hold         &lt;pidFile&gt;      写完 pid 后长时间存活（不会自己退出）
 *   echo                         把 stdin 的每一行回写为 {@code ECHO:&lt;line&gt;}，读到 EOF 后退出 0
 *   env-probe    &lt;resultFile&gt;    把自身 TERM/COLORTERM 写入结果文件，打印标记后退出 0
 *   exit-code    &lt;code&gt;          打印标记后退到指定退出码
 * </pre>
 */
public final class TerminalRuntimeFixtureMain {

  /** 自然退出与输出回传的固定标记。 */
  static final String MARKER = "__TTY_RUNTIME_FIXTURE__";

  private static final long HOLD_MILLIS = 600_000L;

  private TerminalRuntimeFixtureMain() {}

  /** 构造运行本夹具的 argv（测试用）：同一 JVM 与类路径，并转发父进程的覆盖率代理。 */
  static List<String> fixtureCommand(String... arguments) {
    List<String> command = new ArrayList<>();
    command.add(javaBinary());
    command.addAll(forwardedCoverageArguments());
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(TerminalRuntimeFixtureMain.class.getName());
    command.addAll(List.of(arguments));
    return command;
  }

  public static void main(String[] args) {
    if (args.length < 1) {
      System.err.println("usage: <mode> [...]");
      System.exit(2);
      return;
    }
    try {
      run(args);
    } catch (Throwable error) {
      error.printStackTrace();
      System.exit(1);
    }
  }

  private static void run(String[] args) throws Exception {
    String mode = args[0];
    switch (mode) {
      case "hold" -> {
        writePid(Path.of(args[1]));
        sleepForever();
      }
      case "echo" -> echoUntilEof();
      case "env-probe" -> envProbe(Path.of(args[1]));
      case "exit-code" -> {
        System.out.println(MARKER);
        System.out.flush();
        System.exit(Integer.parseInt(args[1]));
      }
      default -> {
        System.err.println("unknown fixture mode");
        System.exit(2);
      }
    }
  }

  /** 回写 stdin 的每一行，因此父进程可以通过内核画面看到命令确实读到了写入的字节。 */
  private static void echoUntilEof() throws IOException {
    InputStream in = System.in;
    OutputStream out = System.out;
    ByteArrayOutputStream line = new ByteArrayOutputStream();
    int read;
    while ((read = in.read()) >= 0) {
      if (read == '\n') {
        out.write(
            ("ECHO:" + line.toString(StandardCharsets.UTF_8) + "\n")
                .getBytes(StandardCharsets.UTF_8));
        out.flush();
        line.reset();
      } else {
        line.write(read);
      }
    }
    out.flush();
    System.exit(0);
  }

  /** 把继承到的 TERM/COLORTERM 写进结果文件，并打印标记，供父进程核对终端声明。 */
  private static void envProbe(Path resultFile) throws IOException {
    String term = String.valueOf(System.getenv("TERM"));
    String colorterm = String.valueOf(System.getenv("COLORTERM"));
    publishAtomically(resultFile, "TERM=" + term + "\nCOLORTERM=" + colorterm + "\n");
    System.out.println(MARKER);
    System.out.flush();
    System.exit(0);
  }

  private static void writePid(Path pidFile) throws IOException {
    publishAtomically(pidFile, Long.toString(ProcessHandle.current().pid()));
  }

  /** 原子发布：先写临时文件再移动，父进程不会读到半截内容。 */
  private static void publishAtomically(Path file, String content) throws IOException {
    Path staging = file.resolveSibling(file.getFileName() + ".part");
    Files.writeString(staging, content, StandardCharsets.UTF_8);
    Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING);
  }

  private static void sleepForever() {
    long deadline = System.nanoTime() + HOLD_MILLIS * 1_000_000L;
    while (System.nanoTime() < deadline) {
      try {
        Thread.sleep(1_000L);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private static String javaBinary() {
    return Path.of(System.getProperty("java.home"))
        .resolve("bin")
        .resolve(isWindows() ? "java.exe" : "java")
        .toString();
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  /** 把父 JVM 的 JaCoCo 代理转发给夹具 JVM，落到独立文件，避免与父进程的定位文件互相覆盖。 */
  private static List<String> forwardedCoverageArguments() {
    Pattern destfile = Pattern.compile("destfile=([^,]+)");
    for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
      if (!argument.startsWith("-javaagent:") || !argument.contains("jacoco")) {
        continue;
      }
      Matcher matcher = destfile.matcher(argument);
      if (!matcher.find()) {
        return List.of(argument);
      }
      Path destination = Path.of(matcher.group(1)).resolveSibling("jacoco-helper");
      try {
        Files.createDirectories(destination);
      } catch (IOException error) {
        return List.of(argument);
      }
      String isolated = destination.resolve("fixture-" + UUID.randomUUID() + ".exec").toString();
      return List.of(
          argument.substring(0, matcher.start(1)) + isolated + argument.substring(matcher.end(1)));
    }
    return List.of();
  }
}
