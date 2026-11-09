package fun.fengwk.kkstudio.web.environment.ide;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 生产 Daemon 子进程夹具：私有临时发行根 + 真实 Node 24 进程。
 *
 * <p>发行根按 Daemon 自身的解析规则构造：{@code daemon/src/config.ts} 的 {@code resolveDistributionDir}
 * 以模块所在目录的父级作为发行根，因此临时发行根包含：{@code dist} 副本、{@code package.json} 副本、{@code node_modules}
 * 软链（复用已安装的生产依赖）、{@code ide-extension} 副本，以及指向只读 code-server 产物的 {@code runtime/code-server} 软链。
 * {@code dist} 必须复制而不是软链：避免 {@code realpath(argv[1])} 与 {@code import.meta.url} 失配或发行根定位落回仓库树。
 *
 * <p>注册凭据只写进临时数据目录的 {@code daemon.token}（0600），进程 stdout/stderr 落在工作区 {@code records} 目录（0700）内的
 * 0600 文件里；夹具从不把凭据写进命令行、环境或异常消息。
 */
final class ProductionDaemonFixture implements AutoCloseable {

  private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY =
      PosixFilePermissions.fromString("rwx------");

  private static final Set<PosixFilePermission> OWNER_ONLY_FILE =
      PosixFilePermissions.fromString("rw-------");

  /** 停止宽限：先 SIGTERM 让 Daemon 自己收敛，再强制结束残留进程。 */
  private static final long STOP_GRACE_SECONDS = 20;

  private final Path workspace;

  private final Path distributionDir;

  private final Path dataDir;

  private final Path stdoutLog;

  private final Path stderrLog;

  private final Process process;

  /** 崩溃前捕获的受管 code-server 后代句柄。 */
  private final List<ProcessHandle> retainedDescendants = new ArrayList<>();

  /** 外部清理没能确认全部自有进程退出时置位：{@link #close()} 据此保留工作目录并如实报告。 */
  private boolean preserveWorkspace;

  private ProductionDaemonFixture(
      Path workspace,
      Path distributionDir,
      Path dataDir,
      Path stdoutLog,
      Path stderrLog,
      Process process) {
    this.workspace = workspace;
    this.distributionDir = distributionDir;
    this.dataDir = dataDir;
    this.stdoutLog = stdoutLog;
    this.stderrLog = stderrLog;
    this.process = process;
  }

  /**
   * 启动生产 Daemon。
   *
   * @param repoRoot 仓库根（提供 {@code daemon/dist} 与 {@code daemon/ide-extension}）
   * @param codeServerRelease 只读的已构建 code-server 发行目录
   * @param environmentId 创建环境后由服务端返回的 canonical UUID
   * @param configVersion 创建环境后由服务端返回的配置 CAS 版本
   * @param daemonConfigJson 创建环境后由服务端返回的完整 daemonConfig JSON
   * @param registrationToken 注册凭据明文；只写进 0600 的 {@code daemon.token}
   * @param recordsDir 私有日志目录（0700）
   * @param nodeBinary Node 24 可执行文件
   */
  static ProductionDaemonFixture start(
      Path repoRoot,
      Path codeServerRelease,
      String environmentId,
      long configVersion,
      String daemonConfigJson,
      String registrationToken,
      Path recordsDir,
      Path nodeBinary)
      throws IOException {
    Path workspace = Files.createTempDirectory("kk-ide-acceptance-");
    List<Path> logs = new ArrayList<>();
    Process started = null;
    try {
      restrictDirectory(workspace);
      Path distributionDir = workspace.resolve("distribution");
      Files.createDirectories(distributionDir);
      restrictDirectory(distributionDir);

      copyTree(repoRoot.resolve("daemon").resolve("dist"), distributionDir.resolve("dist"));
      copyTree(
          repoRoot.resolve("daemon").resolve("ide-extension"),
          distributionDir.resolve("ide-extension"));
      Files.copy(
          repoRoot.resolve("daemon").resolve("package.json"),
          distributionDir.resolve("package.json"));
      Files.createSymbolicLink(
          distributionDir.resolve("node_modules"),
          repoRoot.resolve("daemon").resolve("node_modules"));

      Path runtimeDir = distributionDir.resolve("runtime");
      Files.createDirectories(runtimeDir);
      restrictDirectory(runtimeDir);
      Files.createSymbolicLink(runtimeDir.resolve("code-server"), codeServerRelease);

      Path homeDir = Files.createDirectory(workspace.resolve("home"));
      restrictDirectory(homeDir);
      Path dataDir = distributionDir.resolve("data");
      Files.createDirectories(dataDir);
      restrictDirectory(dataDir);
      Path configFile = dataDir.resolve("daemon.json");
      writePrivate(
          configFile,
          "{\"environmentId\":\""
              + environmentId
              + "\",\"version\":\""
              + configVersion
              + "\",\"daemonConfig\":"
              + daemonConfigJson
              + "}\n");
      Path tokenFile = dataDir.resolve("daemon.token");
      writePrivate(tokenFile, registrationToken + "\n");

      Files.createDirectories(recordsDir);
      restrictDirectory(recordsDir);
      Path stdoutLog = Files.createTempFile(recordsDir, "ide-acceptance-daemon-", ".log");
      logs.add(stdoutLog);
      Path stderrLog = Files.createTempFile(recordsDir, "ide-acceptance-daemon-", ".err.log");
      logs.add(stderrLog);
      restrictFile(stdoutLog);
      restrictFile(stderrLog);

      ProcessBuilder builder =
          new ProcessBuilder(
              nodeBinary.toString(),
              distributionDir.resolve("dist").resolve("main.js").toString(),
              "--config",
              configFile.toString());
      builder.directory(distributionDir.toFile());
      builder.redirectOutput(stdoutLog.toFile());
      builder.redirectError(stderrLog.toFile());
      builder.environment().put("HOME", homeDir.toString());
      builder.environment().put("XDG_CONFIG_HOME", homeDir.toString());
      builder.environment().put("XDG_CACHE_HOME", homeDir.toString());
      builder.environment().put("XDG_DATA_HOME", homeDir.toString());
      builder.environment().put("TMPDIR", homeDir.toString());
      builder.environment().put("no_proxy", "127.0.0.1,localhost,::1");
      builder.environment().put("NO_PROXY", "127.0.0.1,localhost,::1");
      started = builder.start();
      return new ProductionDaemonFixture(
          workspace, distributionDir, dataDir, stdoutLog, stderrLog, started);
    } catch (IOException | RuntimeException failure) {
      throw reclaimFailedStart(workspace, logs, started, failure);
    }
  }

