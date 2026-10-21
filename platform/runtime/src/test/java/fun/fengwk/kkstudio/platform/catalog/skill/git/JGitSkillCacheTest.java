package fun.fengwk.kkstudio.platform.catalog.skill.git;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import fun.fengwk.kkstudio.platform.catalog.skill.SkillManifestEntry;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class JGitSkillCacheTest {

  /** 合成秘密正文：注入的失败路径携带它，用于证明安全日志绝不回显路径或原始异常内容。 */
  private static final String SYNTHETIC_SECRET = "synthetic-secret-value";

  private static final String DEV_SKILL_MD =
      """
      ---
      name: dev
      description: 开发者技能包含开发规范
      ---
      # dev content
      """;

  private static final String CHATGPT_SKILL_MD =
      """
      ---
      name: chatgpt-agent
      description: ChatGPT 智能助手技能
      ---
      # chatgpt agent content
      """;

  private RevCommit createRepo(File repoDir, Map<String, String> files) throws Exception {
    try (Git git = Git.init().setDirectory(repoDir).setInitialBranch("main").call()) {
      for (Map.Entry<String, String> entry : files.entrySet()) {
        File file = new File(repoDir, entry.getKey());
        file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), entry.getValue(), StandardCharsets.UTF_8);
      }
      git.add().addFilepattern(".").call();
      return git.commit().setMessage("Initial commit").setSign(false).call();
    }
  }

  @Test
  public void resolveBranchHead_success(@TempDir Path tempDir) throws Exception {
    // 测试意图：验证对于存在的有效分支，能够正确解析得到远端 HEAD commit ID 且格式合法。
    File remoteDir = tempDir.resolve("remote-repo").toFile();
    RevCommit commit = createRepo(remoteDir, Map.of("README.md", "# test"));
    String repoUrl = remoteDir.toURI().toString();

    Path cacheRoot = tempDir.resolve("cache");
    JGitSkillCache cache = new JGitSkillCache(cacheRoot);

    String resolvedCommit = cache.resolveBranchHead(repoUrl, "main", null);
    assertEquals(commit.getId().getName(), resolvedCommit);
  }

  @Test
  public void resolveBranchHead_branchNotFound_throwsSkillGitException(@TempDir Path tempDir)
      throws Exception {
    // 测试意图：验证解析不存在的分支名称时抛出 SkillGitException。
    File remoteDir = tempDir.resolve("remote-repo").toFile();
    createRepo(remoteDir, Map.of("README.md", "# test"));
    String repoUrl = remoteDir.toURI().toString();

    Path cacheRoot = tempDir.resolve("cache");
    JGitSkillCache cache = new JGitSkillCache(cacheRoot);

    assertThrows(
        SkillGitException.class,
        () -> cache.resolveBranchHead(repoUrl, "non-existent-branch", null));
  }

  @Test
  public void unsupportedSchemeRejectedAtEntry(@TempDir Path tempDir) {
    // 测试意图：直接入口对未受网络保护覆盖的 scheme fail-closed；file 通过门禁交由 JGit 处理。
    JGitSkillCache cache = new JGitSkillCache(tempDir.resolve("cache"));

    SkillGitException gitHead =
        assertThrows(
            SkillGitException.class,
            () -> cache.resolveBranchHead("git://example.com/repo.git", "main", null));
    assertEquals(
        "UNSUPPORTED_REPOSITORY_SCHEME: unsupported repository scheme: git", gitHead.getMessage());

    SkillGitException sshEnsure =
        assertThrows(
            SkillGitException.class,
            () -> cache.ensureCommit("pkg", "ssh://example.com/repo.git", "0".repeat(40), null));
    assertEquals(
        "UNSUPPORTED_REPOSITORY_SCHEME: unsupported repository scheme: ssh",
        sshEnsure.getMessage());

    SkillGitException fileHead =
        assertThrows(
            SkillGitException.class,
            () -> cache.resolveBranchHead(tempDir.toUri().toString(), "main", null));
    assertFalse(fileHead.getMessage().contains("UNSUPPORTED_REPOSITORY_SCHEME"));
  }

  @Test
  public void directEntriesRejectCredentialsBeforeNetwork(@TempDir Path tempDir) {
    // 测试意图：非 Web 调用同样在网络前拒绝凭据，错误及 cause 不泄漏原始 URL。
    JGitSkillCache cache = new JGitSkillCache(tempDir.resolve("cache"));
    String repositoryUrl = "https://user:private-value@example.invalid/repo.git";
    SkillGitException head =
        assertThrows(
            SkillGitException.class, () -> cache.resolveBranchHead(repositoryUrl, "main", null));
    SkillGitException fetch =
        assertThrows(
            SkillGitException.class,
            () -> cache.ensureCommit("pkg", repositoryUrl, "0".repeat(40), null));
    assertEquals("repositoryUrl must not contain userinfo", head.getMessage());
    assertEquals(head.getMessage(), fetch.getMessage());
    assertNull(head.getCause());
    assertNull(fetch.getCause());
    assertFalse(Files.exists(tempDir.resolve("cache/pkg")));
    SkillGitException malformed =
        assertThrows(
            SkillGitException.class,
            () ->
                cache.resolveBranchHead(
                    "https://user:private value@example.invalid", "main", null));
    assertEquals("repositoryUrl must be a valid URL without userinfo", malformed.getMessage());
    assertNull(malformed.getCause());
  }

  @Test
  public void validatePackageNameAndCommitFormat(@TempDir Path tempDir) {
    // 测试意图：验证非法包名与 commit 格式均在入口处拒绝并抛出 SkillGitException。
    JGitSkillCache cache = new JGitSkillCache(tempDir.resolve("cache"));
    String validCommit = "a".repeat(40);

    assertThrows(
        SkillGitException.class,
        () -> cache.ensureCommit("test-pkg", "file:///repo", "short", null));
    assertThrows(
        SkillGitException.class,
        () -> cache.ensureCommit("test-pkg", "file:///repo", "g".repeat(40), null));
    assertThrows(
        SkillGitException.class, () -> cache.ensureCommit("test-pkg", "file:///repo", null, null));
    assertThrows(SkillGitException.class, () -> cache.scanManifest("test-pkg", "invalid"));
    assertThrows(SkillGitException.class, () -> cache.readFile("test-pkg", "invalid", "README.md"));

    assertThrows(
        SkillGitException.class,
        () -> cache.ensureCommit("Invalid_Pkg", "file:///repo", validCommit, null));
    assertThrows(
        SkillGitException.class,
        () -> cache.ensureCommit("-pkg", "file:///repo", validCommit, null));
    assertThrows(SkillGitException.class, () -> cache.scanManifest("Invalid_Pkg", validCommit));
    assertThrows(
        SkillGitException.class, () -> cache.readFile("Invalid_Pkg", validCommit, "README.md"));
  }

  @Test
  public void ensureCommit_publishesExactCommit_scanManifestAndReadFileSuccess(
      @TempDir Path tempDir) throws Exception {
    // 测试意图：验证首次 ensureCommit 能够拉取 commit 并物化发布目录，scanManifest 正确扫描并忽略无关文件，readFile 读取字节。
    File remoteDir = tempDir.resolve("remote-repo").toFile();
    RevCommit commit =
        createRepo(
            remoteDir,
            Map.of(
                "dev/SKILL.md",
                DEV_SKILL_MD,
                "chatgpt-agent/SKILL.md",
                CHATGPT_SKILL_MD,
                "docs/intro.md",
                "# Documentation",
                "README.md",
                "# Root readme"));
    String repoUrl = remoteDir.toURI().toString();
    String commitId = commit.getId().getName();

    Path cacheRoot = tempDir.resolve("cache");
    JGitSkillCache cache = new JGitSkillCache(cacheRoot);

    cache.ensureCommit("test-pkg", repoUrl, commitId, null);

    // 验证 cache 根目录下是物化发布目录 <pkg> 而非 bare <pkg>.git，且 .kkstudio-commit 记录 commit
    Path publishedDir = cacheRoot.resolve("test-pkg");
    assertTrue(Files.isDirectory(publishedDir));
    assertFalse(Files.exists(cacheRoot.resolve("test-pkg.git")));
    Path commitMarker = publishedDir.resolve(".kkstudio-commit");
    assertTrue(Files.isRegularFile(commitMarker));
    assertEquals(commitId, Files.readString(commitMarker, StandardCharsets.UTF_8).trim());

    List<SkillManifestEntry> manifest = cache.scanManifest("test-pkg", commitId);
    assertNotNull(manifest);
    assertEquals(2, manifest.size());

    assertEquals("chatgpt-agent", manifest.get(0).name());
    assertEquals("ChatGPT 智能助手技能", manifest.get(0).description());

    assertEquals("dev", manifest.get(1).name());
    assertEquals("开发者技能包含开发规范", manifest.get(1).description());

    byte[] devBytes = cache.readFile("test-pkg", commitId, "dev/SKILL.md");
    assertArrayEquals(DEV_SKILL_MD.getBytes(StandardCharsets.UTF_8), devBytes);

    byte[] readmeBytes = cache.readFile("test-pkg", commitId, "README.md");
    assertArrayEquals("# Root readme".getBytes(StandardCharsets.UTF_8), readmeBytes);
  }

  @Test
  public void ensureCommit_idempotentNoOp_whenCommitAlreadyCached(@TempDir Path tempDir)
      throws Exception {
    // 测试意图：验证当 commit 已发布时再次调用 ensureCommit 为 no-op，无 clone/网络操作，marker 不变且无残留 clone 目录。
    File remoteDir = tempDir.resolve("remote-repo").toFile();
    RevCommit commit = createRepo(remoteDir, Map.of("README.md", "# test"));
    String repoUrl = remoteDir.toURI().toString();
    String commitId = commit.getId().getName();

    Path cacheRoot = tempDir.resolve("cache");
    JGitSkillCache cache = new JGitSkillCache(cacheRoot);

    cache.ensureCommit("test-pkg", repoUrl, commitId, null);

    Path marker = cacheRoot.resolve("test-pkg/.kkstudio-commit");
    assertEquals(commitId, Files.readString(marker, StandardCharsets.UTF_8).trim());

    // 第二次调用传入无效的 URL，若仍有 clone 则会失败，直接返回则表示幂等正确
    cache.ensureCommit("test-pkg", "file:///non/existent/path/repo.git", commitId, null);

    assertEquals(commitId, Files.readString(marker, StandardCharsets.UTF_8).trim());
    Path workDir = cacheRoot.resolve(".work");
    if (Files.exists(workDir)) {
      try (var stream = Files.list(workDir)) {
        assertEquals(0, stream.count());
      }
    }
  }

  @Test
  public void ensureCommit_republishesOnDifferentCommit_oldContentGone(@TempDir Path tempDir)
      throws Exception {
    // 测试意图：验证发布不同 commit 时原子替换物化目录，旧内容与旧 commit 读取均失效。
    File remoteDir = tempDir.resolve("remote-repo").toFile();
    RevCommit commit1 =
        createRepo(remoteDir, Map.of("dev/SKILL.md", DEV_SKILL_MD, "old-file.txt", "old content"));
    String repoUrl = remoteDir.toURI().toString();
    String commit1Id = commit1.getId().getName();

    Path cacheRoot = tempDir.resolve("cache");
    JGitSkillCache cache = new JGitSkillCache(cacheRoot);

    cache.ensureCommit("test-pkg", repoUrl, commit1Id, null);
    assertEquals("开发者技能包含开发规范", cache.scanManifest("test-pkg", commit1Id).get(0).description());
    assertArrayEquals(
        "old content".getBytes(StandardCharsets.UTF_8),
        cache.readFile("test-pkg", commit1Id, "old-file.txt"));

    RevCommit commit2;
    try (Git git = Git.open(remoteDir)) {
      Files.delete(remoteDir.toPath().resolve("old-file.txt"));
      git.rm().addFilepattern("old-file.txt").call();
      File newSkill = new File(remoteDir, "chatgpt-agent/SKILL.md");
      newSkill.getParentFile().mkdirs();
      Files.writeString(newSkill.toPath(), CHATGPT_SKILL_MD, StandardCharsets.UTF_8);
      git.add().addFilepattern(".").call();
      commit2 = git.commit().setMessage("Second commit").setSign(false).call();
    }
    String commit2Id = commit2.getId().getName();

    cache.ensureCommit("test-pkg", repoUrl, commit2Id, null);

    assertThrows(SkillGitException.class, () -> cache.scanManifest("test-pkg", commit1Id));
    assertThrows(
        SkillGitException.class, () -> cache.readFile("test-pkg", commit1Id, "old-file.txt"));

    List<SkillManifestEntry> manifest2 = cache.scanManifest("test-pkg", commit2Id);
    assertEquals(2, manifest2.size());
    assertThrows(
        SkillGitException.class, () -> cache.readFile("test-pkg", commit2Id, "old-file.txt"));
    assertEquals(
        commit2Id,
        Files.readString(cacheRoot.resolve("test-pkg/.kkstudio-commit"), StandardCharsets.UTF_8)
            .trim());
  }

  /** 测试意图：重新发布时旧目录的递归清理真实失败必须去敏可观察（固定阶段与异常类名，无路径/正文），且单个条目失败不得跳过同层其余可删除条目。 */
  @Test
  public void ensureCommit_failedStaleCleanupIsLoggedSafelyAndContinuesRemainingDeletions(
      @TempDir Path tempDir) throws Exception {
    assumeTrue(
        Files.getFileStore(tempDir).supportsFileAttributeView(PosixFileAttributeView.class),
        "requires POSIX permission semantics");

    File remoteDir = tempDir.resolve("remote-repo").toFile();
    RevCommit commit1 =
        createRepo(
            remoteDir, Map.of("dev/SKILL.md", DEV_SKILL_MD, "docs/intro.md", "# Documentation"));
    String repoUrl = remoteDir.toURI().toString();

    // 缓存根名称携带合成秘密：任何把路径或原始正文写进日志的实现都会在断言中暴露。
    Path cacheRoot = tempDir.resolve(SYNTHETIC_SECRET + "-cache");
    JGitSkillCache cache = new JGitSkillCache(cacheRoot);
    cache.ensureCommit("test-pkg", repoUrl, commit1.getId().getName(), null);

    // 已发布缓存内的子目录去掉写权限：非 root 环境下递归删除会在该目录真实失败。
    Path readOnlyDir = cacheRoot.resolve("test-pkg/dev");
    Files.setPosixFilePermissions(readOnlyDir, PosixFilePermissions.fromString("r-x------"));
    assumeFalse(
        Files.isWritable(readOnlyDir),
        "read-only permissions cannot be enforced in this environment");

    RevCommit commit2;
    try (Git git = Git.open(remoteDir)) {
      Files.writeString(remoteDir.toPath().resolve("README.md"), "# root", StandardCharsets.UTF_8);
      git.add().addFilepattern(".").call();
      commit2 = git.commit().setMessage("Second commit").setSign(false).call();
    }

    try (SkillCacheLogCapture logs = new SkillCacheLogCapture()) {
      cache.ensureCommit("test-pkg", repoUrl, commit2.getId().getName(), null);
      logs.assertOnlySafeDeleteFailures();
    }

    assertEquals(
        commit2.getId().getName(),
        Files.readString(cacheRoot.resolve("test-pkg/.kkstudio-commit"), StandardCharsets.UTF_8)
            .trim());
    Path staleDir = findStaleWorkDir(cacheRoot);
    assertTrue(
        Files.exists(staleDir.resolve("dev")), "the undeletable read-only subtree is retained");
    assertFalse(
        Files.exists(staleDir.resolve("docs")),
        "cleanup must continue past a failing entry and delete the remaining writable subtree");
  }

  /** 定位重新发布留下的 {@code .old} 工作目录；清理失败时它必须存在。 */
  private static Path findStaleWorkDir(Path cacheRoot) throws IOException {
    try (Stream<Path> entries = Files.list(cacheRoot.resolve(".work"))) {
      return entries
          .filter(path -> path.getFileName().toString().endsWith(".old"))
          .findFirst()
          .orElseThrow();
    }
  }

  @Test
  public void ensureCommit_unknownCommit_throwsSkillGitException(@TempDir Path tempDir)
      throws Exception {
    // 测试意图：验证远端仓库不存在目标 commit 时 ensureCommit 抛出 SkillGitException 且不发布目录。
    File remoteDir = tempDir.resolve("remote-repo").toFile();
    createRepo(remoteDir, Map.of("README.md", "# test"));
    String repoUrl = remoteDir.toURI().toString();

    Path cacheRoot = tempDir.resolve("cache");
    JGitSkillCache cache = new JGitSkillCache(cacheRoot);

    String unknownCommit = "0123456789abcdef0123456789abcdef01234567";
    SkillGitException error =
        assertThrows(
            SkillGitException.class,
            () -> cache.ensureCommit("test-pkg", repoUrl, unknownCommit, null));
    assertTrue(
        error.getMessage().contains("Commit " + unknownCommit + " does not exist in repository"));
    assertFalse(Files.exists(cacheRoot.resolve("test-pkg")));
  }

  @Test
  public void readFile_unknownCommit_throwsSkillGitException(@TempDir Path tempDir)
      throws Exception {
    // 测试意图：验证在未发布的 commit 上读取文件时抛出 SkillGitException。
    File remoteDir = tempDir.resolve("remote-repo").toFile();
    RevCommit commit = createRepo(remoteDir, Map.of("README.md", "# test"));
    String repoUrl = remoteDir.toURI().toString();

    Path cacheRoot = tempDir.resolve("cache");
    JGitSkillCache cache = new JGitSkillCache(cacheRoot);
    cache.ensureCommit("test-pkg", repoUrl, commit.getId().getName(), null);

    String unknownCommit = "0123456789abcdef0123456789abcdef01234567";
    assertThrows(
        SkillGitException.class, () -> cache.readFile("test-pkg", unknownCommit, "README.md"));
  }

  @Test
  public void readFile_nonExistentPath_throwsSkillGitException(@TempDir Path tempDir)
      throws Exception {
    // 测试意图：验证读取不存在的文件路径时抛出 SkillGitException。
    File remoteDir = tempDir.resolve("remote-repo").toFile();
    RevCommit commit = createRepo(remoteDir, Map.of("README.md", "# test"));
    String repoUrl = remoteDir.toURI().toString();
    String commitId = commit.getId().getName();

    Path cacheRoot = tempDir.resolve("cache");
    JGitSkillCache cache = new JGitSkillCache(cacheRoot);
    cache.ensureCommit("test-pkg", repoUrl, commitId, null);

    assertThrows(
        SkillGitException.class, () -> cache.readFile("test-pkg", commitId, "dev/not-exist.txt"));
  }

  @Test
  public void readFile_directoryPath_throwsSkillGitException(@TempDir Path tempDir)
      throws Exception {
    // 测试意图：验证读取目录而非普通文件时抛出 SkillGitException。
    File remoteDir = tempDir.resolve("remote-repo").toFile();
    RevCommit commit = createRepo(remoteDir, Map.of("dev/SKILL.md", DEV_SKILL_MD));
    String repoUrl = remoteDir.toURI().toString();
    String commitId = commit.getId().getName();

    Path cacheRoot = tempDir.resolve("cache");
    JGitSkillCache cache = new JGitSkillCache(cacheRoot);
    cache.ensureCommit("test-pkg", repoUrl, commitId, null);

    assertThrows(SkillGitException.class, () -> cache.readFile("test-pkg", commitId, "dev"));
  }

  @Test
  public void readFile_symlink_throwsSkillGitException(@TempDir Path tempDir) throws Exception {
    // 测试意图：验证读取符号链接文件时抛出 SkillGitException（NOFOLLOW 校验）。
    Path publishedDir = tempDir.resolve("cache/test-pkg");
    Files.createDirectories(publishedDir);
    String commit = "a".repeat(40);
    Files.writeString(publishedDir.resolve(".kkstudio-commit"), commit);
    Path targetFile = publishedDir.resolve("target.txt");
    Files.writeString(targetFile, "target content");
    Path symlink = publishedDir.resolve("link.txt");
    try {
      Files.createSymbolicLink(symlink, Path.of("target.txt"));
    } catch (UnsupportedOperationException | IOException e) {
      return;
    }

    JGitSkillCache cache = new JGitSkillCache(tempDir.resolve("cache"));
    assertThrows(SkillGitException.class, () -> cache.readFile("test-pkg", commit, "link.txt"));
  }

  @Test
  public void readFile_illegalPaths_throwsSkillGitException(@TempDir Path tempDir)
      throws Exception {
    // 测试意图：验证对于含遍历段、绝对路径、反斜杠或空段等非法路径读取时均抛出 SkillGitException。
    File remoteDir = tempDir.resolve("remote-repo").toFile();
    RevCommit commit = createRepo(remoteDir, Map.of("README.md", "# test"));
    String repoUrl = remoteDir.toURI().toString();
    String commitId = commit.getId().getName();

    Path cacheRoot = tempDir.resolve("cache");
    JGitSkillCache cache = new JGitSkillCache(cacheRoot);
    cache.ensureCommit("test-pkg", repoUrl, commitId, null);

    List<String> illegalPaths =
        List.of("../x", "/etc/passwd", "a\\..\\b", "a//b", "dev/.", "dev/..", "", "dev/\0/test");

    for (String path : illegalPaths) {
      assertThrows(
          SkillGitException.class,
          () -> cache.readFile("test-pkg", commitId, path),
          "Expected illegal path rejection for: " + path);
    }
  }

  @Test
  public void scanManifest_missingFrontmatter_failsClosed(@TempDir Path tempDir) throws Exception {
    // 测试意图：验证当 SKILL.md 未以 '---' 开头时 ensureCommit 拒绝发布，scanManifest 亦无法读取。
    File remoteDir = tempDir.resolve("remote-repo").toFile();
    String badContent = "# Just markdown content without frontmatter";
    RevCommit commit = createRepo(remoteDir, Map.of("foo/SKILL.md", badContent));
    String repoUrl = remoteDir.toURI().toString();
    String commitId = commit.getId().getName();

    Path cacheRoot = tempDir.resolve("cache");
    JGitSkillCache cache = new JGitSkillCache(cacheRoot);

    assertThrows(
        SkillGitException.class, () -> cache.ensureCommit("test-pkg", repoUrl, commitId, null));
    assertFalse(Files.exists(cacheRoot.resolve("test-pkg")));
  }

  @Test
  public void scanManifest_unclosedFrontmatter_failsClosed(@TempDir Path tempDir) throws Exception {
    // 测试意图：验证当 frontmatter 缺失闭合 '---' 时 ensureCommit 拒绝发布。
    File remoteDir = tempDir.resolve("remote-repo").toFile();
    String badContent = "---\nname: foo\ndescription: desc\n";
    RevCommit commit = createRepo(remoteDir, Map.of("foo/SKILL.md", badContent));
    String repoUrl = remoteDir.toURI().toString();
    String commitId = commit.getId().getName();

    Path cacheRoot = tempDir.resolve("cache");
    JGitSkillCache cache = new JGitSkillCache(cacheRoot);

    assertThrows(
        SkillGitException.class, () -> cache.ensureCommit("test-pkg", repoUrl, commitId, null));
    assertFalse(Files.exists(cacheRoot.resolve("test-pkg")));
  }

  @Test
  public void scanManifest_missingDescription_failsClosed(@TempDir Path tempDir) throws Exception {
    // 测试意图：验证当 frontmatter 缺失 description 字段时 ensureCommit 拒绝发布。
    File remoteDir = tempDir.resolve("remote-repo").toFile();
    String badContent = """
        ---
        name: foo
        ---
        """;
    RevCommit commit = createRepo(remoteDir, Map.of("foo/SKILL.md", badContent));
    String repoUrl = remoteDir.toURI().toString();
    String commitId = commit.getId().getName();

    Path cacheRoot = tempDir.resolve("cache");
    JGitSkillCache cache = new JGitSkillCache(cacheRoot);

    assertThrows(
        SkillGitException.class, () -> cache.ensureCommit("test-pkg", repoUrl, commitId, null));
    assertFalse(Files.exists(cacheRoot.resolve("test-pkg")));
  }

  @Test
  public void scanManifest_emptyDescription_failsClosed(@TempDir Path tempDir) throws Exception {
    // 测试意图：验证当 frontmatter 中 description 为空或空白时 ensureCommit 拒绝发布。
    File remoteDir = tempDir.resolve("remote-repo").toFile();
    String badContent =
        """
        ---
        name: foo
        description: ""
        ---
        """;
    RevCommit commit = createRepo(remoteDir, Map.of("foo/SKILL.md", badContent));
    String repoUrl = remoteDir.toURI().toString();
    String commitId = commit.getId().getName();

    Path cacheRoot = tempDir.resolve("cache");
    JGitSkillCache cache = new JGitSkillCache(cacheRoot);

    assertThrows(
        SkillGitException.class, () -> cache.ensureCommit("test-pkg", repoUrl, commitId, null));
    assertFalse(Files.exists(cacheRoot.resolve("test-pkg")));
  }

  @Test
  public void scanManifest_nameMismatchDirectory_failsClosed(@TempDir Path tempDir)
      throws Exception {
    // 测试意图：验证当 frontmatter 中 name 与所在目录名不一致时 ensureCommit 拒绝发布。
    File remoteDir = tempDir.resolve("remote-repo").toFile();
    String badContent =
        """
        ---
        name: dir-other
        description: 合法描述
        ---
        """;
    RevCommit commit = createRepo(remoteDir, Map.of("dir-actual/SKILL.md", badContent));
    String repoUrl = remoteDir.toURI().toString();
    String commitId = commit.getId().getName();

    Path cacheRoot = tempDir.resolve("cache");
    JGitSkillCache cache = new JGitSkillCache(cacheRoot);

    assertThrows(
        SkillGitException.class, () -> cache.ensureCommit("test-pkg", repoUrl, commitId, null));
    assertFalse(Files.exists(cacheRoot.resolve("test-pkg")));
  }

  @Test
  public void scanManifest_invalidSkillDirName_failsClosed(@TempDir Path tempDir) throws Exception {
    // 测试意图：验证包含 SKILL.md 的目录名不符合 canonical skill 名规范时 ensureCommit 拒绝发布。
    File remoteDir = tempDir.resolve("remote-repo").toFile();
    String badContent =
        """
        ---
        name: bad@name
        description: 合法描述
        ---
        """;
    RevCommit commit = createRepo(remoteDir, Map.of("bad@name/SKILL.md", badContent));
    String repoUrl = remoteDir.toURI().toString();
    String commitId = commit.getId().getName();

    Path cacheRoot = tempDir.resolve("cache");
    JGitSkillCache cache = new JGitSkillCache(cacheRoot);

    assertThrows(
        SkillGitException.class, () -> cache.ensureCommit("test-pkg", repoUrl, commitId, null));
    assertFalse(Files.exists(cacheRoot.resolve("test-pkg")));
  }

  @Test
  public void scanManifest_duplicateSkillName_throwsSkillGitException(@TempDir Path tempDir)
      throws Exception {
    // 测试意图：验证相同 name 的技能在不同目录下定义时因名称冲突/不匹配抛出 SkillGitException。
    Path stage = tempDir.resolve("stage");
    Files.createDirectories(stage.resolve("skill-a"));
    Files.createDirectories(stage.resolve("skill-b"));
    Files.writeString(stage.resolve("skill-a/SKILL.md"), "---\nname: skill-a\ndescription: d\n---");
    Files.writeString(stage.resolve("skill-b/SKILL.md"), "---\nname: skill-a\ndescription: d\n---");
    assertThrows(SkillGitException.class, () -> SkillManifestScanner.scan(stage, "pkg"));
  }

  @Test
  public void symlinkSkillMd_failsClosedAndDoesNotPublish(@TempDir Path tempDir) throws Exception {
    // 测试意图：验证当 SKILL.md 为符号链接时 ensureCommit 拒绝并阻止发布。
    File remoteDir = tempDir.resolve("remote-repo").toFile();
    try (Git git = Git.init().setDirectory(remoteDir).setInitialBranch("main").call()) {
      File devDir = new File(remoteDir, "dev");
      devDir.mkdirs();
      File targetFile = new File(remoteDir, "target.txt");
      Files.writeString(targetFile.toPath(), DEV_SKILL_MD, StandardCharsets.UTF_8);

      Path symlink = devDir.toPath().resolve("SKILL.md");
      try {
        Files.createSymbolicLink(symlink, Path.of("../target.txt"));
      } catch (UnsupportedOperationException | IOException e) {
        return;
      }

      git.add().addFilepattern(".").call();
      RevCommit commit = git.commit().setMessage("Add symlink SKILL.md").setSign(false).call();
      String commitId = commit.getId().getName();
      String repoUrl = remoteDir.toURI().toString();

      Path cacheRoot = tempDir.resolve("cache");
      JGitSkillCache cache = new JGitSkillCache(cacheRoot);

      assertThrows(
          SkillGitException.class, () -> cache.ensureCommit("test-pkg", repoUrl, commitId, null));
      assertFalse(Files.exists(cacheRoot.resolve("test-pkg")));
    }
  }

  @Test
  public void symlinkAtRoot_failsClosedAndDoesNotPublish(@TempDir Path tempDir) throws Exception {
    // 测试意图：验证根目录存在符号链接时 ensureCommit 拒绝并阻止发布。
    File remoteDir = tempDir.resolve("remote-repo").toFile();
    try (Git git = Git.init().setDirectory(remoteDir).setInitialBranch("main").call()) {
      File targetFile = new File(remoteDir, "target.txt");
      Files.writeString(targetFile.toPath(), "content", StandardCharsets.UTF_8);

      Path symlink = remoteDir.toPath().resolve("link.txt");
      try {
        Files.createSymbolicLink(symlink, Path.of("target.txt"));
      } catch (UnsupportedOperationException | IOException e) {
        return;
      }

      git.add().addFilepattern(".").call();
      RevCommit commit = git.commit().setMessage("Add symlink at root").setSign(false).call();
      String commitId = commit.getId().getName();
      String repoUrl = remoteDir.toURI().toString();

      Path cacheRoot = tempDir.resolve("cache");
      JGitSkillCache cache = new JGitSkillCache(cacheRoot);

      assertThrows(
          SkillGitException.class, () -> cache.ensureCommit("test-pkg", repoUrl, commitId, null));
      assertFalse(Files.exists(cacheRoot.resolve("test-pkg")));
    }
  }

  @Test
  public void gitlinkSubmoduleAtRoot_failsClosedAndDoesNotPublish(@TempDir Path tempDir)
      throws Exception {
    // 测试意图：验证根目录下存在 submodule/gitlink 时 ensureCommit 拒绝并阻止发布。
    File remoteDir = tempDir.resolve("remote-repo").toFile();
    try (Git git = Git.init().setDirectory(remoteDir).setInitialBranch("main").call()) {
      Repository repo = git.getRepository();
      DirCache index = DirCache.newInCore();
      DirCacheBuilder builder = index.builder();
      DirCacheEntry entry = new DirCacheEntry("sub");
      entry.setFileMode(FileMode.GITLINK);
      entry.setObjectId(ObjectId.fromString("0".repeat(40)));
      builder.add(entry);
      builder.finish();

      ObjectId treeId;
      try (ObjectInserter inserter = repo.newObjectInserter()) {
        treeId = index.writeTree(inserter);
        CommitBuilder cb = new CommitBuilder();
        cb.setTreeId(treeId);
        PersonIdent person = new PersonIdent("test", "test@example.com");
        cb.setAuthor(person);
        cb.setCommitter(person);
        cb.setMessage("commit with gitlink");
        ObjectId commitId = inserter.insert(cb);
        inserter.flush();

        git.branchCreate().setName("main").setStartPoint(commitId.name()).call();

        Path cacheRoot = tempDir.resolve("cache");
        JGitSkillCache cache = new JGitSkillCache(cacheRoot);

        assertThrows(
            SkillGitException.class,
            () ->
                cache.ensureCommit(
                    "test-pkg", remoteDir.toURI().toString(), commitId.name(), null));
        assertFalse(Files.exists(cacheRoot.resolve("test-pkg")));
      }
    }
  }

  /**
   * 捕获 {@link JGitSkillCache} 的 WARN 日志：清理失败是普通 slf4j 记录，只有挂接 appender 才能断言其去敏可观察性。
   *
   * <p>{@link #close()} 摘除 appender，避免影响其它测试。
   */
  private static final class SkillCacheLogCapture implements AutoCloseable {

    private final Logger logger = (Logger) LoggerFactory.getLogger(JGitSkillCache.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private SkillCacheLogCapture() {
      appender.start();
      logger.addAppender(appender);
    }

    List<ILoggingEvent> events() {
      return List.copyOf(appender.list);
    }

    /** 真实清理失败必须可观察；每条日志只含固定阶段与异常类名，没有原始 throwable，也不回显合成秘密正文。 */
    void assertOnlySafeDeleteFailures() {
      assertFalse(events().isEmpty(), "a real cleanup failure must be observable");
      for (ILoggingEvent event : events()) {
        assertEquals(Level.WARN, event.getLevel());
        assertNull(event.getThrowableProxy(), "a raw throwable must never be logged");
        assertTrue(
            event
                .getFormattedMessage()
                .matches(
                    "skill cache recursive delete failed: stage=(delete|traversal),"
                        + " error=[A-Za-z0-9_.$]+"),
            event.getFormattedMessage());
        assertFalse(
            event.getFormattedMessage().contains(SYNTHETIC_SECRET),
            "the safe log must not echo the raw failure content");
      }
    }

    @Override
    public void close() {
      logger.detachAppender(appender);
      appender.stop();
    }
  }
}
