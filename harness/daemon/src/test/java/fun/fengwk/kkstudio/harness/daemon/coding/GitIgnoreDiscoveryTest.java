package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapability;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Git 忽略解析的共享契约：从检索目标自身向上发现祖先 {@code .gitignore} 与 {@code .git/info/exclude}，支持 {@code .git} 文件形式的
 * linked worktree，且不以调用 workdir 作为继承边界。同一目标在相对/绝对 path 与不同 workdir 下必须得到一致的匹配。
 */
class GitIgnoreDiscoveryTest {

  @TempDir Path repositoryRoot;
  @TempDir Path worktreeRoot;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  @AfterEach
  void closeExecutor() {
    executor.shutdownNow();
  }

  /** 祖先 {@code .gitignore} 必须作用于 workdir 之外的目标：workdir 落在子目录时，仓库根的忽略规则仍然生效，且换一个 workdir 不会改变匹配。 */
  @Test
  void ancestorGitignoreAppliesRegardlessOfWorkdirBoundary() throws Exception {
    Files.createDirectories(repositoryRoot.resolve(".git"));
    Files.writeString(repositoryRoot.resolve(".gitignore"), "ignored.txt\n");
    Files.createDirectories(repositoryRoot.resolve("sub"));
    Files.writeString(repositoryRoot.resolve("sub/keep.txt"), "needle\n");
    Files.writeString(repositoryRoot.resolve("sub/ignored.txt"), "needle\n");

    // workdir 是仓库根：规则当然生效。
    String fromRepositoryRoot =
        text(
            invoke(
                grep(config()),
                "{\"pattern\":\"needle\",\"path\":\"sub\",\"workdir\":"
                    + json(repositoryRoot.toString())
                    + "}"));

    // workdir 落在子目录：旧实现把 workdir 当作继承边界，会漏掉仓库根规则并错误命中 ignored.txt。
    String fromSubdirectory =
        text(
            invoke(
                grep(config()),
                "{\"pattern\":\"needle\",\"path\":\".\",\"workdir\":"
                    + json(repositoryRoot.resolve("sub").toString())
                    + "}"));

    assertTrue(fromRepositoryRoot.contains("keep.txt:1:needle"), fromRepositoryRoot);
    assertFalse(fromRepositoryRoot.contains("ignored.txt"), fromRepositoryRoot);
    assertTrue(fromSubdirectory.contains("keep.txt:1:needle"), fromSubdirectory);
    assertFalse(fromSubdirectory.contains("ignored.txt"), fromSubdirectory);
  }

  /** {@code .git/info/exclude} 与 {@code .gitignore} 同源解析：排除结果对 grep 与 find 一致。 */
  @Test
  void gitInfoExcludeAppliesFromRepositoryGitDirectory() throws Exception {
    Path info = Files.createDirectories(repositoryRoot.resolve(".git/info"));
    Files.writeString(info.resolve("exclude"), "excluded.txt\n");
    Files.writeString(repositoryRoot.resolve("keep.txt"), "needle\n");
    Files.writeString(repositoryRoot.resolve("excluded.txt"), "needle\n");

    String grepped =
        text(
            invoke(
                grep(config()),
                "{\"pattern\":\"needle\",\"path\":\".\",\"workdir\":"
                    + json(repositoryRoot.toString())
                    + "}"));
    assertTrue(grepped.contains("keep.txt:1:needle"), grepped);
    assertFalse(grepped.contains("excluded.txt"), grepped);

    String found =
        text(
            invoke(
                find(config()),
                "{\"pattern\":\"*.txt\",\"path\":\".\",\"workdir\":"
                    + json(repositoryRoot.toString())
                    + "}"));
    assertTrue(found.contains("keep.txt"), found);
    assertFalse(found.contains("excluded.txt"), found);
  }

  /**
   * {@code .git} 文件形式的 linked worktree：忽略规则来自 {@code gitdir} 指针与 {@code commondir} 指向的公共 git 目录，而不是
   * worktree 内不存在的 {@code .git/index} 之类文件。
   */
  @Test
  void gitFileWorktreeResolvesInfoExcludeThroughCommonDir() throws Exception {
    Path commonGitDir = Files.createDirectories(repositoryRoot.resolve("main/.git"));
    Path linkedGitDir = Files.createDirectories(commonGitDir.resolve("worktrees/wt"));
    Files.writeString(linkedGitDir.resolve("commondir"), "../..\n");
    Files.createDirectories(commonGitDir.resolve("info"));
    Files.writeString(commonGitDir.resolve("info/exclude"), "excluded.txt\n");

    Files.writeString(
        worktreeRoot.resolve(".git"), "gitdir: " + linkedGitDir.toAbsolutePath() + "\n");
    Files.writeString(worktreeRoot.resolve("keep.txt"), "needle\n");
    Files.writeString(worktreeRoot.resolve("excluded.txt"), "needle\n");

    String grepped =
        text(
            invoke(
                grep(config()),
                "{\"pattern\":\"needle\",\"path\":\".\",\"workdir\":"
                    + json(worktreeRoot.toString())
                    + "}"));

    assertTrue(grepped.contains("keep.txt:1:needle"), grepped);
    assertFalse(grepped.contains("excluded.txt"), grepped);
  }

