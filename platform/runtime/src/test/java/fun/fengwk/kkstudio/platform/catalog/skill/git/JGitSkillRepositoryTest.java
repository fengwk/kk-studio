package fun.fengwk.kkstudio.platform.catalog.skill.git;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.internal.WorkQueue;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.platform.catalog.skill.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Timeout(30)
class JGitSkillRepositoryTest {

  private static final String SKILL_MD =
      """
      ---
      name: dev
      description: development guidelines
      ---
      # dev content
      """;

  @TempDir Path source;

  /**
   * 意图：发布归档后不留下仍在操作临时仓库的后台任务。
   *
   * <p>fetch 结束后 JGit 默认在共享 WorkQueue 上后台执行 auto GC，它会在 finally 删除临时仓库时并发增删 {@code
   * gc.log.lock}，让递归删除抛错并把成功的发布变成失败。这里先占住 WorkQueue 唯一的工作线程，任何后台 GC 都只能排队，
   * 因此发布返回时没有排队的一次性任务即证明没有遗留的后台任务；RepositoryCache 的周期清理任务与临时仓库无关，不计入。
   */
  @Test
  void buildArchiveLeavesNoBackgroundGcBehind() throws Exception {
    String commit = commitSkill();
    ScheduledThreadPoolExecutor workQueue = WorkQueue.getExecutor();
    CountDownLatch occupied = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Future<?> blocker =
        workQueue.submit(
            () -> {
              occupied.countDown();
              release.await();
              return null;
            });
    try {
      assertTrue(occupied.await(10, TimeUnit.SECONDS), "WorkQueue worker must be occupied");

      SkillArchiveBuilder.Result archive =
          new JGitSkillRepository().buildArchive(source.toUri().toString(), commit, null);

      assertEquals(
          "dev", archive.skills().stream().map(SkillManifestEntry::name).findFirst().orElseThrow());
      long pendingOneShotTasks =
          workQueue.getQueue().stream()
              .filter(
                  task ->
                      !(task instanceof RunnableScheduledFuture<?> scheduled)
                          || !scheduled.isPeriodic())
              .count();
      assertEquals(
          0, pendingOneShotTasks, "no background task may still touch the temporary repository");
    } finally {
      release.countDown();
      blocker.get(10, TimeUnit.SECONDS);
    }
  }

  private String commitSkill() throws Exception {
    Path skill = source.resolve("dev/SKILL.md");
    Files.createDirectories(skill.getParent());
    Files.writeString(skill, SKILL_MD, StandardCharsets.UTF_8);
    try (Git git = Git.init().setDirectory(source.toFile()).setInitialBranch("main").call()) {
      git.add().addFilepattern(".").call();
      RevCommit commit = git.commit().setMessage("skill").setSign(false).call();
      return commit.getId().getName();
    }
  }
}
