package fun.fengwk.kkstudio.harness.daemon.coding;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * scope helper 的 JVM 启动辅助：父进程侧唯一知道「怎样把 helper 拉起来」的地方。
 *
 * <p>它只回答三件事：用哪个 Java、带哪些 JVM 参数（含 JaCoCo 代理与测试闸门）、helper 的三条标准流怎么接。范围的确认、 终止与收敛结论都不在这里——它们属于
 * {@link ProcessScope}。这样拆开是因为「启动参数只在启动时用一次」与「终止并证明整组 已经结束贯穿调用的整个生命周期」是两件相互独立的职责。
 *
 * <p>三处不能被「统一简化」掉的真实约束：
 *
 * <ul>
 *   <li>Java 可执行文件按平台区分（Windows 是 {@code java.exe}），类路径来自父 JVM 的 {@code java.class.path}，缺失时
 *       必须失败关闭——绝不启动一个无法承载 helper 的 JVM；
 *   <li>覆盖率为 helper 换成独立的 {@code destfile}（调用私有的状态目录里），既不读父进程的定位文件（那会在父 JVM 退出时
 *       被整份写回覆盖），也不改变正常调用与测试的启动开销：开关默认关闭；
 *   <li>helper 自己的话只写调用私有的诊断文件，标准输出完全属于用户命令。
 * </ul>
 */
final class ProcessScopeHelperLauncher {

  private static final Logger LOG = System.getLogger(ProcessScopeHelperLauncher.class.getName());

  /** 把标准流模式转发给 helper 的属性名；helper 侧以编译期常量读取同一个名字。 */
  static final String STDIO_PROPERTY = "kk-studio.process-scope.stdio";

  /**
   * 仅测试使用的派生闸门属性：显式设置时转发给 helper，让它在启动命令之前等待释放文件。
   *
   * <p>用来确定性地构造「父进程取消与 helper 派生命令同时发生」这一窗口，生产路径不设置该属性。
   */
  static final String SPAWN_LATCH_PROPERTY = "kk-studio.process-scope.spawn-latch";

  /** 显式要求给 helper 挂覆盖率代理的开关：默认关闭，避免改变正常调用与测试的启动开销。 */
  private static final String HELPER_COVERAGE_PROPERTY = "kk-studio.process-scope.helper-coverage";

  private static final String HELPER_COVERAGE_FILE = "jacoco-helper.exec";

  /** helper 覆盖数据的落地子目录：与父进程覆盖数据文件同级，便于合并报告。 */
  private static final String HELPER_COVERAGE_DIRECTORY = "jacoco-helper";

  private ProcessScopeHelperLauncher() {}

  /**
   * 标准流模式：用户命令的 stdin/stdout/stderr 接到哪里。
   *
   * <p>{@link #CAPTURE} 是默认：命令的 stdin 是一条只有 helper 持有写端的空管道（命令因此读到确定性 EOF），stderr 合并进
   * stdout，父进程按捕获管道排空——「命令的输出就是调用结果」的模式。
   *
   * <p>{@link #DUPLEX} 供常驻双向协议（LSP）：命令的 stdin/stdout/stderr 直接继承 helper 自己的三条流，stderr 保持独立，
   * 父进程因此可以像直接 {@code ProcessBuilder.start()} 那样与命令对话。命令的 stdin 不创建、也不关闭，EOF 只在父进程关闭
   * 自己那一端时到达；stderr 由父进程按自己的诊断需要排空。
   */
  enum Stdio {
    CAPTURE,
    DUPLEX
  }

  /**
   * 启动 helper JVM：POSIX 新 session/进程组与 Windows 命名 Job 的建立、等父进程确认、再放行用户命令都由 helper 自己完成，
   * 这里只负责把它拉起来，并把标准流按 {@code stdio} 接好。
   *
   * <p>捕获模式把 helper 的 stderr（JVM 启动提示与 helper 诊断）重定向到调用私有的诊断文件，绝不混进用户命令的输出；双向 模式给命令留一条独立的 stderr
   * 管道，helper 自己的诊断仍然只写同一条诊断文件，因此不会进入命令的 stderr。
   */
  static Process launch(
      Path stateDir, Path workdir, List<String> command, boolean windows, Stdio stdio)
      throws IOException {
    String classpath = System.getProperty("java.class.path");
    if (classpath == null || classpath.isBlank()) {
      throw new IOException(
          "cannot locate the daemon classpath required to launch the process scope helper");
    }
    List<String> argv = new ArrayList<>();
    argv.add(javaBinary(windows));
    // helper 的输出必须只有用户命令的输出：关掉 JVM 统一日志，并容忍陌生 VM 选项。
    argv.add("-XX:+IgnoreUnrecognizedVMOptions");
    argv.add("-XX:TieredStopAtLevel=1");
    argv.add("-Xlog:disable");
    argv.addAll(helperCoverageArguments(stateDir));
    argv.addAll(spawnLatchArguments());
    argv.addAll(stdioArguments(stdio));
    argv.add("-cp");
    argv.add(classpath);
    argv.add(ProcessScopeHelper.class.getName());
    argv.add(stateDir.toString());
    argv.add(workdir.toString());
    argv.addAll(command);
    ProcessBuilder builder = new ProcessBuilder(argv);
    if (stdio == Stdio.DUPLEX) {
      // 双向模式下 helper 的 stderr 必须是管道：命令继承它，父进程按需排空（helper 自己的 Java 输出仍然写调用私有的诊断
      // 文件，因此不会混进命令的 stderr）。
      builder.redirectError(ProcessBuilder.Redirect.PIPE);
    } else {
      // 捕获模式下 helper 的 stderr（JVM 启动提示、诊断）只进调用私有的诊断文件，绝不混进用户命令的输出。
      builder.redirectError(stateDir.resolve(ProcessScopeState.DIAGNOSTICS_FILE).toFile());
    }
    return builder.start();
  }

