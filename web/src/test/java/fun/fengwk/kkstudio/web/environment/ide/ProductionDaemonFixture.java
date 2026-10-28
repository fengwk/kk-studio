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
 * destroyForcibly 并等待确认退出；只有全部确认退出才删除临时目录，否则保留目录并抛异常带出路径。 不做全盘进程扫描，也不按命令行模糊匹配，因而不可能命中别人的进程。
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
    Path stderrLog = Files.createTempFile(recordsDir, "ide-acceptance-daemon-", ".err.log");
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
    // 只连接本机 Studio 与 loopback code-server：显式直连，避免继承到的环境代理影响链路。
    builder.environment().put("no_proxy", "127.0.0.1,localhost,::1");
    builder.environment().put("NO_PROXY", "127.0.0.1,localhost,::1");
    Process process = builder.start();
    return new ProductionDaemonFixture(
        workspace, distributionDir, dataDir, stdoutLog, stderrLog, process);
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
   */
  void crashCodeServer() {
    ProcessHandle target =
        descendants().stream()
            .filter(handle -> isCodeServer(handle))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException("the daemon started no managed code-server process"));
    target.destroyForcibly();
    if (!awaitExit(target)) {
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
   * 停止自有 Daemon 与它派生的全部后代，确认退出后再删除临时工作目录。
   *
   * <p>顺序固定：<b>先</b>用 {@link ProcessHandle#descendants()} 快照后代句柄（句柄自带 pid + 启动时刻身份，重放的 pid 不会误伤），
   * <b>再</b>停止 Daemon，最后逐个确认快照内的句柄都已退出。任何一个仍存活就保留工作目录并把路径写进失败信息； 清理失败不吞掉，直接抛给调用方。
   */
  @Override
  public void close() {
    List<ProcessHandle> owned = process.toHandle().descendants().toList();
    stopDaemon();
    terminateOwnedDescendants(owned);
    deleteTree(workspace);
  }

  private void stopDaemon() {
    ProcessHandle handle = process.toHandle();
    handle.destroy();
    boolean exited = awaitExit(process);
    if (!exited) {
      handle.destroyForcibly();
      exited = awaitExit(process);
    }
    if (!exited) {
      throw new IllegalStateException("the owned daemon did not exit; kept workspace " + workspace);
    }
  }

  private boolean awaitExit(Process process) {
    try {
      return process.waitFor(STOP_GRACE_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return !process.isAlive();
    }
  }

  /**
   * 终止 Daemon 停止前拍下的后代句柄，并逐个确认退出。
   *
   * <p>先礼后兵：{@code destroy} 等不到退出再 {@code destroyForcibly}，两次都等不到就抛异常，工作目录保留。
   */
  private void terminateOwnedDescendants(List<ProcessHandle> owned) {
    List<String> survivors = new ArrayList<>();
    for (ProcessHandle handle : owned) {
      if (!handle.isAlive()) {
        continue;
      }
      handle.destroy();
      if (!awaitExit(handle)) {
        handle.destroyForcibly();
      }
      if (!awaitExit(handle)) {
        survivors.add(describe(handle));
      }
    }
    if (!survivors.isEmpty()) {
      throw new IllegalStateException(
          "owned descendants did not exit ("
              + String.join(", ", survivors)
              + "); kept workspace "
              + workspace);
    }
  }

  private boolean awaitExit(ProcessHandle handle) {
    try {
      handle.onExit().get(STOP_GRACE_SECONDS, TimeUnit.SECONDS);
      return true;
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return !handle.isAlive();
    } catch (TimeoutException error) {
      return !handle.isAlive();
    } catch (ExecutionException error) {
      return !handle.isAlive();
    }
  }

  private static String describe(ProcessHandle handle) {
    String command = handle.info().command().orElse("unknown");
    return "pid=" + handle.pid() + " command=" + command;
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
