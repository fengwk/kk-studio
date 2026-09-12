package fun.fengwk.kkstudio.harness.daemon.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceConfig;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceSnapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GIT 来源的 install/update/refresh 语义：固定 commit、ref 固定不追随移动、失败回滚、取消与错误文本不含 URL。
 *
 * <p>测试意图：全部使用本地临时仓库（{@code file://} URL 与本地 {@code git init/commit}），不访问网络；仓库路径由测试在运行时
 * 生成，因此源码中不固定任何具体 URL。缺少宿主 git 或无法建立本地仓库时跳过，而不是失败。
 */
class DaemonGitManagerTest {

  private static final UUID SOURCE_ID = DaemonSkillTestSupport.SOURCE_ID;

  @TempDir Path tempDir;

  private Path remote;
  private Path dataDir;

  @BeforeEach
  void setUp() {
    assumeTrue(hostGitAvailable(), "host git is required for GIT source tests");
    remote = tempDir.resolve("remote");
    dataDir = tempDir.resolve("data");
  }

  /** install：首次解析默认 HEAD 并固定为不可变 checkout，正文按内容 revision 精确加载。 */
  @Test
  void installFixesHeadRevisionAndPublishesSkills() throws Exception {
    initRepository(remote, "alpha");
    DaemonSkillRegistry registry = DaemonSkillRegistry.open(dataDir, tempDir);

    DaemonSkillSourceSnapshot snapshot = registry.install(git(null, null));

    assertEquals(1, snapshot.skills().size());
    assertEquals("alpha", snapshot.skills().get(0).name());
    assertEquals(commit(remote), snapshot.sourceRevision());
    assertEquals(
        "# alpha",
        registry
            .load(SOURCE_ID, "alpha", snapshot.skills().get(0).contentRevision())
            .orElseThrow()
            .body());
  }

  /** refresh 只扫描已应用的本地 checkout：远端新增 commit 不会被 refresh 拉取。 */
  @Test
  void refreshNeverFetchesFromTheRemote() throws Exception {
    initRepository(remote, "alpha");
    DaemonSkillRegistry registry = DaemonSkillRegistry.open(dataDir, tempDir);
    DaemonSkillSourceSnapshot installed = registry.install(git(null, null));
    addCommit(remote, "beta");

    DaemonSkillSourceSnapshot refreshed =
        registry.refresh(git(null, null, installed.sourceRevision()));

    assertEquals(installed.sourceRevision(), refreshed.sourceRevision());
    assertEquals(List.of("alpha"), names(refreshed));
    // 远端已有新 commit，但 refresh 绝不联网：checkout 内不存在 beta。
    assertFalse(
        Files.exists(
            dataDir
                .resolve("skills/checkouts")
                .resolve(SOURCE_ID.toString())
                .resolve(installed.sourceRevision())
                .resolve("beta")));
  }

  /** ref 非空时安装固定 ref 指向的 commit；后续 update 复用已应用 revision，绝不追随移动的 ref。 */
  @Test
  void fixedRefInstallPinsCommitAndUpdateNeverFollowsMovingRef() throws Exception {
    initRepository(remote, "alpha");
    run(remote, "git", "branch", "stable");
    DaemonSkillRegistry registry = DaemonSkillRegistry.open(dataDir, tempDir);

    DaemonSkillSourceSnapshot installed = registry.install(git("stable", null));
    String pinned = installed.sourceRevision();

    // ref 前进到新 commit：固定 ref 的 update 必须仍使用已应用 revision。
    addCommit(remote, "beta");
    run(remote, "git", "branch", "-f", "stable", "HEAD");
    DaemonSkillSourceSnapshot updated =
        registry.update(git("stable", null, installed.sourceRevision()));

    assertEquals(pinned, updated.sourceRevision());
    assertEquals(List.of("alpha"), names(updated));
  }

  /** 固定 ref 已应用 revision 的本地 checkout 缺失时按该 revision 重新物化，而不是解析最新的 ref。 */
  @Test
  void fixedRefUpdateRestoresMissingAppliedRevision() throws Exception {
    initRepository(remote, "alpha");
    run(remote, "git", "branch", "stable");
    DaemonSkillRegistry registry = DaemonSkillRegistry.open(dataDir, tempDir);
    DaemonSkillSourceSnapshot installed = registry.install(git("stable", null));
    String pinned = installed.sourceRevision();
    // ref 前进到新 commit：重建必须仍然落在 pinned，而不是新 ref 指向的 commit。
    addCommit(remote, "beta");
    run(remote, "git", "branch", "-f", "stable", "HEAD");
    deleteRecursively(dataDir.resolve("skills/checkouts"));

    DaemonSkillSourceSnapshot updated = registry.update(git("stable", null, pinned));

    assertEquals(pinned, updated.sourceRevision());
    assertEquals(List.of("alpha"), names(updated));
  }

  /** ref 为空时 update 显式解析新的默认 HEAD，因此能拿到新 commit 与新 skill 集合。 */
  @Test
  void updateWithoutRefResolvesNewDefaultHead() throws Exception {
    initRepository(remote, "alpha");
    DaemonSkillRegistry registry = DaemonSkillRegistry.open(dataDir, tempDir);
    DaemonSkillSourceSnapshot installed = registry.install(git(null, null));

    addCommit(remote, "beta");
    DaemonSkillSourceSnapshot updated =
        registry.update(git(null, null, installed.sourceRevision()));

    assertNotEquals(installed.sourceRevision(), updated.sourceRevision());
    assertEquals(commit(remote), updated.sourceRevision());
    assertEquals(List.of("alpha", "beta"), names(updated));
  }

  /** Git 来源的 skill 正文仍按内容 revision 命名：同一内容跨 commit 复用同一 blob。 */
  @Test
  void unchangedSkillContentKeepsItsContentRevisionAcrossCommits() throws Exception {
    initRepository(remote, "alpha");
    DaemonSkillRegistry registry = DaemonSkillRegistry.open(dataDir, tempDir);
    DaemonSkillSourceSnapshot first = registry.install(git(null, null));
    String firstContentRevision = first.skills().get(0).contentRevision();

    addCommit(remote, "beta");
    DaemonSkillSourceSnapshot second = registry.update(git(null, null, first.sourceRevision()));

    assertNotEquals(first.sourceRevision(), second.sourceRevision());
    assertEquals(firstContentRevision, second.skills().get(0).contentRevision());
  }

  /** scanPath 限定仓库内子目录；越界 scanPath 被拒绝且不发布任何内容。 */
  @Test
  void scanPathSelectsRepositorySubdirectoryAndRejectsEscapes() throws Exception {
    initRepository(remote, "alpha");
    writeSkill(remote.resolve("nested"), "nested");
    addAll(remote, "nested");
    DaemonSkillRegistry registry = DaemonSkillRegistry.open(dataDir, tempDir);

    DaemonSkillSourceSnapshot snapshot = registry.install(git(null, "nested"));

    assertEquals(List.of("nested"), names(snapshot));

    // 仓库相对路径在配置构造期就被拒绝：越界 scanPath 永远无法形成合法来源配置。
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> git(null, "../escape"));
    assertFalse(error.getMessage().contains("../escape"));
    assertEquals(1, registry.snapshots().size());
    assertEquals(List.of("nested"), names(registry.snapshots().get(0)));
  }

  /** 获取失败必须回滚：staging 被清理，已发布快照保持不变。 */
  @Test
  void failedFetchRollsBackWithoutTouchingPublishedSnapshot() throws Exception {
    initRepository(remote, "alpha");
    DaemonSkillRegistry registry = DaemonSkillRegistry.open(dataDir, tempDir);
    DaemonSkillSourceSnapshot published = registry.install(git(null, null));

    DaemonSkillSourceConfig brokenSource =
        DaemonSkillSourceConfig.git(
            SOURCE_ID,
            2,
            2,
            tempDir.resolve("does-not-exist").toUri().toString(),
            null,
            null,
            published.sourceRevision(),
            Set.of(SOURCE_ID));
    DaemonSkillException error =
        assertThrows(DaemonSkillException.class, () -> registry.install(brokenSource));

    // 异常只给结构性原因：不回显 URL。
    assertFalse(error.getMessage().contains("does-not-exist"));
    assertEquals(List.of(published), registry.snapshots());
    assertTrue(stagingIsEmpty(dataDir));
  }

  /** PATH 来源没有 install/update 语义：显式拒绝，而 refresh(PATH) 仍是合法的本地扫描。 */
  @Test
  void rejectsPathSourceForInstallAndUpdateButAllowsRefresh() throws Exception {
    Path skillRoot = tempDir.resolve("path-skills");
    writeSkill(skillRoot.resolve("local"), "local");
    DaemonSkillRegistry registry = DaemonSkillRegistry.open(dataDir, tempDir);
    DaemonSkillSourceConfig pathSource =
        DaemonSkillSourceConfig.path(
            SOURCE_ID, 1, 1, skillRoot.toString(), false, Set.of(SOURCE_ID));

    DaemonSkillException installError =
        assertThrows(DaemonSkillException.class, () -> registry.install(pathSource));
    assertTrue(installError.getMessage().contains("git"));
    DaemonSkillException updateError =
        assertThrows(DaemonSkillException.class, () -> registry.update(pathSource));
    assertTrue(updateError.getMessage().contains("git"));
    assertTrue(registry.snapshots().isEmpty());

    DaemonSkillSourceSnapshot refreshed = registry.refresh(pathSource);
    assertEquals(List.of("local"), names(refreshed));
  }

  /**
   * 取消只作用于本次调用：中断一个正在等待子进程的 Git 操作不会终止并发执行的另一个操作。
   *
   * <p>两个操作共用同一 registry，因此这条测试同时证明 Git 获取发生在发布保护区<strong>之外</strong>（两者能同时在途），以及取消是调用级事实
   * （另一个子进程照常完成）。同步只依赖 started 标记文件，不依赖固定时长。
   */
  @Test
  void cancellingOneGitOperationDoesNotTerminateAnother() throws Exception {
    Path control = Files.createDirectories(tempDir.resolve("fake-git-control"));
    DaemonSkillRegistry registry = fakeGitRegistry(control);
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      Future<?> cancelled =
          executor.submit(() -> registry.install(fakeGitSource("blocked", control, "block", 1)));
      Future<DaemonSkillSourceSnapshot> concurrent =
          executor.submit(() -> registry.install(fakeGitSource("concurrent", control, "block", 2)));
      // 两者都已在等待自己的子进程：既证明并发不被保护区串行化，也让取消具备"是否误杀"的判别力。
      awaitMarker(control, "blocked.started");
      awaitMarker(control, "concurrent.started");

      cancelled.cancel(true);

      // 释放并发操作的 gate：它必须仍然成功完成，因为被取消的进程只属于另一个操作。
      Files.writeString(control.resolve("concurrent.gate"), "released");
      DaemonSkillSourceSnapshot published = concurrent.get(60, TimeUnit.SECONDS);

      assertEquals(List.of("concurrent"), names(published));
      assertEquals(FakeGitCommand.COMMIT_ID, published.sourceRevision());
      assertTrue(Files.exists(control.resolve("concurrent.released")));
      // 两个子进程的 pid 不同：取消不会误杀另一个操作。
      assertNotEquals(
          Files.readString(control.resolve("blocked.pid")),
          Files.readString(control.resolve("concurrent.pid")));
      // 被取消的操作不发布任何内容（其 skill 不在目录中），且自己的 staging 被清理。
      assertFalse(Files.exists(control.resolve("blocked.released")));
      assertEquals(List.of("concurrent"), names(registry.snapshots().get(0)));
      assertTrue(stagingIsEmpty(dataDir(control)));
    } finally {
      executor.shutdownNow();
      // 释放可能仍在等待的 fake git，避免其等到超时。
      Files.writeString(control.resolve("blocked.gate"), "released");
      Files.writeString(control.resolve("concurrent.gate"), "released");
    }
  }

  /**
   * 同一来源允许在 Platform UNKNOWN 后开始新的管理操作，但较早操作的迟到结果不能覆盖较新操作。
   *
   * <p>两个操作的配置版本相同，且使用不同 fake 内容。后启动的操作先发布；随后放行先启动的操作时，它必须被来源级启动序号栅栏拒绝，最终目录保持较新结果。
   */
  @Test
  void olderOverlappingOperationCannotOverwriteNewerPublication() throws Exception {
    Path control = Files.createDirectories(tempDir.resolve("fake-git-control"));
    DaemonSkillRegistry registry = fakeGitRegistry(control);
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      Future<DaemonSkillSourceSnapshot> older =
          executor.submit(
              () -> registry.install(fakeGitSource("older", control, "block", "older-ref", 1)));
      awaitMarker(control, "older.started");

      Future<DaemonSkillSourceSnapshot> newer =
          executor.submit(
              () -> registry.install(fakeGitSource("newer", control, "block", "newer-ref", 1)));
      awaitMarker(control, "newer.started");
      Files.writeString(control.resolve("newer.gate"), "released");
      assertEquals(List.of("newer"), names(newer.get(60, TimeUnit.SECONDS)));

      Files.writeString(control.resolve("older.gate"), "released");
      ExecutionException superseded =
          assertThrows(ExecutionException.class, () -> older.get(60, TimeUnit.SECONDS));
      assertTrue(superseded.getCause() instanceof DaemonSkillException);

      assertEquals(List.of("newer"), names(registry.snapshots().get(0)));
      assertTrue(stagingIsEmpty(dataDir(control)));
    } finally {
      executor.shutdownNow();
      Files.writeString(control.resolve("older.gate"), "released");
      Files.writeString(control.resolve("newer.gate"), "released");
    }
  }

  /**
   * sourceVersion 比启动 ticket 更权威：新配置的慢操作即使更早启动，也必须在旧配置晚启动并先发布后覆盖它。
   *
   * <p>否则线程调度反转会让旧配置仅凭更大的进程内 ticket 永久压过新配置。
   */
  @Test
  void newerSourceVersionWinsEvenWhenItsOperationStartedEarlier() throws Exception {
    Path control = Files.createDirectories(tempDir.resolve("fake-git-control"));
    DaemonSkillRegistry registry = fakeGitRegistry(control);
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      Future<DaemonSkillSourceSnapshot> newerVersion =
          executor.submit(
              () ->
                  registry.install(
                      fakeGitSource("newer-version", control, "block", "newer-ref", 2)));
      awaitMarker(control, "newer-version.started");

      DaemonSkillSourceSnapshot staleVersion =
          registry.install(fakeGitSource("stale-version", control, "instant", "older-ref", 1));
      assertEquals(1, staleVersion.sourceVersion());

      Files.writeString(control.resolve("newer-version.gate"), "released");
      DaemonSkillSourceSnapshot published = newerVersion.get(60, TimeUnit.SECONDS);

      assertEquals(2, published.sourceVersion());
      assertEquals(List.of("newer-version"), names(published));
      assertEquals(List.of(published), registry.snapshots());
    } finally {
      executor.shutdownNow();
      Files.writeString(control.resolve("newer-version.gate"), "released");
    }
  }

  /** sourceSetVersion 同样比启动 ticket 更权威：来源行未变化时，携带新来源集合的慢操作不能被晚启动的旧集合操作压过。 */
  @Test
  void newerSourceSetVersionWinsEvenWhenItsOperationStartedEarlier() throws Exception {
    Path control = Files.createDirectories(tempDir.resolve("fake-git-control"));
    DaemonSkillRegistry registry = fakeGitRegistry(control);
    UUID otherSourceId = UUID.fromString("10000000-0000-4000-8000-000000000002");
    DaemonSkillSourceConfig newerSourceSet =
        DaemonSkillSourceConfig.git(
            SOURCE_ID,
            1,
            2,
            "fake-git://block/newer-source-set",
            "newer-ref",
            null,
            null,
            Set.of(SOURCE_ID, otherSourceId));
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      Future<DaemonSkillSourceSnapshot> newer =
          executor.submit(() -> registry.install(newerSourceSet));
      awaitMarker(control, "newer-source-set.started");

      DaemonSkillSourceSnapshot stale =
          registry.install(fakeGitSource("stale-source-set", control, "instant", "older-ref", 1));
      assertEquals(List.of("stale-source-set"), names(stale));

      Files.writeString(control.resolve("newer-source-set.gate"), "released");
      DaemonSkillSourceSnapshot published = newer.get(60, TimeUnit.SECONDS);

      assertEquals(List.of("newer-source-set"), names(published));
      assertEquals(List.of(published), registry.snapshots());
    } finally {
      executor.shutdownNow();
      Files.writeString(control.resolve("newer-source-set.gate"), "released");
    }
  }

  /** 被中断的操作收敛为失败且不发布任何内容：registry 保持空目录，staging 被清理。 */
  @Test
  void interruptedInstallFailsWithoutPublishing() throws Exception {
    Path control = Files.createDirectories(tempDir.resolve("fake-git-control"));
    DaemonSkillRegistry registry = fakeGitRegistry(control);
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    AtomicReference<Thread> worker = new AtomicReference<>();
    try {
      Future<DaemonSkillSourceSnapshot> pending =
          executor.submit(
              () -> {
                worker.set(Thread.currentThread());
                return registry.install(fakeGitSource("blocked", control, "block", 1));
              });
      awaitMarker(control, "blocked.started");

      pending.cancel(true);
      // 等被中断的 worker 真正退出后再断言清理结果，避免与清理代码竞争。
      worker.get().join(TimeUnit.SECONDS.toMillis(30));

      assertThrows(CancellationException.class, () -> pending.get(30, TimeUnit.SECONDS));
      assertTrue(registry.snapshots().isEmpty());
      assertTrue(stagingIsEmpty(dataDir(control)));
      // 被中断的子进程不留下完成标记：它确实在等待期间被终止。
      assertFalse(Files.exists(control.resolve("blocked.released")));
    } finally {
      executor.shutdownNow();
      // 释放阻塞中的 fake git，避免其等待 gate 到超时。
      Files.writeString(control.resolve("blocked.gate"), "released");
    }
  }

  /**
   * 原生的 lightweight tag 与 annotated tag 都必须解析为 tag 指向的 commit。
   *
   * <p>轻量 tag 直接指向 commit，附注 tag 指向 tag 对象；两者都必须经 {@code ^{commit}} 归一为 commit id，否则 checkout 会以非
   * commit 形状失败。 两个 tag 指向同一个 commit，因此内容与 {@code sourceRevision} 完全一致，可同时验证解析结果而不是 tag 对象形状。
   */
  @Test
  void resolvesLightweightAndAnnotatedTagsToTheTaggedCommit() throws Exception {
    initRepository(remote, "alpha");
    run(remote, "git", "tag", "lightweight-v1");
    run(remote, "git", "tag", "-a", "annotated-v1", "-m", "annotated tag");
    String head = commit(remote);
    DaemonSkillRegistry registry = DaemonSkillRegistry.open(dataDir, tempDir);

    DaemonSkillSourceSnapshot lightweight = registry.install(git("lightweight-v1", null));

    assertEquals(head, lightweight.sourceRevision());
    assertEquals(List.of("alpha"), names(lightweight));

    DaemonSkillSourceSnapshot annotated =
        registry.install(git("annotated-v1", null, lightweight.sourceRevision(), 2, 2));

    assertEquals(head, annotated.sourceRevision());
    assertEquals(List.of("alpha"), names(annotated));
  }

  /** 完整引用形式必须被接受：{@code refs/tags/<name>} 与 {@code refs/heads/<name>} 都落到 commit。 */
  @Test
  void resolvesFullHeadAndTagRefs() throws Exception {
    initRepository(remote, "alpha");
    run(remote, "git", "branch", "stable");
    String first = commit(remote);
    run(remote, "git", "tag", "v1");
    addCommit(remote, "beta");
    DaemonSkillRegistry registry = DaemonSkillRegistry.open(dataDir, tempDir);

    // clone 后本地只有默认分支的本地引用，因此 refs/heads/stable 必须经远端跟踪引用解析。
    DaemonSkillSourceSnapshot head = registry.install(git("refs/heads/stable", null, null, 1, 1));

    assertEquals(first, head.sourceRevision());
    assertEquals(List.of("alpha"), names(head));

    DaemonSkillSourceSnapshot tag =
        registry.install(git("refs/tags/v1", null, head.sourceRevision(), 2, 2));

    assertEquals(first, tag.sourceRevision());
    assertEquals(List.of("alpha"), names(tag));
  }

  /** pin 一个普通可达 commit id 必须成功：无需任何 tag/分支指向它。 */
  @Test
  void resolvesReachableCommitIdDirectly() throws Exception {
    initRepository(remote, "alpha");
    String first = commit(remote);
    addCommit(remote, "beta");
    DaemonSkillRegistry registry = DaemonSkillRegistry.open(dataDir, tempDir);

    DaemonSkillSourceSnapshot pinned = registry.install(git(first, null));

    assertEquals(first, pinned.sourceRevision());
    assertEquals(List.of("alpha"), names(pinned));
  }

  /** 无法解析的 ref 只报告结构性原因：不回显 ref 文本。 */
  @Test
  void unresolvableRefDoesNotEchoTheRefText() throws Exception {
    initRepository(remote, "alpha");
    DaemonSkillRegistry registry = DaemonSkillRegistry.open(dataDir, tempDir);

    DaemonSkillException error =
        assertThrows(
            DaemonSkillException.class, () -> registry.install(git("secret-ref-name", null)));

    assertFalse(error.getMessage().contains("secret-ref-name"), error.getMessage());
    assertTrue(registry.snapshots().isEmpty());
  }

  /** 发布目录必须是纯内容快照：checkout 内不得残留任何 .git 元数据。 */
  @Test
  void publishedCheckoutHasNoGitMetadata() throws Exception {
    initRepository(remote, "alpha");
    DaemonSkillRegistry registry = DaemonSkillRegistry.open(dataDir, tempDir);
    DaemonSkillSourceSnapshot snapshot = registry.install(git(null, null));

    Path checkout =
        dataDir
            .resolve("skills/checkouts")
            .resolve(SOURCE_ID.toString())
            .resolve(snapshot.sourceRevision());

    assertTrue(Files.isDirectory(checkout));
    assertFalse(Files.exists(checkout.resolve(".git")));
    assertTrue(Files.isRegularFile(checkout.resolve("alpha/SKILL.md")));
  }

  /** 已物化的同一 (sourceId, revision) 再次发布必须安全收敛：复用既有目录且不破坏内容。 */
  @Test
  void republishingAnAlreadyMaterializedRevisionConverges() throws Exception {
    initRepository(remote, "alpha");
    DaemonSkillRegistry registry = DaemonSkillRegistry.open(dataDir, tempDir);
    DaemonSkillSourceSnapshot installed = registry.install(git(null, null));

    // 同一 revision 的并发/重复发布：固定已应用 revision 的 update 直接复用已物化目录。
    DaemonSkillSourceSnapshot reused = registry.update(git(null, null, installed.sourceRevision()));

    assertEquals(installed.sourceRevision(), reused.sourceRevision());
    assertEquals(List.of("alpha"), names(reused));
    assertTrue(
        Files.isRegularFile(
            dataDir
                .resolve("skills/checkouts")
                .resolve(SOURCE_ID.toString())
                .resolve(installed.sourceRevision())
                .resolve("alpha/SKILL.md")));
    assertTrue(stagingIsEmpty(dataDir));
  }

  /** 并发物化同一 revision 必须收敛：两个操作各自 staging，但同一 checkout 目录只能有一个发布者。 */
  @Test
  void concurrentMaterializationOfTheSameRevisionConverges() throws Exception {
    Path control = Files.createDirectories(tempDir.resolve("fake-git-control"));
    DaemonSkillRegistry registry = fakeGitRegistry(control);
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      // 两个操作解析出同一 commit（fake git 恒定返回同一 id），因此发布目标目录完全一致。
      Future<DaemonSkillSourceSnapshot> first =
          executor.submit(() -> registry.install(fakeGitSource("first", control, "block", 1)));
      Future<DaemonSkillSourceSnapshot> second =
          executor.submit(() -> registry.install(fakeGitSource("second", control, "block", 1)));
      awaitMarker(control, "first.started");
      awaitMarker(control, "second.started");
      Files.writeString(control.resolve("first.gate"), "released");
      Files.writeString(control.resolve("second.gate"), "released");

      // 两个操作都成功，并指向同一 revision：并发发布收敛为复用同一个不可变 checkout。
      assertEquals(FakeGitCommand.COMMIT_ID, first.get(60, TimeUnit.SECONDS).sourceRevision());
      assertEquals(FakeGitCommand.COMMIT_ID, second.get(60, TimeUnit.SECONDS).sourceRevision());
      Path checkout =
          dataDir(control)
              .resolve("skills/checkouts")
              .resolve(SOURCE_ID.toString())
              .resolve(FakeGitCommand.COMMIT_ID);
      // 收敛后的目录是完整内容快照：恰好一个发布者写入内容，且不含 .git 元数据。
      List<String> published =
          List.of("first", "second").stream()
              .filter(name -> Files.isRegularFile(checkout.resolve(name).resolve("SKILL.md")))
              .toList();
      assertEquals(1, published.size(), "exactly one materialization wins: " + published);
      assertFalse(Files.exists(checkout.resolve(".git")));
      // 失败/落败方的 staging 必须被清理，不留半成品目录。
      assertTrue(stagingIsEmpty(dataDir(control)));
    } finally {
      executor.shutdownNow();
      Files.writeString(control.resolve("first.gate"), "released");
      Files.writeString(control.resolve("second.gate"), "released");
    }
  }

  /**
   * 注入 fake git 的 registry：命令前缀是 argv 形式的 java + 测试类路径 + 公开 main，生产代码不感知测试。
   *
   * <p>fake git 通过系统属性拿到控制目录，因此 argv 里只有 git 自身的参数，取消语义与真实 git 完全同构。
   */
  private static DaemonSkillRegistry fakeGitRegistry(Path control) {
    String java = System.getProperty("java.home") + "/bin/java";
    return DaemonSkillRegistry.open(
        dataDir(control),
        control,
        List.of(
            java,
            "-D" + FakeGitCommand.CONTROL_DIRECTORY_PROPERTY + "=" + control,
            "-cp",
            System.getProperty("java.class.path"),
            FakeGitCommand.class.getName()));
  }

  /** fake git 场景的数据目录：与真实 git 场景隔离，避免 staging/checkout 互相干扰。 */
  private static Path dataDir(Path control) {
    return control.resolve("data");
  }

  /**
   * 指向 fake git 的来源配置：URL 只编码模式与 id。
   *
   * <p>{@code block} 模式让 fake git 等待自己的 gate 文件，从而允许测试在不使用固定 sleep 的前提下构造"该操作正在等待子进程"的状态。
   * 同一来源的并发操作使用不同的行版本，符合"版本随每次配置变更前进"的契约。
   */
  private static DaemonSkillSourceConfig fakeGitSource(
      String id, Path control, String mode, long sourceVersion) {
    return fakeGitSource(id, control, mode, null, sourceVersion);
  }

  private static DaemonSkillSourceConfig fakeGitSource(
      String id, Path control, String mode, String ref, long sourceVersion) {
    return DaemonSkillSourceConfig.git(
        SOURCE_ID,
        sourceVersion,
        1,
        "fake-git://" + mode + "/" + id,
        ref,
        null,
        null,
        Set.of(SOURCE_ID));
  }

  /** 等待 fake git 的进度标记：只以文件系统事实同步，不依赖固定时长。 */
  private static void awaitMarker(Path control, String marker) throws Exception {
    Path path = control.resolve(marker);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (!Files.exists(path)) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("fake git did not reach marker: " + marker);
      }
      Thread.sleep(20);
    }
  }

  /** 每次操作显式给出 ref、scanPath 与 currentlyAppliedRevision，避免把 revision 误当 scanPath。 */
  private DaemonSkillSourceConfig git(
      String ref, String scanPath, String currentlyAppliedRevision) {
    return git(ref, scanPath, currentlyAppliedRevision, 1, 1);
  }

  /** 显式给出行版本与全局来源集合版本，用于构造陈旧集合场景。 */
  private DaemonSkillSourceConfig git(
      String ref,
      String scanPath,
      String currentlyAppliedRevision,
      long sourceVersion,
      long sourceSetVersion) {
    return DaemonSkillSourceConfig.git(
        SOURCE_ID,
        sourceVersion,
        sourceSetVersion,
        remote.toUri().toString(),
        ref,
        scanPath,
        currentlyAppliedRevision,
        Set.of(SOURCE_ID));
  }

  /** 首次安装/无已应用 revision 的场景。 */
  private DaemonSkillSourceConfig git(String ref, String scanPath) {
    return git(ref, scanPath, null);
  }

  private static List<String> names(DaemonSkillSourceSnapshot snapshot) {
    return snapshot.skills().stream().map(skill -> skill.name()).toList();
  }

  private static boolean stagingIsEmpty(Path dataDir) {
    Path staging = dataDir.resolve("skills/staging");
    if (!Files.isDirectory(staging)) {
      return true;
    }
    try (var entries = Files.list(staging)) {
      return entries.findAny().isEmpty();
    } catch (IOException error) {
      return false;
    }
  }

  /** 初始化一个本地仓库并提交首个 skill 子目录。 */
  private static void initRepository(Path repository, String skillName) throws Exception {
    Files.createDirectories(repository);
    run(repository, "git", "init", "--quiet", "-b", "main");
    run(repository, "git", "config", "user.email", "daemon-test@example.invalid");
    run(repository, "git", "config", "user.name", "daemon test");
    writeSkill(repository.resolve(skillName), skillName);
    addAll(repository, ".");
  }

  /** 追加一个 skill 子目录并提交。 */
  private static void addCommit(Path repository, String skillName) throws Exception {
    writeSkill(repository.resolve(skillName), skillName);
    addAll(repository, skillName);
  }

  private static void addAll(Path repository, String path) throws Exception {
    run(repository, "git", "add", "--", path);
    run(repository, "git", "commit", "--quiet", "-m", "update skills");
  }

  private static void writeSkill(Path directory, String name) throws IOException {
    Files.createDirectories(directory);
    Files.writeString(
        directory.resolve("SKILL.md"),
        "---\nname: " + name + "\ndescription: " + name + " description\n---\n# " + name + "\n");
  }

  private static String commit(Path repository) throws Exception {
    return run(repository, "git", "rev-parse", "HEAD").trim();
  }

  private static boolean hostGitAvailable() {
    try {
      Process process = new ProcessBuilder("git", "--version").start();
      return process.waitFor() == 0;
    } catch (IOException error) {
      return false;
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static String run(Path workingDirectory, String... argv) throws Exception {
    ProcessBuilder builder = new ProcessBuilder(argv).directory(workingDirectory.toFile());
    builder.redirectErrorStream(true);
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes());
    int exitCode = process.waitFor();
    assertEquals(0, exitCode, "command failed: " + String.join(" ", argv) + " -> " + output.trim());
    return output;
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (var walk = Files.walk(root)) {
      for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }
}
