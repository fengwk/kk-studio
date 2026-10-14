package fun.fengwk.kkstudio.platform.catalog.skill.git;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Timeout(10)
class JGitSkillCacheNetworkTest {

  /** 意图：所有 lsRemote 与 clone 真正经过分离的 HTTP factory，持续传输超过 read budget 不被总时限截断。 */
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
        assertEquals(commit, cache.resolveBranchHead(server.url(), "main", null));
        server.smart = false;
        cache.ensureCommit("pkg", server.url(), commit, null);
        assertArrayEquals("test".getBytes(), cache.readFile("pkg", commit, "README.md"));
        assertTrue(System.nanoTime() - start > TimeUnit.MILLISECONDS.toNanos(200));
      }
    }
  }

  /** 意图：真实 Git HTTP 读取停滞停止且只请求一次，不能吞超时或产生残留物化内容。 */
  @Test
  void idleTimeoutIsStableAndDoesNotRetry(@TempDir Path directory) throws Exception {
    try (LocalGitHttpServer server = new LocalGitHttpServer(directory, "a".repeat(40))) {
      server.stall = true;
      JGitSkillCache cache = new JGitSkillCache(directory.resolve("cache"), 1000, 100);
      SkillGitException resolve =
          assertThrows(
              SkillGitException.class, () -> cache.resolveBranchHead(server.url(), "main", null));
      assertTrue(resolve.getMessage().contains("GIT_READ_TIMEOUT"));
      assertEquals(1, server.requests.get());
      SkillGitException fetch =
          assertThrows(
              SkillGitException.class,
              () -> cache.ensureCommit("pkg", server.url(), "a".repeat(40), null));
      assertTrue(fetch.getMessage().contains("GIT_READ_TIMEOUT"));
      assertEquals(2, server.requests.get());
      assertFalse(Files.exists(directory.resolve("cache/pkg")));
    }
  }

  /** 意图：仓库鉴权失败明确收敛为 CODE_AUTHENTICATION_FAILED，无静默兜底且不泄露令牌。 */
  @Test
  void authenticationFailureClassifiedCorrectly(@TempDir Path directory) throws Exception {
    HttpServer authServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    authServer.createContext(
        "/",
        exchange -> {
          exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"Git\"");
          exchange.sendResponseHeaders(401, -1);
          exchange.close();
        });
    authServer.start();
    try {
      String url = "http://127.0.0.1:" + authServer.getAddress().getPort() + "/repo.git";
      JGitSkillCache cache = new JGitSkillCache(directory.resolve("cache"), 1000, 1000);
      SkillGitException error =
          assertThrows(
              SkillGitException.class,
              () -> cache.ensureCommit("pkg", url, "a".repeat(40), "secret-token"));
      assertEquals(SkillGitException.CODE_AUTHENTICATION_FAILED, error.code());
      assertTrue(error.authenticationFailed());
      assertFalse(error.getMessage().contains("secret-token"));
      assertFalse(Files.exists(directory.resolve("cache/pkg")));
    } finally {
      authServer.stop(0);
    }
  }
}
