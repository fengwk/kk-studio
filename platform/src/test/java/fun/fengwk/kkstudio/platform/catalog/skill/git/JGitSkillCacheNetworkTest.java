package fun.fengwk.kkstudio.platform.catalog.skill.git;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Timeout(10)
class JGitSkillCacheNetworkTest {

  /** 意图：所有 lsRemote/fetch 真正经过分离的 HTTP factory，持续传输超过 read budget 不被总时限截断。 */
  @Test
  void lsRemoteAndFetchAllowContinuingNetworkProgress(@TempDir Path directory) throws Exception {
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
        server.smart = true;
        JGitSkillCache cache = new JGitSkillCache(directory.resolve("cache"), 1000, 200);
        long start = System.nanoTime();
        assertEquals(commit, cache.resolveBranchHead(server.url(), "main"));
        server.smart = false;
        cache.ensureCommit("pkg", server.url(), commit);
        assertArrayEquals("test".getBytes(), cache.readFile("pkg", commit, "README.md"));
        assertTrue(System.nanoTime() - start > TimeUnit.MILLISECONDS.toNanos(200));
      }
    }
  }

  /** 意图：真实 Git HTTP 读取停滞停止且只请求一次，不能吞超时再回退分支。 */
  @Test
  void idleTimeoutIsStableAndDoesNotRetry(@TempDir Path directory) throws Exception {
    try (LocalGitHttpServer server = new LocalGitHttpServer(directory, "a".repeat(40))) {
      server.stall = true;
      JGitSkillCache cache = new JGitSkillCache(directory.resolve("cache"), 1000, 100);
      SkillGitException resolve =
          assertThrows(
              SkillGitException.class, () -> cache.resolveBranchHead(server.url(), "main"));
      assertTrue(resolve.getMessage().contains("GIT_READ_TIMEOUT"));
      assertEquals(1, server.requests.get());
      SkillGitException fetch =
          assertThrows(
              SkillGitException.class,
              () -> cache.ensureCommit("pkg", server.url(), "a".repeat(40)));
      assertTrue(fetch.getMessage().contains("GIT_READ_TIMEOUT"));
      assertEquals(2, server.requests.get());
    }
  }
}
