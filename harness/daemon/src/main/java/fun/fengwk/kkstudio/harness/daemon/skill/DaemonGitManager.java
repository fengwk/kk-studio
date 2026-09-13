package fun.fengwk.kkstudio.harness.daemon.skill;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 使用宿主 {@code git} 的 Skill 来源获取器。
 *
 * <p>所有命令都以 argv 形式经 {@link ProcessBuilder} 直接执行，绝不经过 shell；URL、ref 与命令 argv 不进入日志或异常文本
 * （异常只给出稳定的结构性原因），因此凭据即使出现在 URL 中也不会被回显。
 *
 * <p>每次 install/update 都在 {@code staging/<unique>} 下构建完整 checkout，成功后才发布到不可变 {@code
 * checkouts/<sourceId>/<commit>}；staging 失败只删除 staging，绝不替换当前快照。指定 ref 的安装只解析一次并固定 commit； 固定 ref
 * 的后续 update 使用已应用 revision，不追随移动的 ref。
 *
 * <p><b>取消语义：</b>本类不持有任何进程级共享状态。每个操作线程只等待并终止<strong>自己</strong>启动的子进程，因此取消一次管理调用只会中断该调用 自己的进程，
 * 不会影响并发执行的其它调用；被中断的进程连同其后代一起被强制终止，且其 staging 目录由调用方清理。
 */
final class DaemonGitManager {

  /** 子进程被中断后等待其终止的时间上限。 */
  private static final long TERMINATION_TIMEOUT_MILLIS = 5000L;

  /** 生产 git 命令前缀：直接执行 argv {@code git}。 */
  private static final List<String> GIT_COMMAND = List.of("git");

  private final DaemonSkillStore store;

  /** 不可变 git 命令前缀；仅测试基座注入确定性包装，生产恒为 {@code ["git"]}。 */
  private final List<String> gitCommand;

  DaemonGitManager(DaemonSkillStore store) {
    this(store, GIT_COMMAND);
  }

  /** 测试专用构造：允许注入确定性的 git 命令前缀，仍然以 argv 直接执行，绝不引入 shell。 */
  DaemonGitManager(DaemonSkillStore store, List<String> gitCommand) {
    this.store = Objects.requireNonNull(store, "store");
    this.gitCommand = List.copyOf(Objects.requireNonNull(gitCommand, "gitCommand"));
    if (this.gitCommand.isEmpty()) {
      throw new IllegalArgumentException("gitCommand must not be empty");
    }
  }

  /**
   * 安装：解析目标 commit 并物化不可变 checkout。
   *
   * @return 解析并固定的 commit
   */
  String install(DaemonSkillSourceConfig config) {
    Path staging = newStaging("git-install");
    boolean published = false;
    try {
      cloneInto(staging, config.url());
      String revision =
          config.ref() == null ? headRevision(staging) : resolvedRevision(staging, config.ref());
      checkoutRevision(staging, revision);
      removeGitMetadata(staging);
      publish(staging, config.sourceId(), revision);
      published = true;
      return revision;
    } finally {
      if (!published) {
        deleteRecursively(staging);
      }
    }
  }

  /**
   * 更新：{@code ref == null} 时显式解析远端默认 HEAD 的新 commit；{@code ref != null} 时固定使用已应用 revision，绝不追随移动
   * ref。只有本地确实缺少该 revision 的 checkout 才访问网络。
   *
   * @return 更新后应用的 commit
   */
  String update(DaemonSkillSourceConfig config) {
    String applied = config.currentlyAppliedRevision();
    if (config.ref() == null || applied == null) {
      // 无固定 ref 时每次都解析新的默认 HEAD；没有已应用 revision 的固定 ref 来源等价于首次安装（解析一次并固定）。
      return install(config);
    }
    String revision = requireCommitId(applied);
    if (Files.isDirectory(store.checkout(config.sourceId(), revision))) {
      return revision;
    }
    // 固定 ref 的已应用 revision 本地缺失：按该 revision 重新物化，绝不重新解析 ref。
    return materialize(config, revision, "git-update");
  }

  /** 物化一个已知 revision 的不可变 checkout；checkout 失败即为显式失败，绝不回退到其他版本。 */
  private String materialize(
      DaemonSkillSourceConfig config, String revision, String stagingPrefix) {
    Path staging = newStaging(stagingPrefix);
    boolean published = false;
    try {
      cloneInto(staging, config.url());
      checkoutRevision(staging, revision);
      removeGitMetadata(staging);
      publish(staging, config.sourceId(), revision);
      published = true;
      return revision;
    } finally {
      if (!published) {
        deleteRecursively(staging);
      }
    }
  }

  private Path newStaging(String prefix) {
    try {
      return store.newStagingDirectory(prefix);
    } catch (IOException error) {
      throw new DaemonSkillException("cannot create git staging directory");
    }
  }