  /** 相对 path 与绝对 path 指向同一目标时必须给出完全一致的匹配结果（忽略解析只依赖目标自身）。 */
  @Test
  void absoluteAndRelativePathsProduceIdenticalMatches() throws Exception {
    Files.createDirectories(repositoryRoot.resolve("sub/nested"));
    Files.writeString(repositoryRoot.resolve("sub/a.txt"), "needle\n");
    Files.writeString(repositoryRoot.resolve("sub/nested/b.txt"), "needle\n");

    String workdir = json(repositoryRoot.toString());
    String relativeGrep =
        text(
            invoke(
                grep(config()),
                "{\"pattern\":\"needle\",\"path\":\"sub\",\"workdir\":" + workdir + "}"));
    String absoluteGrep =
        text(
            invoke(
                grep(config()),
                "{\"pattern\":\"needle\",\"path\":"
                    + json(repositoryRoot.resolve("sub").toString())
                    + ",\"workdir\":"
                    + workdir
                    + "}"));
    assertEquals(relativeGrep, absoluteGrep);

    String relativeFind =
        text(
            invoke(
                find(config()),
                "{\"pattern\":\"*.txt\",\"path\":\"sub\",\"workdir\":" + workdir + "}"));
    String absoluteFind =
        text(
            invoke(
                find(config()),
                "{\"pattern\":\"*.txt\",\"path\":"
                    + json(repositoryRoot.resolve("sub").toString())
                    + ",\"workdir\":"
                    + workdir
                    + "}"));
    assertEquals(relativeFind, absoluteFind);
    assertTrue(relativeFind.contains("nested/b.txt"), relativeFind);
  }

  /** 注释/空行构成的 {@code .gitignore} 不产生任何规则，不影响正常匹配。 */
  @Test
  void commentOnlyGitignoreAddsNoRules() throws Exception {
    Files.writeString(
        repositoryRoot.resolve(".gitignore"), "# comment only\n\n# another comment\n");
    Files.writeString(repositoryRoot.resolve("keep.txt"), "needle\n");

    String grepped =
        text(
            invoke(
                grep(config()),
                "{\"pattern\":\"needle\",\"path\":\".\",\"workdir\":"
                    + json(repositoryRoot.toString())
                    + "}"));

    assertTrue(grepped.contains("keep.txt:1:needle"), grepped);
  }

  /**
   * 无法解析的 {@code .git} 指针按“没有可用 exclude”降级，搜索照常进行且 {@code .git} 仍被硬排除：非指针内容、空 {@code
   * gitdir:}、包含非法字符的 {@code gitdir:}、以及指向不存在目标的符号链接都必须被安全忽略。
   */
  @Test
  void unparseableGitFilePointerIsIgnoredGracefully() throws Exception {
    Files.writeString(repositoryRoot.resolve("keep.txt"), "needle\n");
    Path dotGit = repositoryRoot.resolve(".git");

    for (String pointer :
        new String[] {"not-a-gitdir-pointer\n", "gitdir:\n", "gitdir: bad\u0000path\n"}) {
      Files.writeString(dotGit, pointer);
      assertSearchesWithoutGitMetadata(repositoryRoot);
    }

    Files.delete(dotGit);
    try {
      Files.createSymbolicLink(dotGit, repositoryRoot.resolve("missing-gitdir"));
    } catch (UnsupportedOperationException | IOException unsupported) {
      // 平台不支持符号链接：损坏指针这一子场景无法构造，其余场景已完成验证。
      return;
    }
    assertSearchesWithoutGitMetadata(repositoryRoot);
  }

