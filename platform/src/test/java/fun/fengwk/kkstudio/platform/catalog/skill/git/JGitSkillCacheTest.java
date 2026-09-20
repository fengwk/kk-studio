package fun.fengwk.kkstudio.platform.catalog.skill.git;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillManifestEntry;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class JGitSkillCacheTest {

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

    String resolvedCommit = cache.resolveBranchHead(repoUrl, "main");
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
        SkillGitException.class, () -> cache.resolveBranchHead(repoUrl, "non-existent-branch"));
  }

  @Test
  public void ensureCommit_fetchAndScanManifest_success(@TempDir Path tempDir) throws Exception {
    // 测试意图：验证首次 ensureCommit 能够拉取 commit，scanManifest 正确扫描根目录一级子目录并忽略 docs/ 和 README.md，按 name
    // 升序返回。
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

    cache.ensureCommit("test-pkg", repoUrl, commitId);

    List<SkillManifestEntry> manifest = cache.scanManifest("test-pkg", commitId);
    assertNotNull(manifest);
    assertEquals(2, manifest.size());

    assertEquals("chatgpt-agent", manifest.get(0).name());
    assertEquals("ChatGPT 智能助手技能", manifest.get(0).description());

    assertEquals("dev", manifest.get(1).name());
    assertEquals("开发者技能包含开发规范", manifest.get(1).description());
  }

  @Test
  public void ensureCommit_idempotentNoOp_whenCommitAlreadyCached(@TempDir Path tempDir)
      throws Exception {
    // 测试意图：验证当 commit 已经存在于本地 bare cache 时再次调用 ensureCommit 为 no-op，且不产生远端网络操作。
    File remoteDir = tempDir.resolve("remote-repo").toFile();
    RevCommit commit = createRepo(remoteDir, Map.of("README.md", "# test"));
    String repoUrl = remoteDir.toURI().toString();
    String commitId = commit.getId().getName();

    Path cacheRoot = tempDir.resolve("cache");
    JGitSkillCache cache = new JGitSkillCache(cacheRoot);

    cache.ensureCommit("test-pkg", repoUrl, commitId);

    // 第二次调用传入无效的 URL，若仍有 fetch 则会失败，无 fetch 则应直接返回
    cache.ensureCommit("test-pkg", "file:///non/existent/path/repo.git", commitId);
  }

  @Test
  public void ensureCommit_unknownCommit_throwsSkillGitException(@TempDir Path tempDir)
      throws Exception {
    // 测试意图：验证远端仓库不存在目标 commit 时 ensureCommit 抛出 SkillGitException。
    File remoteDir = tempDir.resolve("remote-repo").toFile();
    createRepo(remoteDir, Map.of("README.md", "# test"));
    String repoUrl = remoteDir.toURI().toString();

    Path cacheRoot = tempDir.resolve("cache");
    JGitSkillCache cache = new JGitSkillCache(cacheRoot);

    String unknownCommit = "0123456789abcdef0123456789abcdef01234567";
    assertThrows(
        SkillGitException.class, () -> cache.ensureCommit("test-pkg", repoUrl, unknownCommit));
  }

  @Test
  public void readFile_success(@TempDir Path tempDir) throws Exception {
    // 测试意图：验证 readFile 能够正确读取仓库内目标文件的原始字节。
    File remoteDir = tempDir.resolve("remote-repo").toFile();
    RevCommit commit =
        createRepo(remoteDir, Map.of("dev/SKILL.md", DEV_SKILL_MD, "README.md", "# Root readme"));
    String repoUrl = remoteDir.toURI().toString();
    String commitId = commit.getId().getName();

    Path cacheRoot = tempDir.resolve("cache");
    JGitSkillCache cache = new JGitSkillCache(cacheRoot);
    cache.ensureCommit("test-pkg", repoUrl, commitId);

    byte[] bytes = cache.readFile("test-pkg", commitId, "dev/SKILL.md");
    assertArrayEquals(DEV_SKILL_MD.getBytes(StandardCharsets.UTF_8), bytes);
  }

  @Test
  public void readFile_unknownCommit_throwsSkillGitException(@TempDir Path tempDir)
      throws Exception {
    // 测试意图：验证在未知 commit 上读取文件时抛出 SkillGitException。
    File remoteDir = tempDir.resolve("remote-repo").toFile();
    RevCommit commit = createRepo(remoteDir, Map.of("README.md", "# test"));
    String repoUrl = remoteDir.toURI().toString();

    Path cacheRoot = tempDir.resolve("cache");
    JGitSkillCache cache = new JGitSkillCache(cacheRoot);
    cache.ensureCommit("test-pkg", repoUrl, commit.getId().getName());

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
    cache.ensureCommit("test-pkg", repoUrl, commitId);

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
    cache.ensureCommit("test-pkg", repoUrl, commitId);

    assertThrows(SkillGitException.class, () -> cache.readFile("test-pkg", commitId, "dev"));
  }

  @Test
  public void readFile_symlink_throwsSkillGitException(@TempDir Path tempDir) throws Exception {
    // 测试意图：验证读取符号链接文件时抛出 SkillGitException。
    File remoteDir = tempDir.resolve("remote-repo").toFile();
    try (Git git = Git.init().setDirectory(remoteDir).setInitialBranch("main").call()) {
      File target = new File(remoteDir, "target.txt");
      Files.writeString(target.toPath(), "target content", StandardCharsets.UTF_8);
      Path symlink = remoteDir.toPath().resolve("link.txt");
      try {
        Files.createSymbolicLink(symlink, Path.of("target.txt"));
      } catch (UnsupportedOperationException | IOException e) {
        // 当前文件系统不支持符号链接，跳过测试
        return;
      }
      git.add().addFilepattern(".").call();
      RevCommit commit = git.commit().setMessage("Add symlink").setSign(false).call();
      String commitId = commit.getId().getName();
      String repoUrl = remoteDir.toURI().toString();

      Path cacheRoot = tempDir.resolve("cache");
      JGitSkillCache cache = new JGitSkillCache(cacheRoot);
      cache.ensureCommit("test-pkg", repoUrl, commitId);

      assertThrows(SkillGitException.class, () -> cache.readFile("test-pkg", commitId, "link.txt"));
    }
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
    cache.ensureCommit("test-pkg", repoUrl, commitId);

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
  public void scanManifest_missingFrontmatter_throwsSkillGitException(@TempDir Path tempDir)
      throws Exception {
    // 测试意图：验证当 SKILL.md 未以 '---' 开头时 scanManifest 拒绝并抛出 SkillGitException。
    File remoteDir = tempDir.resolve("remote-repo").toFile();
    String badContent = "# Just markdown content without frontmatter";
    RevCommit commit = createRepo(remoteDir, Map.of("foo/SKILL.md", badContent));
    String repoUrl = remoteDir.toURI().toString();
    String commitId = commit.getId().getName();

    Path cacheRoot = tempDir.resolve("cache");
    JGitSkillCache cache = new JGitSkillCache(cacheRoot);
    cache.ensureCommit("test-pkg", repoUrl, commitId);

    assertThrows(SkillGitException.class, () -> cache.scanManifest("test-pkg", commitId));
  }

  @Test
  public void scanManifest_missingDescription_throwsSkillGitException(@TempDir Path tempDir)
      throws Exception {
    // 测试意图：验证当 frontmatter 缺失 description 字段时 scanManifest 拒绝并抛出 SkillGitException。
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
    cache.ensureCommit("test-pkg", repoUrl, commitId);

    assertThrows(SkillGitException.class, () -> cache.scanManifest("test-pkg", commitId));
  }

  @Test
  public void scanManifest_emptyDescription_throwsSkillGitException(@TempDir Path tempDir)
      throws Exception {
    // 测试意图：验证当 frontmatter 中 description 为空或空白时 scanManifest 拒绝并抛出 SkillGitException。
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
    cache.ensureCommit("test-pkg", repoUrl, commitId);

    assertThrows(SkillGitException.class, () -> cache.scanManifest("test-pkg", commitId));
  }

  @Test
  public void scanManifest_descriptionExceeds1024Chars_throwsSkillGitException(
      @TempDir Path tempDir) throws Exception {
    // 测试意图：验证当 frontmatter 中 description 超过 1024 字符时 scanManifest 拒绝并抛出 SkillGitException。
    File remoteDir = tempDir.resolve("remote-repo").toFile();
    String longDesc = "a".repeat(1025);
    String badContent =
        """
        ---
        name: foo
        description: %s
        ---
        """
            .formatted(longDesc);
    RevCommit commit = createRepo(remoteDir, Map.of("foo/SKILL.md", badContent));
    String repoUrl = remoteDir.toURI().toString();
    String commitId = commit.getId().getName();

    Path cacheRoot = tempDir.resolve("cache");
    JGitSkillCache cache = new JGitSkillCache(cacheRoot);
    cache.ensureCommit("test-pkg", repoUrl, commitId);

    assertThrows(SkillGitException.class, () -> cache.scanManifest("test-pkg", commitId));
  }

  @Test
  public void scanManifest_nameMismatchDirectory_throwsSkillGitException(@TempDir Path tempDir)
      throws Exception {
    // 测试意图：验证当 frontmatter 中 name 与所在目录名不一致时 scanManifest 拒绝并抛出 SkillGitException。
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
    cache.ensureCommit("test-pkg", repoUrl, commitId);

    assertThrows(SkillGitException.class, () -> cache.scanManifest("test-pkg", commitId));
  }

  @Test
  public void scanManifest_symlinkSkillMd_throwsSkillGitException(@TempDir Path tempDir)
      throws Exception {
    // 测试意图：验证当 SKILL.md 为符号链接时 scanManifest 拒绝并抛出 SkillGitException 以防止越界穿透。
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
        // 当前文件系统不支持 symlink，跳过测试
        return;
      }

      git.add().addFilepattern(".").call();
      RevCommit commit = git.commit().setMessage("Add symlink SKILL.md").setSign(false).call();
      String commitId = commit.getId().getName();
      String repoUrl = remoteDir.toURI().toString();

      Path cacheRoot = tempDir.resolve("cache");
      JGitSkillCache cache = new JGitSkillCache(cacheRoot);
      cache.ensureCommit("test-pkg", repoUrl, commitId);

      assertThrows(SkillGitException.class, () -> cache.scanManifest("test-pkg", commitId));
    }
  }

  @Test
  public void scanManifest_invalidSkillDirName_throwsSkillGitException(@TempDir Path tempDir)
      throws Exception {
    // 测试意图：验证包含 SKILL.md 的目录名不符合 canonical skill 名规范时抛出 SkillGitException。
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
    cache.ensureCommit("test-pkg", repoUrl, commitId);

    assertThrows(SkillGitException.class, () -> cache.scanManifest("test-pkg", commitId));
  }
}