  /**
   * clone 到已存在的空目录。
   *
   * <p>不使用 {@code --no-tags}：轻量/附注 tag 必须随 clone 一起到达，显式 ref 才能在本地解析为固定 commit。
   */
  private void cloneInto(Path staging, String url) {
    requireSuccess(List.of("clone", "--quiet", "--", url, "."), staging);
  }

  /** 远端默认 HEAD 的 commit：{@code rev-parse} 输出带换行，必须先 trim 再作为 revision 使用。 */
  private String headRevision(Path staging) {
    return requireCommitId(capture(List.of("rev-parse", "--verify", "HEAD"), staging).trim());
  }

  /**
   * 解析显式 ref 到固定 commit。
   *
   * <p>显式给出 {@code refs/heads/}、{@code refs/tags/}、{@code refs/remotes/} 前缀时只按该 ref 解析；裸名依次尝试
   * 远端跟踪分支、tag 与本地分支，覆盖轻量 tag、附注 tag（{@code ^{commit}} 自动剥离 tag 对象）与普通可达 commit id。{@code ^{commit}
   * } 保证结果一定是 commit，而不是 tag/树对象；解析失败只报告结构性原因，绝不回显 ref。
   */
  private String resolvedRevision(Path staging, String ref) {
    for (String candidate : refCandidates(ref)) {
      // --quiet 让 git 不回显候选引用名；标准错误同样丢弃，因此候选文本不会出现在任何输出或异常中。
      Executed executed =
          run(List.of("rev-parse", "--verify", "--quiet", candidate + "^{commit}"), staging, true);
      String revision = executed.stdout().trim();
      if (executed.exitCode() == 0 && DaemonSkillSourceConfig.isCommitId(revision)) {
        return revision;
      }
    }
    throw new DaemonSkillException("requested git ref cannot be resolved to a commit");
  }

  /**
   * 显式 ref 的解析候选。
   *
   * <p>{@code clone} 只产生远端跟踪分支与 tag，因此：裸名依次尝试 {@code refs/remotes/origin/<name>}、{@code
   * refs/tags/<name>} 与 {@code refs/heads/<name>}；显式 {@code refs/heads/<name>}
   * 额外尝试同一分支的远端跟踪引用（clone 后本地 通常只有默认分支存在本地引用）；{@code refs/tags/}、{@code refs/remotes/} 与其它 {@code
   * refs/} 前缀只解析该引用本身，避免 猜测掩盖配置错误。完整 commit id 直接作为候选，使 pin 一个可达 commit 无需任何本地引用存在。
   */
  private static List<String> refCandidates(String ref) {
    if (DaemonSkillSourceConfig.isCommitId(ref)) {
      return List.of(ref);
    }
    if (ref.startsWith("refs/heads/")) {
      return List.of(ref, "refs/remotes/origin/" + ref.substring("refs/heads/".length()));
    }
    if (ref.startsWith("refs/")) {
      return List.of(ref);
    }
    return List.of("refs/remotes/origin/" + ref, "refs/tags/" + ref, "refs/heads/" + ref, ref);
  }

  private void checkoutRevision(Path staging, String revision) {
    requireSuccess(List.of("checkout", "--quiet", "--detach", requireCommitId(revision)), staging);
  }

  /**
   * 删除 checkout 内的 .git 元数据：发布目录只是内容快照，不是用户可操作的仓库。
   *
   * <p>失败必须 fail-closed：残留 .git 会让后续扫描看到仓库内部结构，因此删除不完整时显式失败，而绝不发布一个仍带版本库元数据的目录。
   */
  private void removeGitMetadata(Path checkout) {
    Path metadata = checkout.resolve(".git");
    if (!Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    deleteRecursively(metadata);
    if (Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)) {
      throw new DaemonSkillException("cannot remove git metadata from the skill checkout");
    }
  }

  /**
   * 发布 staging 到不可变 {@code checkouts/<sourceId>/<revision>}。
   *
   * <p>同一 {@code (sourceId, revision)} 的内容按定义相同，因此已存在（并发或早先操作发布）时收敛为复用既有目录并清理本次 staging，而不是失败或覆盖。
   */
  private void publish(Path staging, UUID sourceId, String revision) {
    Path target = store.checkout(sourceId, revision);
    if (Files.isDirectory(target)) {
      deleteRecursively(staging);
      return;
    }
    try {
      Files.createDirectories(target.getParent());
    } catch (IOException error) {
      throw new DaemonSkillException("cannot create git checkout directory");
    }
    try {
      Files.move(staging, target);
    } catch (IOException error) {
      // 并发发布同一 revision：move 失败后若目标已经是完整目录，则视为良性收敛，仅清理本次 staging。
      if (Files.isDirectory(target)) {
        deleteRecursively(staging);
        return;
      }
      throw new DaemonSkillException("cannot publish git checkout");
    }
  }

