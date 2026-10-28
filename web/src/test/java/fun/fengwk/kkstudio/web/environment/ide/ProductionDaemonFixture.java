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
 * 软链（复用已安装的生产依赖）、{@code ide-extension}（bridge 扩展）副本，以及指向只读 code-server 产物的 {@code
 * runtime/code-server} 软链。{@code dist} 必须复制而不是软链：{@code daemon/src/main.ts} 用 {@code
 * realpath(argv[1])} 与 {@code import.meta.url} 比较来决定是否执行入口，软链会让两者失配而静默不启动。
 *
 * <p>注册凭据只写进临时数据目录的 {@code daemon.token}（0600），进程 stdout/stderr 落在工作区 {@code records} 目录（0700）内的
 * 0600 文件里； 夹具从不把凭据写进命令行、环境或异常消息。
 *
 * <p><b>自有子进程回收：</b>Daemon 是本夹具启动的自有进程，它又是受管 code-server 的拥有者。{@link #close()} 在停止 Daemon
 * <b>之前</b>先对 {@link ProcessHandle#descendants()} 拍快照（句柄身份 = pid + 启动时刻），停止 Daemon 后逐个 destroy →
 * destroyForcibly 并等待确认退出；只有全部确认退出才删除临时目录，否则保留目录并抛异常带出路径。 不做全盘进程扫描，也不按命令行模糊匹配。
 *
 * <p><b>崩溃后的再收养：</b>{@link #crashCodeServer()} 在强杀受管 code-server <b>之前</b>先拍下它自己的后代句柄并留在夹具里：
 * 父进程一旦死亡，这些进程会被重新收养（不再出现在 Daemon 的后代列表里），只有这份快照能让 {@link #close()} 按句柄把它们一并回收。
 *
 * <p><b>失败即回收：</b>启动过程中任何异常都会先停掉可能已经起来的进程、再终止它派生的后代，最后才删除临时目录；删不掉就保留目录并把路径写进失败信息。
 * 失败信息只带路径、不带凭据：凭据只存在于临时目录里的 0600 {@code daemon.token}，目录被删时一起消失，被保留时仍受 0700/0600 保护。
 *
 * <p><b>按需保留：</b>{@link #preserveWorkspace()} 只影响“删不删根目录”，不影响回收：{@link #close()} 依然停掉自有 Daemon
 * 与已捕获的后代， 只是不删除根目录，并把保留的路径写进失败信息，供人工取证。
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

  /**
   * 崩溃前捕获的受管 code-server 后代句柄。
   *
   * <p>父进程被强杀后它们会被重新收养，不再出现在 {@link ProcessHandle#descendants()} 里；只有留住句柄，{@link #close()} 才能
   * 按句柄回收它们，不必扫描任何别人的进程。
   */
  private final List<ProcessHandle> retainedDescendants = new ArrayList<>();

  /** 外部清理没能确认全部自有进程退出时置位：{@link #close()} 据此保留工作目录并如实报告，而不是把它删掉。 */
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
   * <p>构造过程中任何异常都会走回收路径：停掉可能已经起来的进程、删除临时目录；删不掉就保留目录并把路径写进失败信息（只带路径，不带凭据）。
   *
   * @param repoRoot 仓库根（提供 {@code daemon/dist} 与 {@code daemon/ide-extension}）
   * @param codeServerRelease 只读的已构建 code-server 发行目录
   * @param studioUrl Daemon 连接的 Studio 同源地址
   * @param registrationToken 注册凭据明文；只写进 0600 的 {@code daemon.token}
   * @param recordsDir 私有日志目录（0700）
   * @param nodeBinary Node 24 可执行文件
   */
  static ProductionDaemonFixture start(
      Path repoRoot,
      Path codeServerRelease,
      String studioUrl,
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
      // 发行根的 package.json 声明 ESM 与运行时依赖；依赖目录用软链复用已安装的生产依赖。
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
      Path configFile = dataDir.resolve("config.json");
      writePrivate(configFile, "{\"studioUrl\":\"" + studioUrl + "\"}\n");
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
      // Shell、extension host 和 code-server 的配置/缓存只能落在本夹具目录。
      builder.environment().put("HOME", homeDir.toString());
      builder.environment().put("XDG_CONFIG_HOME", homeDir.toString());
      builder.environment().put("XDG_CACHE_HOME", homeDir.toString());
      builder.environment().put("XDG_DATA_HOME", homeDir.toString());
      builder.environment().put("TMPDIR", homeDir.toString());
      // 只连接本机 Studio 与 loopback code-server：显式直连，避免继承到的环境代理影响链路。
      builder.environment().put("no_proxy", "127.0.0.1,localhost,::1");
      builder.environment().put("NO_PROXY", "127.0.0.1,localhost,::1");
      started = builder.start();
      // 进程起来之后不再有会失败的步骤：下面的构造只是字段赋值。
      return new ProductionDaemonFixture(
          workspace, distributionDir, dataDir, stdoutLog, stderrLog, started);
    } catch (IOException failure) {
      throw reclaimFailedStart(workspace, logs, started, failure);
    } catch (RuntimeException failure) {
      throw reclaimFailedStart(workspace, logs, started, failure);
    }
  }

  /**
   * 启动失败后的回收：先停掉可能已经起来的进程，再终止它派生的后代，最后删临时目录与空日志。
   *
   * <p>后代句柄必须在停 Daemon <b>之前</b>拍下：Daemon 一死，它的后代就被重新收养、不再是它的后代，只剩句柄能回收它们。
   * 根目录也必须在后代全部停掉之后才删，否则活着的后代会继续占用已删除的目录。
   *
   * <p>删不掉就把路径保留下来并写进失败信息；失败信息只带路径、不带凭据：注册凭据只存在于临时目录里的 0600 {@code daemon.token}，
   * 目录被删掉时凭据一起消失，目录被保留时仍受 0700 目录与 0600 文件保护。
   */
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
      // 进程从未起来，日志一定是空的：删掉，别在 records 目录里留垃圾。
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

  /** 供受管视图使用的夹具自有工作目录（0700），不使用用户主目录。 */
  Path workspaceDirectory() throws IOException {
    Path directory = workspace.resolve("files");
    if (!Files.isDirectory(directory)) {
      Files.createDirectories(directory);
      restrictDirectory(directory);
    }
    return directory;
  }

  /** 当前 Daemon 的后代快照；用于只操作本夹具拥有的子进程（例如模拟 code-server 崩溃）。 */
  List<ProcessHandle> descendants() {
    return process.toHandle().descendants().toList();
  }

  /**
   * 模拟受管 code-server 崩溃：只强制结束本 Daemon 自己的 {@code entry.js} 子进程。
   *
   * <p>句柄来自 {@link #descendants()} 快照，属于本夹具拥有的进程范围；结束前不触碰 Daemon 本身。
   *
   * <p>强杀<b>之前</b>先拍下 code-server 自己的后代：父进程一旦死亡，它们会被重新收养、不再是 Daemon 的后代， {@link #close()}
   * 只能靠这份快照把它们一并回收。
   */
  void crashCodeServer() {
    ProcessHandle target =
        descendants().stream()
            .filter(handle -> isCodeServer(handle))
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

  /** 当前是否存在本 Daemon 的受管 code-server 子进程。 */
  boolean hasCodeServer() {
    return descendants().stream().anyMatch(ProductionDaemonFixture::isCodeServer);
  }

  private static boolean isCodeServer(ProcessHandle handle) {
    String commandLine = handle.info().commandLine().orElse("");
    return commandLine.contains("out/node/entry.js");
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

  /**
   * 要求 {@link #close()} 保留工作目录根，而不是把它删掉。
   *
   * <p>外部清理（例如浏览器驱动）没能确认全部自有进程退出时调用：夹具仍然照常停掉自己的 Daemon 与已捕获的后代，
   * 只是不删除根目录，并把保留的路径写进失败信息，供人工取证。重复调用没有额外效果。
   */
  void preserveWorkspace() {
    preserveWorkspace = true;
  }

  /**
   * 停止自有 Daemon 与它派生的全部后代，确认退出后再删除临时工作目录。
   *
   * <p>顺序固定：<b>先</b>拍下自有句柄快照（{@link ProcessHandle#descendants()} 加上崩溃前捕获的句柄），<b>再</b>停止 Daemon，
   * <b>最后</b>逐个确认快照内的句柄都已退出。Daemon 停不掉也<b>不会</b>中断后代回收：所有问题聚合到同一条失败信息里， 只要还有未解决的进程、或 {@link
   * #preserveWorkspace()} 要求保留工作目录，就不删除工作目录并把路径写进失败信息； 清理失败不吞掉，直接抛给调用方。
   */
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

  /** 自有句柄集合：Daemon 当前的后代，加上崩溃前捕获的受管 code-server 后代。 */
  private List<ProcessHandle> ownedHandles() {
    List<ProcessHandle> owned = new ArrayList<>(process.toHandle().descendants().toList());
    owned.addAll(retainedDescendants);
    return owned;
  }

  /**
   * 终止自有句柄，并逐个确认退出。
   *
   * <p>先礼后兵：{@code destroy} 等不到退出再 {@code destroyForcibly}，两次都等不到就记为未解决，交由调用方聚合；工作目录因此保留。
   */
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

  /** 停止自有进程：先礼后兵；停不掉就如实返回结论，由调用方聚合，不在这里抛。 */
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

  /** 等待自有进程退出；被中断时保留中断标志，并按「未确认退出」处理。 */
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

  /** 等待自有句柄退出；被中断时保留中断标志，并按「未确认退出」处理。 */
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

  /** 退出确认结论。 */
  private enum ExitOutcome {
    /** 已确认进程退出。 */
    EXITED,
    /** 进程仍在运行。 */
    ALIVE,
    /** 等待失败或被中断，退出没有确认。 */
    UNCONFIRMED
  }

  /** 递归复制目录树；文件权限按源保留，目录统一收紧为 owner-only。 */
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

  /** 删除夹具自有临时目录；失败带着路径抛给调用方，不吞掉。 */
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