  /**
   * 把 helper 的覆盖数据搬到父进程覆盖数据旁边，便于合并报告。
   *
   * <p>helper 的逻辑是执行范围的核心，不能因为「在另一个 JVM 里」就没有覆盖数据；但收集本身只是度量工具：没有数据、没有
   * 父代理或复制失败都不影响调用结果。失败留下只报「收集失败」这一事实的去敏诊断，不回显任何私有路径或命令内容。
   */
  static void collectCoverage(Path stateDir) {
    Path exec = stateDir.resolve(HELPER_COVERAGE_FILE);
    if (!Files.isRegularFile(exec)) {
      return;
    }
    Path directory = helperCoverageDirectory();
    if (directory == null) {
      return;
    }
    try {
      Files.createDirectories(directory);
      Files.copy(
          exec,
          directory.resolve(stateDir.getFileName() + ".exec"),
          StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException error) {
      LOG.log(Level.WARNING, "cannot collect the process scope helper coverage data");
    }
  }

  /** {@link Stdio#DUPLEX} 时把模式转发给 helper；默认模式不传参数。 */
  private static List<String> stdioArguments(Stdio stdio) {
    return stdio == Stdio.DUPLEX ? List.of("-D" + STDIO_PROPERTY + "=duplex") : List.of();
  }

  private static String javaBinary(boolean windows) {
    return Path.of(System.getProperty("java.home"))
        .resolve("bin")
        .resolve(windows ? "java.exe" : "java")
        .toString();
  }

  /** {@link #SPAWN_LATCH_PROPERTY} 设置时把闸门目录转发给 helper；未设置时参数里什么都不加。 */
  private static List<String> spawnLatchArguments() {
    String latch = System.getProperty(SPAWN_LATCH_PROPERTY);
    return latch == null || latch.isBlank()
        ? List.of()
        : List.of("-D" + SPAWN_LATCH_PROPERTY + "=" + latch);
  }

  /**
   * 父进程自己带覆盖率代理且显式打开 helper 覆盖率时，给 helper 挂同一个代理并写到调用私有的文件。
   *
   * <p>代理 jar 取自父 JVM 已有的 {@code -javaagent} 参数，只有 {@code destfile} 换成 helper 自己的文件：这样父进程退出时
   * 整份写回不会覆盖 helper 的数据，helper 的平台分支才能进入合并报告。
   */
  private static List<String> helperCoverageArguments(Path stateDir) {
    if (!Boolean.getBoolean(HELPER_COVERAGE_PROPERTY)) {
      return List.of();
    }
    String argument = jacocoAgentArgument();
    if (argument == null) {
      return List.of();
    }
    String agent = argument.substring("-javaagent:".length());
    int options = agent.indexOf('=');
    String jar = options < 0 ? agent : agent.substring(0, options);
    return List.of(
        "-javaagent:"
            + jar
            + "=destfile="
            + stateDir.resolve(HELPER_COVERAGE_FILE)
            + ",append=false,dumponexit=true");
  }

  /** 父进程自己的 JaCoCo 代理参数；没有代理时返回 {@code null}。 */
  private static String jacocoAgentArgument() {
    for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
      if (argument.startsWith("-javaagent:") && argument.contains("jacoco")) {
        return argument;
      }
    }
    return null;
  }

  /** helper 覆盖数据的落地目录：父进程覆盖数据文件旁边的 {@code jacoco-helper/}。 */
  private static Path helperCoverageDirectory() {
    String argument = jacocoAgentArgument();
    if (argument == null) {
      return null;
    }
    int destfile = argument.indexOf("destfile=");
    if (destfile < 0) {
      return null;
    }
    String value = argument.substring(destfile + "destfile=".length());
    int end = value.indexOf(',');
    Path parent = Path.of(end < 0 ? value : value.substring(0, end)).toAbsolutePath().getParent();
    return parent == null ? null : parent.resolve(HELPER_COVERAGE_DIRECTORY);
  }
}