  private static RuntimeException reclaimFailedStart(
      Path workspace, List<Path> logs, Process started, Exception failure) {
    List<String> preserved = new ArrayList<>();
    boolean processesExited = true;
    if (started != null) {
      List<ProcessHandle> owned = List.of();
      try {
        owned = started.toHandle().descendants().toList();
      } catch (RuntimeException error) {
        processesExited = false;
        preserved.add("daemon descendant snapshot failed");
      }
      ExitOutcome daemonOutcome = stopProcess(started);
      if (daemonOutcome != ExitOutcome.EXITED) {
        processesExited = false;
        preserved.add(
            "the daemon process started before the failure did not exit (pid="
                + started.pid()
                + ")");
      }
      List<String> survivors = terminateOwnedDescendants(owned);
      processesExited &= survivors.isEmpty();
      preserved.addAll(survivors);
    } else {
      for (Path log : logs) {
        try {
          Files.deleteIfExists(log);
        } catch (IOException error) {
          preserved.add("kept log " + log);
        }
      }
    }
    RuntimeException deleteFailure = null;
    if (!processesExited) {
      preserved.add("kept workspace " + workspace);
    } else {
      try {
        deleteTree(workspace);
      } catch (RuntimeException error) {
        deleteFailure = error;
        preserved.add("kept workspace " + workspace);
      }
    }
    if (preserved.isEmpty()) {
      if (failure instanceof IOException ioFailure) {
        return new UncheckedIOException(ioFailure);
      }
      return (RuntimeException) failure;
    }
    IllegalStateException reported =
        new IllegalStateException(
            "the daemon fixture failed to start and could not reclaim everything ("
                + String.join("; ", preserved)
                + ")",
            failure);
    if (deleteFailure != null) {
      reported.addSuppressed(deleteFailure);
    }
    return reported;
  }

  Path workspaceDirectory() throws IOException {
    Path directory = workspace.resolve("files");
    if (!Files.isDirectory(directory)) {
      Files.createDirectories(directory);
      restrictDirectory(directory);
    }
    return directory;
  }

  List<ProcessHandle> descendants() {
    return process.toHandle().descendants().toList();
  }

