package fun.fengwk.kkstudio.platform.catalog.skill.git;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PacketLineOut;
import org.eclipse.jgit.transport.RefAdvertiser.PacketLineOutRefAdvertiser;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UploadPack;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

@Timeout(10)
class JGitSkillCacheNetworkTest {

  /** 测试意图：允许拉取可达提交的 Smart HTTP 服务在分支前进后仍支持按旧 SHA 浅拉取，无需检出。 */
  @Test
  void fetchesPreviouslyObservedCommitAtDepthOneWithoutCheckout(@TempDir Path directory)
      throws Exception {
    Path remote = directory.resolve("remote-shallow");
    try (Git source = Git.init().setDirectory(remote.toFile()).setInitialBranch("main").call()) {
      Path file = remote.resolve("dev/SKILL.md");
      Files.createDirectories(file.getParent());
      Files.writeString(file, "old");
      source.add().addFilepattern(".").call();
      RevCommit parent = source.commit().setMessage("parent").setSign(false).call();
      Files.writeString(file, "observed");
      source.add().addFilepattern(".").call();
      RevCommit observed = source.commit().setMessage("observed").setSign(false).call();
      Files.writeString(file, "new head");
      source.add().addFilepattern(".").call();
      RevCommit head = source.commit().setMessage("head").setSign(false).call();

      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            try (UploadPack pack = new UploadPack(source.getRepository());
                ByteArrayOutputStream response = new ByteArrayOutputStream()) {
              pack.setBiDirectionalPipe(false);
              pack.setRequestPolicy(UploadPack.RequestPolicy.REACHABLE_COMMIT);
              String contentType;
              if (exchange.getRequestURI().getPath().equals("/info/refs")) {
                PacketLineOut lines = new PacketLineOut(response);
                pack.sendAdvertisedRefs(new PacketLineOutRefAdvertiser(lines), "git-upload-pack");
                contentType = "application/x-git-upload-pack-advertisement";
              } else if (exchange.getRequestURI().getPath().equals("/git-upload-pack")) {
                byte[] request = exchange.getRequestBody().readAllBytes();
                if ("gzip".equals(exchange.getRequestHeaders().getFirst("Content-Encoding"))) {
                  try (GZIPInputStream input =
                      new GZIPInputStream(new ByteArrayInputStream(request))) {
                    request = input.readAllBytes();
                  }
                }
                pack.upload(new ByteArrayInputStream(request), response, null);
                contentType = "application/x-git-upload-pack-result";
              } else {
                exchange.sendResponseHeaders(404, -1);
                return;
              }
              byte[] body = response.toByteArray();
              exchange.getResponseHeaders().set("Content-Type", contentType);
              exchange.sendResponseHeaders(200, body.length);
              exchange.getResponseBody().write(body);
            } finally {
              exchange.close();
            }
          });
      server.start();
      try {
        String remoteUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        try (GitHttpConnectionFactory network = new GitHttpConnectionFactory(1000, 1000)) {
          assertEquals(
              head.name(),
              Git.lsRemoteRepository()
                  .setRemote(remoteUrl)
                  .setTransportConfigCallback(network.callback())
                  .callAsMap()
                  .get("refs/heads/main")
                  .getObjectId()
                  .name());
          Path local = directory.resolve("fetch");
          try (Git fetched = Git.init().setDirectory(local.toFile()).setBare(true).call()) {
            fetched
                .fetch()
                .setRemote(remoteUrl)
                .setTransportConfigCallback(network.callback())
                .setRefSpecs(new RefSpec(observed.name() + ":refs/heads/observed"))
                .setDepth(1)
                .call();
            assertTrue(
                fetched.getRepository().getObjectDatabase().getShallowCommits().contains(observed));
            assertFalse(fetched.getRepository().getObjectDatabase().has(parent));
            assertFalse(fetched.getRepository().getObjectDatabase().has(head));
            try (RevWalk walk = new RevWalk(fetched.getRepository());
                TreeWalk tree =
                    TreeWalk.forPath(
                        fetched.getRepository(),
                        "dev/SKILL.md",
                        walk.parseCommit(ObjectId.fromString(observed.name())).getTree())) {
              assertArrayEquals(
                  "observed".getBytes(StandardCharsets.UTF_8),
                  fetched.getRepository().open(tree.getObjectId(0), Constants.OBJ_BLOB).getBytes());
            }
          }
          assertFalse(Files.exists(local.resolve("dev")));
        }
      } finally {
        server.stop(0);
      }
    }
  }

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
      assertTrue(resolve.getMessage().contains("GIT_READ_TIMEOUT"), resolve::getMessage);
      assertEquals(1, server.requests.get());
      SkillGitException fetch =
          assertThrows(
              SkillGitException.class,
              () -> cache.ensureCommit("pkg", server.url(), "a".repeat(40), null));
      assertTrue(fetch.getMessage().contains("GIT_READ_TIMEOUT"), fetch::getMessage);
      assertEquals(2, server.requests.get());
      assertFalse(Files.exists(directory.resolve("cache/pkg")));
    }
  }

  /** 真实 401 与 403 都收敛为 CODE_AUTHENTICATION_FAILED，无静默兜底且不泄露令牌。 */
  @ParameterizedTest
  @ValueSource(ints = {401, 403})
  void authenticationFailureClassifiedCorrectly(int status, @TempDir Path directory)
      throws Exception {
    HttpServer authServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    authServer.createContext(
        "/",
        exchange -> {
          if (status == 401) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"Git\"");
          }
          exchange.sendResponseHeaders(status, -1);
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
