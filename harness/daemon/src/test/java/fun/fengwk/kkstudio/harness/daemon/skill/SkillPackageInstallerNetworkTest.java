package fun.fengwk.kkstudio.harness.daemon.skill;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Timeout(10)
class SkillPackageInstallerNetworkTest {

  /** Git 取消观察使用测试自有的 executor；用完即关闭，模拟 runtime 生命周期。 */
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  @AfterEach
  void tearDown() {
    executor.shutdownNow();
  }

  /** 意图：daemon fetch 持续 socket 数据超过短测试 budget 仍可完成原子安装。 */
  @Test
  void fetchWithContinuingDataHasNoWholeOperationDeadline(@TempDir Path directory)
      throws Exception {
    Path remote = directory.resolve("remote");
    try (Git git = Git.init().setDirectory(remote.toFile()).setInitialBranch("main").call()) {
      Files.writeString(remote.resolve("README.md"), "test");
      git.add().addFilepattern(".").call();
      String commit =
          git.commit()
              .setMessage("initial")
              .setSign(false)
              .setAuthor("test", "test@example.invalid")
              .call()
              .name();
      try (LocalGitHttpServer server = new LocalGitHttpServer(remote.resolve(".git"), commit)) {
        server.slow = true;
        SkillPackageInstaller installer = installer(directory, 200);
        long start = System.nanoTime();
        assertEquals(
            commit, installer.install("pkg", server.url(), "main", commit).installedCommit());
        assertEquals("test", Files.readString(directory.resolve("skills/pkg/README.md")));
        assertTrue(System.nanoTime() - start > TimeUnit.MILLISECONDS.toNanos(200));
      }
    }
  }

  /** 意图：Git socket 停滞报告 READ_TIMEOUT，无二次分支 fetch、无 staging/backup 副作用。 */
  @Test
  void idleFetchStopsWithoutRetryOrInstallArtifacts(@TempDir Path directory) throws Exception {
    try (LocalGitHttpServer server = new LocalGitHttpServer(directory, "a".repeat(40))) {
      server.stall = true;
      SkillPackageInstaller installer = installer(directory, 100);
      SkillSyncException failure =
          assertThrows(
              SkillSyncException.class,
              () -> installer.install("pkg", server.url(), "main", "a".repeat(40)));
      assertEquals("GIT_READ_TIMEOUT", failure.code());
      assertEquals(1, server.requests.get());
      assertFalse(Files.exists(directory.resolve("skills/pkg")));
      try (var staging = Files.list(installer.stagingRoot());
          var backup = Files.list(installer.backupRoot())) {
        assertEquals(0, staging.count());
        assertEquals(0, backup.count());
      }
    }
  }

  /** 意图：真实 HTTP 读取期间取消会回收安装任务，而不是等 180s idle；不允许安装部分目录。 */
  @Test
  void interruptionReclaimsActualSocketFetch(@TempDir Path directory) throws Exception {
    try (LocalGitHttpServer server = new LocalGitHttpServer(directory, "a".repeat(40))) {
      server.stall = true;
      SkillPackageInstaller installer = installer(directory, 30_000);
      CompletableFuture<Void> stopped = new CompletableFuture<>();
      Thread thread =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      assertThrows(
                          SkillSyncException.class,
                          () -> installer.install("pkg", server.url(), "main", "a".repeat(40)));
                      stopped.complete(null);
                    } catch (Throwable error) {
                      stopped.completeExceptionally(error);
                    }
                  });
      assertTrue(server.entered.await(2, TimeUnit.SECONDS));
      thread.interrupt();
      stopped.get(2, TimeUnit.SECONDS);
      assertFalse(Files.exists(directory.resolve("skills/pkg")));
    }
  }

  private SkillPackageInstaller installer(Path root, int readMillis) {
    return new SkillPackageInstaller(
        root.resolve("skills"),
        root.resolve("staging"),
        root.resolve("backup"),
        1000,
        readMillis,
        executor);
  }
}