  /** 相对 {@code gitdir:} 指针以仓库根为基准解析；{@code commondir} 为空或非法时回退到 worktree 自己的 git 目录。 */
  @Test
  void relativeGitDirPointerWithUnusableCommonDirFallsBackToWorktreeGitDir() throws Exception {
    Path linkedGitDir = Files.createDirectories(worktreeRoot.resolve("wt-gitdir"));
    Files.createDirectories(linkedGitDir.resolve("info"));
    Files.writeString(linkedGitDir.resolve("info/exclude"), "excluded.txt\n");
    Files.writeString(worktreeRoot.resolve(".git"), "gitdir: wt-gitdir\n");
    Files.writeString(worktreeRoot.resolve("keep.txt"), "needle\n");
    Files.writeString(worktreeRoot.resolve("excluded.txt"), "needle\n");

    Files.writeString(linkedGitDir.resolve("commondir"), "\n");
    assertSearchesWithoutExcludedFile(worktreeRoot);

    Files.writeString(linkedGitDir.resolve("commondir"), "bad\u0000dir\n");
    assertSearchesWithoutExcludedFile(worktreeRoot);
  }

  /** {@code commondir} 为绝对路径时同样解析到公共 git 目录的 {@code info/exclude}。 */
  @Test
  void absoluteCommonDirPointerResolvesInfoExclude() throws Exception {
    Path commonGitDir = Files.createDirectories(repositoryRoot.resolve("main/.git"));
    Files.createDirectories(commonGitDir.resolve("info"));
    Files.writeString(commonGitDir.resolve("info/exclude"), "excluded.txt\n");
    Path linkedGitDir = Files.createDirectories(worktreeRoot.resolve("wt-gitdir"));
    Files.writeString(linkedGitDir.resolve("commondir"), commonGitDir.toAbsolutePath() + "\n");
    Files.writeString(worktreeRoot.resolve(".git"), "gitdir: " + linkedGitDir + "\n");
    Files.writeString(worktreeRoot.resolve("keep.txt"), "needle\n");
    Files.writeString(worktreeRoot.resolve("excluded.txt"), "needle\n");

    assertSearchesWithoutExcludedFile(worktreeRoot);
  }

  /** 辅助断言：{@code root} 内必须命中 {@code keep.txt}，且被 excludes 声明的 {@code excluded.txt} 不得出现。 */
  private void assertSearchesWithoutExcludedFile(Path root) throws Exception {
    String grepped =
        text(
            invoke(
                grep(config()),
                "{\"pattern\":\"needle\",\"path\":\".\",\"workdir\":"
                    + json(root.toString())
                    + "}"));
    assertTrue(grepped.contains("keep.txt:1:needle"), grepped);
    assertFalse(grepped.contains("excluded.txt"), grepped);
  }

  /** 辅助断言：{@code root} 内必须正常命中内容，且 {@code .git} 元数据永不出现。 */
  private void assertSearchesWithoutGitMetadata(Path root) throws Exception {
    String grepped =
        text(
            invoke(
                grep(config()),
                "{\"pattern\":\"needle\",\"path\":\".\",\"workdir\":"
                    + json(root.toString())
                    + "}"));
    assertTrue(grepped.contains("keep.txt:1:needle"), grepped);
    assertFalse(grepped.contains(".git"), grepped);
  }

  private CodingToolsConfig config() {
    return TestCodingConfig.withLimits(repositoryRoot, 2000, 50 * 1024);
  }

  private GrepCapability grep(CodingToolsConfig config) {
    return new GrepCapability(config, executor);
  }

  private FindCapability find(CodingToolsConfig config) {
    return new FindCapability(config, executor);
  }

  /** 以 JSON 字符串字面量表示任意本地路径，避免手工拼接转义。 */
  private static String json(String value) throws Exception {
    return AbstractCodingCapability.OBJECT_MAPPER.writeValueAsString(value);
  }

  private EnvironmentCapabilityResult invoke(EnvironmentCapability capability, String arguments)
      throws Exception {
    RecordingListener listener = new RecordingListener();
    capability.execute(
        new EnvironmentCapabilityExecutionRequest(
            capability.descriptor(),
            new EnvironmentCapabilityCall("git-ignore-discovery", arguments),
            Duration.ZERO),
        listener);
    assertTrue(listener.completed.await(30, TimeUnit.SECONDS));
    return listener.result;
  }

  private static String text(EnvironmentCapabilityResult result) {
    return result.contents().stream().map(GitIgnoreDiscoveryTest::text).reduce("", String::concat);
  }

  private static String text(ResultContent content) {
    return content instanceof TextResultContent value ? value.text() : "";
  }

  private static final class RecordingListener implements EnvironmentCapabilityExecutionListener {
    private final CountDownLatch completed = new CountDownLatch(1);
    private volatile EnvironmentCapabilityResult result;

    @Override
    public void onPartial(EnvironmentCapabilityResult partial) {}

    @Override
    public void onComplete(EnvironmentCapabilityResult result) {
      this.result = result;
      completed.countDown();
    }

    @Override
    public void onError(Throwable error) {
      throw new AssertionError(error);
    }
  }
}