  /**
   * 执行宿主 git 命令并返回退出码与标准输出。
   *
   * <p>标准错误被丢弃：git 会把 URL/ref 等参数回显到 stderr，因此不采集它，异常文本只保留稳定的结构性原因。
   *
   * <p>操作线程只等待自己的进程：被中断时先终止该进程及其后代并等待其结束，再恢复中断状态并抛出结构性原因，因此取消不会遗留运行中的 git，也不会触碰其它 并发调用 的进程。
   *
   * <p>只有输出天然有界的 {@code rev-parse} 才捕获 stdout；clone/checkout 直接丢弃 stdout，杜绝管道写满造成的子进程阻塞。
   */
  private Executed run(List<String> arguments, Path workingDirectory, boolean captureStdout) {
    // 启动新命令前检查中断：被取消的操作不应再启动新的子进程。
    if (Thread.currentThread().isInterrupted()) {
      throw new DaemonSkillException("interrupted while running host git");
    }
    List<String> argv = new ArrayList<>(gitCommand.size() + arguments.size());
    argv.addAll(gitCommand);
    argv.addAll(arguments);
    ProcessBuilder builder = new ProcessBuilder(argv).directory(workingDirectory.toFile());
    builder.redirectError(ProcessBuilder.Redirect.DISCARD);
    builder.redirectOutput(
        captureStdout ? ProcessBuilder.Redirect.PIPE : ProcessBuilder.Redirect.DISCARD);
    // 交互式凭据提示会让操作悬挂在等待输入上；非交互式失败才是可收敛的结构性错误。
    builder.environment().put("GIT_TERMINAL_PROMPT", "0");
    Process process;
    try {
      process = builder.start();
    } catch (IOException error) {
      throw new DaemonSkillException("host git is not available");
    }
    try {
      int exitCode = process.waitFor();
      return new Executed(exitCode, captureStdout ? readStdout(process) : "");
    } catch (InterruptedException error) {
      terminate(process);
      Thread.currentThread().interrupt();
      throw new DaemonSkillException("interrupted while running host git");
    }
  }

  /** 读取已完成进程的标准输出；只有 rev-parse 这类有界输出会走到这里。 */
  private static String readStdout(Process process) {
    try (InputStream input = process.getInputStream()) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new DaemonSkillException("cannot read host git output");
    }
  }

  /** 终止子进程树并等待其结束；调用方负责恢复中断状态。 */
  private static void terminate(Process process) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
    try {
      process.waitFor(TERMINATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException ignored) {
      // 清理不抛出：中断状态由调用方在返回前恢复。
    }
  }

  /** 执行必须成功且丢弃输出的 git 命令（clone/checkout 的 stdout 无意义）。 */
  private void requireSuccess(List<String> arguments, Path workingDirectory) {
    if (run(arguments, workingDirectory, false).exitCode() != 0) {
      throw new DaemonSkillException("host git command failed");
    }
  }

  /** 执行必须成功且有界捕获 stdout 的 git 命令（仅 rev-parse）。 */
  private String capture(List<String> arguments, Path workingDirectory) {
    Executed executed = run(arguments, workingDirectory, true);
    if (executed.exitCode() != 0) {
      throw new DaemonSkillException("host git command failed");
    }
    return executed.stdout();
  }

  /**
   * 校验 revision 是完整的小写 40/64 位 commit id。
   *
   * <p>revision 随后会作为 checkout 目录段与 git 参数使用，因此非法形状必须在触达路径与子进程之前显式失败；错误不回显该值。
   */
  private static String requireCommitId(String revision) {
    if (!DaemonSkillSourceConfig.isCommitId(revision)) {
      throw new DaemonSkillException("git revision is not a lowercase commit id");
    }
    return revision;
  }

  /** 一次 git 执行的退出码与标准输出。 */
  private record Executed(int exitCode, String stdout) {}

  /**
   * 递归删除路径。
   *
   * <p>删除失败只影响清理：已发布快照不受影响，失败的具体路径也不会出现在任何错误文本中。
   */
  private static void deleteRecursively(Path path) {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    List<Path> paths = new ArrayList<>();
    try (Stream<Path> stream = Files.walk(path)) {
      stream.forEach(paths::add);
    } catch (IOException error) {
      return;
    }
    paths.sort(Comparator.reverseOrder());
    for (Path entry : paths) {
      try {
        Files.deleteIfExists(entry);
      } catch (DirectoryNotEmptyException ignored) {
        // 并发写入者仍持有目录条目：保留目录本身，不做无意义重试。
      } catch (IOException ignored) {
        // 清理失败不影响已发布快照。
      }
    }
  }
}