  void crashCodeServer() {
    ProcessHandle target =
        descendants().stream()
            .filter(ProductionDaemonFixture::isCodeServer)
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException("the daemon started no managed code-server process"));
    retainedDescendants.add(target);
    retainedDescendants.addAll(target.descendants().toList());
    target.destroyForcibly();
    if (awaitExit(target) != ExitOutcome.EXITED) {
      throw new IllegalStateException(
          "the managed code-server did not exit after a forced crash; kept workspace " + workspace);
    }
  }

  boolean hasCodeServer() {
    return descendants().stream().anyMatch(ProductionDaemonFixture::isCodeServer);
  }

  private static boolean isCodeServer(ProcessHandle handle) {
    String commandLine = handle.info().commandLine().orElse("");
    if (commandLine.contains("entry.js")) {
      return true;
    }
    String[] args = handle.info().arguments().orElse(new String[0]);
    for (String arg : args) {
      if (arg.contains("entry.js")) {
        return true;
      }
    }
    return false;
  }

  Path stdoutLog() {
    return stdoutLog;
  }

  Path stderrLog() {
    return stderrLog;
  }

  boolean alive() {
    return process.isAlive();
  }

  void preserveWorkspace() {
    preserveWorkspace = true;
  }

  @Override
  public void close() {
    List<String> unresolved = new ArrayList<>();
    List<ProcessHandle> owned = new ArrayList<>(retainedDescendants);
    try {
      owned = ownedHandles();
    } catch (RuntimeException error) {
      unresolved.add("daemon descendant snapshot failed");
    }
    ExitOutcome daemonOutcome = stopProcess(process);
    if (daemonOutcome != ExitOutcome.EXITED) {
      unresolved.add(
          daemonOutcome == ExitOutcome.UNCONFIRMED
              ? "the owned daemon exit was not confirmed"
              : "the owned daemon did not exit");
    }
    unresolved.addAll(terminateOwnedDescendants(owned));
    List<String> reasons = new ArrayList<>(unresolved);
    if (preserveWorkspace) {
      reasons.add("the workspace was preserved on request");
    }
    if (!reasons.isEmpty()) {
      throw new IllegalStateException(
          "the fixture did not release everything it owns ("
              + String.join("; ", reasons)
              + "); kept workspace "
              + workspace);
    }
    deleteTree(workspace);
  }

  private List<ProcessHandle> ownedHandles() {
    List<ProcessHandle> owned = new ArrayList<>(process.toHandle().descendants().toList());
    owned.addAll(retainedDescendants);
    return owned;
  }

  private static List<String> terminateOwnedDescendants(List<ProcessHandle> owned) {
    List<String> survivors = new ArrayList<>();
    for (ProcessHandle handle : owned) {
      try {
        if (!handle.isAlive()) {
          continue;
        }
        handle.destroy();
        if (awaitExit(handle) == ExitOutcome.EXITED) {
          continue;
        }
        handle.destroyForcibly();
        ExitOutcome outcome = awaitExit(handle);
        if (outcome != ExitOutcome.EXITED) {
          survivors.add(describe(handle, outcome));
        }
      } catch (RuntimeException error) {
        survivors.add("pid=" + handle.pid() + " (exit unconfirmed)");
      }
    }
    return survivors;
  }

  private static ExitOutcome stopProcess(Process target) {
    try {
      ProcessHandle handle = target.toHandle();
      handle.destroy();
      ExitOutcome outcome = awaitExit(target);
      if (outcome == ExitOutcome.EXITED) {
        return outcome;
      }
      handle.destroyForcibly();
      return awaitExit(target);
    } catch (RuntimeException error) {
      return ExitOutcome.UNCONFIRMED;
    }
  }

  private static ExitOutcome awaitExit(Process target) {
    try {
      return target.waitFor(STOP_GRACE_SECONDS, TimeUnit.SECONDS)
          ? ExitOutcome.EXITED
          : ExitOutcome.ALIVE;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return ExitOutcome.UNCONFIRMED;
    }
  }

  private static ExitOutcome awaitExit(ProcessHandle handle) {
    try {
      handle.onExit().get(STOP_GRACE_SECONDS, TimeUnit.SECONDS);
      return ExitOutcome.EXITED;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return ExitOutcome.UNCONFIRMED;
    } catch (TimeoutException failure) {
      return ExitOutcome.ALIVE;
    } catch (ExecutionException failure) {
      return ExitOutcome.UNCONFIRMED;
    }
  }

  private static String describe(ProcessHandle handle, ExitOutcome outcome) {
    String command = handle.info().command().orElse("unknown");
    String suffix = outcome == ExitOutcome.UNCONFIRMED ? " (exit unconfirmed)" : "";
    return "pid=" + handle.pid() + " command=" + command + suffix;
  }

  private enum ExitOutcome {
    EXITED,
    ALIVE,
    UNCONFIRMED
  }

  private static void copyTree(Path source, Path target) throws IOException {
    if (!Files.isDirectory(source)) {
      throw new IOException("required daemon artifact is missing: " + source);
    }
    Files.walkFileTree(
        source,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
              throws IOException {
            Files.createDirectories(target.resolve(source.relativize(directory).toString()));
            restrictDirectory(target.resolve(source.relativize(directory).toString()));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
              throws IOException {
            Path destination = target.resolve(source.relativize(file).toString());
            Files.copy(file, destination);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static void deleteTree(Path root) {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try {
      Files.walkFileTree(
          root,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                throws IOException {
              Files.deleteIfExists(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                throws IOException {
              if (failure != null) {
                throw failure;
              }
              Files.deleteIfExists(directory);
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException error) {
      throw new UncheckedIOException("failed to remove test workspace " + root, error);
    }
  }

  private static void writePrivate(Path file, String content) throws IOException {
    Files.writeString(file, content, StandardCharsets.UTF_8);
    restrictFile(file);
  }

  private static void restrictDirectory(Path directory) throws IOException {
    Files.setPosixFilePermissions(directory, OWNER_ONLY_DIRECTORY);
  }

  private static void restrictFile(Path file) throws IOException {
    Files.setPosixFilePermissions(file, OWNER_ONLY_FILE);
  }
}
